package com.lynxus.worker.runtime;

import com.lynxus.contracts.http.HttpUrls;
import com.lynxus.contracts.session.SessionContracts.AgentTurnStreamFrame;
import com.lynxus.contracts.session.SessionContracts.AgentTurnStreamFrameKind;
import com.lynxus.contracts.session.SessionContracts.AgentTurnRequest;
import com.lynxus.contracts.session.SessionContracts.AgentTurnExecutionOutcome;
import com.lynxus.contracts.session.SessionContracts.AgentTurnTransientFrame;
import com.lynxus.contracts.session.SessionContracts.AgentTurnTransientFrameKind;
import com.lynxus.contracts.session.SessionContracts.FinalOutcomePayload;
import com.lynxus.contracts.session.SessionContracts.PlaybookToolTaskRequest;
import com.lynxus.contracts.session.SessionContracts.PlaybookToolTaskResult;
import com.lynxus.contracts.session.SessionContracts.StreamVisibility;
import com.lynxus.contracts.session.SessionContracts.TurnCompletionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

public interface SessionAgentRuntimeGateway {
    AgentTurnExecutionOutcome executeTurn(AgentTurnRequest request);

    default AgentTurnExecutionOutcome executeTurnStream(AgentTurnRequest request) {
        return executeTurn(request);
    }

    PlaybookToolTaskResult executePlaybookToolTask(PlaybookToolTaskRequest request);

    @Component
    class HttpSessionAgentRuntimeGateway implements SessionAgentRuntimeGateway {
        private static final Logger log = LoggerFactory.getLogger(HttpSessionAgentRuntimeGateway.class);
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final ObjectMapper objectMapper;
        private final String agentRuntimeBaseUrl;
        private final String apiBaseUrl;
        private final String authorizationHeaderValue;
        private final Duration streamIdleTimeout;
        private final Counter streamStallCounter;
        private final Counter missingFinalOutcomeCounter;
        private final Counter streamRelayFailureCounter;
        private static final int INGEST_PIPE_BUFFER_BYTES = 64 * 1024;

        public HttpSessionAgentRuntimeGateway(
            @Value("${lynxus.agent-runtime.base-url}") String agentRuntimeBaseUrl,
            @Value("${lynxus.api.base-url:http://127.0.0.1:8080/api}") String apiBaseUrl,
            @Value("${lynxus.internal-auth.token}") String internalAuthToken,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${lynxus.agent-runtime.stream-idle-timeout:PT30S}") Duration streamIdleTimeout
        ) {
            this.agentRuntimeBaseUrl = agentRuntimeBaseUrl;
            this.apiBaseUrl = apiBaseUrl;
            this.authorizationHeaderValue = "Bearer " + requireInternalAuthToken(internalAuthToken);
            this.objectMapper = objectMapper.rebuild()
                .findAndAddModules()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
            this.streamIdleTimeout = normalizedTimeout(streamIdleTimeout);
            this.streamStallCounter = Counter.builder("lynxus.runtime_stream.stream_stall")
                .description("Number of agent-runtime streaming stalls observed by the worker")
                .register(meterRegistry);
            this.missingFinalOutcomeCounter = Counter.builder("lynxus.runtime_stream.missing_final_outcome")
                .description("Number of agent-runtime streams that ended without FINAL_OUTCOME")
                .register(meterRegistry);
            this.streamRelayFailureCounter = Counter.builder("lynxus.runtime_stream.relay_failure")
                .description("Number of transient stream frames the worker could not relay to the API")
                .register(meterRegistry);
        }

        @Override
        public AgentTurnExecutionOutcome executeTurn(AgentTurnRequest request) {
            return executeTurnStream(request);
        }

        @Override
        public AgentTurnExecutionOutcome executeTurnStream(AgentTurnRequest request) {
            StreamReadContext context = StreamReadContext.from(request);
            try {
                String requestBody = objectMapper.writeValueAsString(request);
                HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(HttpUrls.join(agentRuntimeBaseUrl, "/agent-turns/execute-stream"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/x-ndjson")
                    .header("Authorization", authorizationHeaderValue)
                    .timeout(streamIdleTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
                HttpResponse<InputStream> response;
                try {
                    response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                } catch (HttpTimeoutException error) {
                    recordStreamStall(context, "request", error);
                    try (TransientFrameIngestSession frameIngest = startTransientFrameIngest(context)) {
                        relaySyntheticError(frameIngest, context, 1, "WORKER_STREAM_STALL", "agent-runtime stream request timed out", false);
                    }
                    throw new IllegalStateException("agent-runtime stream stalled before response", error);
                } catch (IOException error) {
                    try (TransientFrameIngestSession frameIngest = startTransientFrameIngest(context)) {
                        relaySyntheticError(frameIngest, context, 1, "WORKER_STREAM_ABORTED", "agent-runtime stream request failed", false);
                    }
                    throw error;
                }
                if (response.statusCode() >= 400) {
                    String body = readBody(response.body());
                    log.error("agent-turn streaming execution failed status={} body={}", response.statusCode(), body);
                    throw new IllegalStateException("agent-turn streaming execution failed: " + response.statusCode() + " " + body);
                }
                try (TransientFrameIngestSession frameIngest = startTransientFrameIngest(context)) {
                    return readTurnStream(response.body(), request, context, frameIngest);
                }
            } catch (IOException | InterruptedException error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("failed to invoke session agent-runtime stream endpoint", error);
            }
        }

        @Override
        public PlaybookToolTaskResult executePlaybookToolTask(PlaybookToolTaskRequest request) {
            return post(
                "/playbook-tool-tasks/execute",
                request,
                PlaybookToolTaskResult.class,
                "playbook tool task execution failed"
            );
        }

        private <T> T post(String path, Object payload, Class<T> responseType, String failurePrefix) {
            try {
                String requestBody = objectMapper.writeValueAsString(payload);
                HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(HttpUrls.join(agentRuntimeBaseUrl, path))
                    .header("Content-Type", "application/json")
                    .header("Authorization", authorizationHeaderValue)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    log.error("{} status={} body={}", failurePrefix, response.statusCode(), response.body());
                    throw new IllegalStateException(failurePrefix + ": " + response.statusCode() + " " + response.body());
                }
                return objectMapper.readValue(response.body(), responseType);
            } catch (IOException | InterruptedException error) {
                throw new IllegalStateException("failed to invoke session agent-runtime endpoint", error);
            }
        }

        private AgentTurnExecutionOutcome readTurnStream(
            InputStream body,
            AgentTurnRequest request,
            StreamReadContext context,
            TransientFrameIngestSession frameIngest
        ) throws IOException, InterruptedException {
            AgentTurnExecutionOutcome finalOutcome = null;
            int finalOutcomeCount = 0;
            long lastSeq = 0;
            ExecutorService readExecutor = newDaemonBoundedSingleThreadExecutor(
                "agent-runtime-stream-reader-" + context.turnExecutionId()
            );
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = readLineWithIdleDeadline(reader, readExecutor, context)) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    AgentTurnStreamFrame frame = readStreamFrame(line);
                    lastSeq = Math.max(lastSeq, frame.seq());
                    log.info(
                        "agent-runtime stream frame received provider=agent-runtime worker=temporal sessionId={} turnId={} turnExecutionId={} streamSeq={} frameKind={} visibility={}",
                        frame.sessionId(),
                        frame.turnId(),
                        frame.turnExecutionId(),
                        frame.seq(),
                        frame.kind(),
                        frame.visibility()
                    );
                    if (frame.kind() == AgentTurnStreamFrameKind.FINAL_OUTCOME) {
                        finalOutcomeCount += 1;
                        if (finalOutcomeCount > 1) {
                            throw streamProtocolFailure(
                                frameIngest,
                                context,
                                lastSeq + 1,
                                "DUPLICATE_FINAL_OUTCOME",
                                "agent-runtime stream returned duplicate FINAL_OUTCOME",
                                null
                            );
                        }
                        try {
                            finalOutcome = readFinalOutcome(frame, context);
                        } catch (RuntimeException error) {
                            throw streamProtocolFailure(
                                frameIngest,
                                context,
                                lastSeq + 1,
                                "INVALID_FINAL_OUTCOME",
                                "FINAL_OUTCOME frame missing or invalid payload.messageId/payload.outcome",
                                error
                            );
                        }
                        continue;
                    } else {
                        relayRuntimeFrame(frameIngest, AgentTurnTransientFrame.fromStreamFrame(frame));
                    }
                }
            } catch (StreamProtocolFailureException error) {
                throw error;
            } catch (StreamIdleTimeoutException error) {
                relaySyntheticError(frameIngest, context, lastSeq + 1, "WORKER_STREAM_STALL", "agent-runtime stream stalled while reading", false);
                throw error;
            } catch (IOException error) {
                relaySyntheticError(frameIngest, context, lastSeq + 1, "WORKER_STREAM_ABORTED", "agent-runtime stream aborted or emitted malformed NDJSON", false);
                throw error;
            } finally {
                readExecutor.shutdownNow();
            }
            if (finalOutcomeCount == 0 || finalOutcome == null) {
                missingFinalOutcomeCounter.increment();
                log.error(
                    "agent-runtime stream ended without FINAL_OUTCOME provider=agent-runtime worker=temporal sessionId={} turnId={} turnExecutionId={} streamSeq={}",
                    context.sessionId(),
                    context.turnId(),
                    context.turnExecutionId(),
                    lastSeq
                );
                relaySyntheticError(frameIngest, context, lastSeq + 1, "MISSING_FINAL_OUTCOME", "agent-runtime stream ended without FINAL_OUTCOME", false);
                throw new IllegalStateException("agent-runtime stream ended without FINAL_OUTCOME");
            }
            relayTurnCompleted(frameIngest, context, lastSeq + 1, finalOutcome.success());
            return finalOutcome;
        }

        private String readLineWithIdleDeadline(
            BufferedReader reader,
            ExecutorService readExecutor,
            StreamReadContext context
        ) throws IOException, InterruptedException {
            Future<String> readFuture = readExecutor.submit(reader::readLine);
            try {
                return readFuture.get(Math.max(1, streamIdleTimeout.toMillis()), TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                readFuture.cancel(true);
                recordStreamStall(context, "read", error);
                throw new StreamIdleTimeoutException("agent-runtime stream stalled for " + streamIdleTimeout);
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof IOException ioError) {
                    throw ioError;
                }
                throw new IllegalStateException("agent-runtime stream reader failed", cause);
            }
        }

        private AgentTurnStreamFrame readStreamFrame(String line) throws IOException {
            try {
                return objectMapper.readValue(line, AgentTurnStreamFrame.class);
            } catch (JacksonException error) {
                throw new IOException("agent-runtime emitted malformed stream frame", error);
            }
        }

        private AgentTurnExecutionOutcome readFinalOutcome(AgentTurnStreamFrame frame, StreamReadContext context) {
            if (!(frame.payload() instanceof FinalOutcomePayload payload) || payload.outcome() == null) {
                throw new IllegalStateException("FINAL_OUTCOME frame missing payload.outcome");
            }
            if (!context.replyMessageId().equals(payload.messageId())) {
                throw new IllegalStateException("FINAL_OUTCOME payload.messageId does not match request replyMessageId");
            }
            return payload.outcome();
        }

        private void relayRuntimeFrame(TransientFrameIngestSession frameIngest, AgentTurnTransientFrame frame) throws InterruptedException {
            frameIngest.writeFrame(frame);
        }

        private void relayTurnCompleted(
            TransientFrameIngestSession frameIngest,
            StreamReadContext context,
            long seq,
            boolean success
        ) throws InterruptedException {
            long completedSeq = Math.max(1, seq);
            relayRuntimeFrame(frameIngest, new AgentTurnTransientFrame(
                AgentTurnTransientFrame.PROTOCOL,
                context.turnExecutionId() + ":" + completedSeq,
                "worker:" + context.turnExecutionId(),
                context.sessionId(),
                context.turnId(),
                context.turnExecutionId(),
                context.ownerAgentId(),
                context.ownershipEpoch(),
                completedSeq,
                AgentTurnTransientFrameKind.TURN_COMPLETED,
                StreamVisibility.OPERATOR,
                Instant.now(),
                Map.of(
                    "messageId",
                    context.replyMessageId(),
                    "status",
                    success ? TurnCompletionStatus.SUCCEEDED : TurnCompletionStatus.FAILED
                )
            ));
        }

        private void relaySyntheticError(
            TransientFrameIngestSession frameIngest,
            StreamReadContext context,
            long seq,
            String code,
            String message,
            boolean retryable
        ) {
            long errorSeq = Math.max(1, seq);
            AgentTurnTransientFrame frame = new AgentTurnTransientFrame(
                AgentTurnTransientFrame.PROTOCOL,
                context.turnExecutionId() + ":" + errorSeq,
                "worker:" + context.turnExecutionId(),
                context.sessionId(),
                context.turnId(),
                context.turnExecutionId(),
                context.ownerAgentId(),
                context.ownershipEpoch(),
                errorSeq,
                AgentTurnTransientFrameKind.ERROR,
                StreamVisibility.OPERATOR,
                Instant.now(),
                Map.of(
                    "code",
                    code,
                    "messageId",
                    context.replyMessageId(),
                    "message",
                    message,
                    "stage",
                    syntheticErrorStage(code),
                    "retryable",
                    retryable,
                    "details",
                    Map.of("source", "worker", "stage", "agent-runtime-stream")
                )
            );
            try {
                frameIngest.writeFrame(frame);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                log.warn(
                    "failed to relay synthetic stream error provider=api worker=temporal sessionId={} turnId={} turnExecutionId={} streamSeq={} code={} reason={}",
                    context.sessionId(),
                    context.turnId(),
                    context.turnExecutionId(),
                    errorSeq,
                    code,
                    error.toString()
                );
            }
        }

        private static String syntheticErrorStage(String code) {
            return switch (code) {
                case "DUPLICATE_FINAL_OUTCOME", "INVALID_FINAL_OUTCOME", "MISSING_FINAL_OUTCOME" -> "FINAL_OUTCOME_BUILD";
                default -> "PROVIDER_STREAM";
            };
        }

        private StreamProtocolFailureException streamProtocolFailure(
            TransientFrameIngestSession frameIngest,
            StreamReadContext context,
            long seq,
            String code,
            String message,
            Exception cause
        ) {
            relaySyntheticError(frameIngest, context, seq, code, message, false);
            return new StreamProtocolFailureException(message, cause);
        }

        private TransientFrameIngestSession startTransientFrameIngest(StreamReadContext context) {
            try {
                PipedInputStream input = new PipedInputStream(INGEST_PIPE_BUFFER_BYTES);
                PipedOutputStream output = new PipedOutputStream(input);
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(HttpUrls.join(apiBaseUrl, "/internal/session-runtime/stream-frame-ingest"))
                    .header("Content-Type", "application/x-ndjson")
                    .header("Accept", "application/json")
                    .header("Authorization", authorizationHeaderValue)
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> input))
                    .build();
                CompletableFuture<HttpResponse<String>> responseFuture = httpClient.sendAsync(
                    request,
                    HttpResponse.BodyHandlers.ofString()
                );
                return new TransientFrameIngestSession(context, output, responseFuture);
            } catch (IOException | RuntimeException error) {
                recordStreamRelayFailure(context, asException(error));
                return new TransientFrameIngestSession(context);
            }
        }

        private final class TransientFrameIngestSession implements AutoCloseable {
            private final StreamReadContext context;
            private final PipedOutputStream output;
            private final CompletableFuture<HttpResponse<String>> responseFuture;
            private final ExecutorService writeExecutor;
            private final AtomicBoolean failureRecorded = new AtomicBoolean(false);
            private final AtomicBoolean closed = new AtomicBoolean(false);

            private TransientFrameIngestSession(
                StreamReadContext context,
                PipedOutputStream output,
                CompletableFuture<HttpResponse<String>> responseFuture
            ) {
                this.context = context;
                this.output = output;
                this.responseFuture = responseFuture;
                this.writeExecutor = newDaemonBoundedSingleThreadExecutor(
                    "api-stream-frame-ingest-writer-" + context.turnExecutionId()
                );
            }

            private TransientFrameIngestSession(StreamReadContext context) {
                this.context = context;
                this.output = null;
                this.responseFuture = null;
                this.writeExecutor = null;
                this.failureRecorded.set(true);
                this.closed.set(true);
            }

            private void writeFrame(AgentTurnTransientFrame frame) throws InterruptedException {
                if (output == null || failureRecorded.get() || closed.get()) {
                    return;
                }
                byte[] body;
                try {
                    body = (objectMapper.writeValueAsString(frame) + "\n").getBytes(StandardCharsets.UTF_8);
                } catch (RuntimeException error) {
                    markFailure(frame, asException(error));
                    return;
                }
                Future<?> writeFuture;
                try {
                    writeFuture = writeExecutor.submit(() -> {
                        output.write(body);
                        output.flush();
                        return null;
                    });
                } catch (RejectedExecutionException error) {
                    markFailure(frame, error);
                    return;
                }
                try {
                    writeFuture.get(Math.max(1, streamIdleTimeout.toMillis()), TimeUnit.MILLISECONDS);
                } catch (TimeoutException error) {
                    writeFuture.cancel(true);
                    markFailure(frame, new HttpTimeoutException("session stream frame ingest write timed out"));
                    closeOutputQuietly();
                    responseFuture.cancel(true);
                } catch (ExecutionException error) {
                    markFailure(frame, asException(error.getCause()));
                    closeOutputQuietly();
                } catch (InterruptedException error) {
                    writeFuture.cancel(true);
                    throw error;
                }
            }

            @Override
            public void close() {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                closeOutputQuietly();
                if (writeExecutor != null) {
                    writeExecutor.shutdownNow();
                }
                observeIngestResponse();
            }

            private void observeIngestResponse() {
                if (responseFuture == null || failureRecorded.get()) {
                    return;
                }
                try {
                    HttpResponse<String> response = responseFuture.get(
                        Math.max(1, streamIdleTimeout.toMillis()),
                        TimeUnit.MILLISECONDS
                    );
                    if (response.statusCode() >= 400) {
                        markFailure(
                            context,
                            new IllegalStateException(
                                "session stream frame ingest failed: " + response.statusCode() + " " + response.body()
                            )
                        );
                    }
                } catch (TimeoutException error) {
                    responseFuture.cancel(true);
                    markFailure(context, new HttpTimeoutException("session stream frame ingest response timed out"));
                } catch (ExecutionException error) {
                    markFailure(context, asException(error.getCause()));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    markFailure(context, error);
                } catch (RuntimeException error) {
                    markFailure(context, error);
                }
            }

            private void markFailure(AgentTurnTransientFrame frame, Exception error) {
                if (failureRecorded.compareAndSet(false, true)) {
                    recordStreamRelayFailure(frame, error);
                }
            }

            private void markFailure(StreamReadContext context, Exception error) {
                if (failureRecorded.compareAndSet(false, true)) {
                    recordStreamRelayFailure(context, error);
                }
            }

            private void closeOutputQuietly() {
                if (output == null) {
                    return;
                }
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void recordStreamStall(StreamReadContext context, String stage, Exception error) {
            streamStallCounter.increment();
            log.error(
                "agent-runtime stream stalled provider=agent-runtime worker=temporal sessionId={} turnId={} turnExecutionId={} stage={} idleTimeoutMs={} reason={}",
                context.sessionId(),
                context.turnId(),
                context.turnExecutionId(),
                stage,
                streamIdleTimeout.toMillis(),
                error == null ? "" : error.toString()
            );
        }

        private void recordStreamRelayFailure(AgentTurnTransientFrame frame, Exception error) {
            streamRelayFailureCounter.increment();
            log.warn(
                "transient stream frame relay failed provider=api worker=temporal sessionId={} turnId={} turnExecutionId={} streamSeq={} frameKind={} visibility={} frameId={} reason={}",
                frame.sessionId(),
                frame.turnId(),
                frame.turnExecutionId(),
                frame.seq(),
                frame.kind(),
                frame.visibility(),
                frame.frameId(),
                error == null ? "" : error.toString()
            );
        }

        private void recordStreamRelayFailure(StreamReadContext context, Exception error) {
            streamRelayFailureCounter.increment();
            log.warn(
                "transient stream frame ingest failed provider=api worker=temporal sessionId={} turnId={} turnExecutionId={} reason={}",
                context.sessionId(),
                context.turnId(),
                context.turnExecutionId(),
                error == null ? "" : error.toString()
            );
        }

        private ExecutorService newDaemonBoundedSingleThreadExecutor(String threadName) {
            return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                task -> {
                    Thread thread = new Thread(task, threadName);
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
            );
        }

        private static Exception asException(Throwable error) {
            if (error instanceof Exception exception) {
                return exception;
            }
            return new RuntimeException(error);
        }

        private static Duration normalizedTimeout(Duration timeout) {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                return Duration.ofSeconds(30);
            }
            return timeout;
        }

        private static String readBody(InputStream body) throws IOException {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        }

        private static String requireInternalAuthToken(String internalAuthToken) {
            if (internalAuthToken == null || internalAuthToken.isBlank()) {
                throw new IllegalStateException("lynxus.internal-auth.token must be configured");
            }
            return internalAuthToken.trim();
        }

        private record StreamReadContext(
            String sessionId,
            String turnId,
            String turnExecutionId,
            String replyMessageId,
            String ownerAgentId,
            long ownershipEpoch
        ) {
            private static StreamReadContext from(AgentTurnRequest request) {
                String ownerAgentId = request.currentOwner() == null ? "" : request.currentOwner().agentId();
                return new StreamReadContext(
                    safeString(request.sessionId()),
                    safeString(request.turnId()),
                    safeString(request.turnExecutionId()),
                    safeString(request.replyMessageId()),
                    safeString(ownerAgentId),
                    request.ownershipEpoch()
                );
            }

            private static String safeString(String value) {
                return value == null ? "" : value;
            }
        }

        private static final class StreamIdleTimeoutException extends IOException {
            private StreamIdleTimeoutException(String message) {
                super(message);
            }
        }

        private static final class StreamProtocolFailureException extends IOException {
            private StreamProtocolFailureException(String message, Exception cause) {
                super(message, cause);
            }
        }

    }
}
