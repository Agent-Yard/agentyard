package com.lynxus.worker.runtime;

import com.lynxus.contracts.session.SessionContracts.AgentTurnStreamFrame;
import com.lynxus.contracts.session.SessionContracts.AgentTurnStreamFrameKind;
import com.lynxus.contracts.session.SessionContracts.AgentTurnRequest;
import com.lynxus.contracts.session.SessionContracts.AgentTurnExecutionOutcome;
import com.lynxus.contracts.session.SessionContracts.PlaybookToolTaskRequest;
import com.lynxus.contracts.session.SessionContracts.PlaybookToolTaskResult;
import com.lynxus.contracts.session.SessionContracts.StreamVisibility;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
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

        public HttpSessionAgentRuntimeGateway(
            @Value("${lynxus.agent-runtime.base-url}") String agentRuntimeBaseUrl,
            @Value("${lynxus.api.base-url:http://127.0.0.1:8080}") String apiBaseUrl,
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
                    .uri(URI.create(agentRuntimeBaseUrl + "/agent-turns/execute-stream"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/x-ndjson")
                    .header("Authorization", authorizationHeaderValue)
                    .timeout(streamIdleTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
                HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 400) {
                    String body = readBody(response.body());
                    log.error("agent-turn streaming execution failed status={} body={}", response.statusCode(), body);
                    throw new IllegalStateException("agent-turn streaming execution failed: " + response.statusCode() + " " + body);
                }
                return readTurnStream(response.body(), request, context);
            } catch (HttpTimeoutException error) {
                recordStreamStall(context, "request", error);
                relaySyntheticError(context, 1, "WORKER_STREAM_STALL", "agent-runtime stream request timed out", false);
                throw new IllegalStateException("agent-runtime stream stalled before response", error);
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
                    .uri(URI.create(agentRuntimeBaseUrl + path))
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
            StreamReadContext context
        ) throws IOException, InterruptedException {
            AgentTurnExecutionOutcome finalOutcome = null;
            int finalOutcomeCount = 0;
            long lastSeq = 0;
            ExecutorService readExecutor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "agent-runtime-stream-reader-" + context.turnExecutionId());
                thread.setDaemon(true);
                return thread;
            });
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
                                context,
                                lastSeq + 1,
                                "DUPLICATE_FINAL_OUTCOME",
                                "agent-runtime stream returned duplicate FINAL_OUTCOME",
                                null
                            );
                        }
                        try {
                            finalOutcome = readFinalOutcome(frame);
                        } catch (IOException | RuntimeException error) {
                            throw streamProtocolFailure(
                                context,
                                lastSeq + 1,
                                "INVALID_FINAL_OUTCOME",
                                "FINAL_OUTCOME frame missing or invalid payload.outcome",
                                error
                            );
                        }
                    } else {
                        relayRuntimeFrame(frame, context);
                    }
                }
            } catch (StreamProtocolFailureException | StreamRelayFailureException error) {
                throw error;
            } catch (StreamIdleTimeoutException error) {
                relaySyntheticError(context, lastSeq + 1, "WORKER_STREAM_STALL", "agent-runtime stream stalled while reading", false);
                throw error;
            } catch (IOException error) {
                relaySyntheticError(context, lastSeq + 1, "WORKER_STREAM_ABORTED", "agent-runtime stream aborted or emitted malformed NDJSON", false);
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
                relaySyntheticError(context, lastSeq + 1, "MISSING_FINAL_OUTCOME", "agent-runtime stream ended without FINAL_OUTCOME", false);
                throw new IllegalStateException("agent-runtime stream ended without FINAL_OUTCOME");
            }
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

        private AgentTurnExecutionOutcome readFinalOutcome(AgentTurnStreamFrame frame) throws IOException {
            Object outcome = frame.payload().get("outcome");
            if (outcome == null) {
                throw new IllegalStateException("FINAL_OUTCOME frame missing payload.outcome");
            }
            return objectMapper.readValue(objectMapper.writeValueAsString(outcome), AgentTurnExecutionOutcome.class);
        }

        private void relayRuntimeFrame(AgentTurnStreamFrame frame, StreamReadContext context) throws IOException, InterruptedException {
            try {
                relayFrame(frame);
            } catch (IOException | RuntimeException error) {
                if (frame.kind() != AgentTurnStreamFrameKind.ERROR) {
                    relaySyntheticError(
                        context,
                        frame.seq() + 1,
                        "STREAM_RELAY_FAILED",
                        "session stream frame relay failed",
                        false
                    );
                }
                throw new StreamRelayFailureException("session stream frame relay failed", error);
            }
        }

        private void relayFrame(AgentTurnStreamFrame frame) throws IOException, InterruptedException {
            String requestBody = objectMapper.writeValueAsString(frame);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/api/internal/session-runtime/stream-frames"))
                .header("Content-Type", "application/json")
                .header("Authorization", authorizationHeaderValue)
                .timeout(streamIdleTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
            HttpResponse<String> response;
            try {
                response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException error) {
                streamStallCounter.increment();
                log.error(
                    "session stream frame relay timed out provider=api worker=temporal sessionId={} turnId={} turnExecutionId={} streamSeq={} frameKind={} visibility={} idleTimeoutMs={}",
                    frame.sessionId(),
                    frame.turnId(),
                    frame.turnExecutionId(),
                    frame.seq(),
                    frame.kind(),
                    frame.visibility(),
                    streamIdleTimeout.toMillis()
                );
                throw error;
            }
            if (response.statusCode() >= 400) {
                log.error(
                    "session stream frame relay failed provider=api worker=temporal sessionId={} turnId={} turnExecutionId={} streamSeq={} frameKind={} visibility={} frameId={} status={} body={}",
                    frame.sessionId(),
                    frame.turnId(),
                    frame.turnExecutionId(),
                    frame.seq(),
                    frame.kind(),
                    frame.visibility(),
                    frame.frameId(),
                    response.statusCode(),
                    response.body()
                );
                throw new IllegalStateException("session stream frame relay failed: " + response.statusCode() + " " + response.body());
            }
        }

        private void relaySyntheticError(
            StreamReadContext context,
            long seq,
            String code,
            String message,
            boolean retryable
        ) {
            long errorSeq = Math.max(1, seq);
            AgentTurnStreamFrame frame = new AgentTurnStreamFrame(
                AgentTurnStreamFrame.PROTOCOL,
                context.turnExecutionId() + ":" + errorSeq,
                "worker:" + context.turnExecutionId(),
                context.sessionId(),
                context.turnId(),
                context.turnExecutionId(),
                context.ownerAgentId(),
                context.ownershipEpoch(),
                errorSeq,
                AgentTurnStreamFrameKind.ERROR,
                StreamVisibility.OPERATOR,
                Instant.now(),
                Map.of(
                    "code",
                    code,
                    "message",
                    message,
                    "retryable",
                    retryable,
                    "details",
                    Map.of("source", "worker", "stage", "agent-runtime-stream")
                )
            );
            try {
                relayFrame(frame);
            } catch (Exception error) {
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

        private StreamProtocolFailureException streamProtocolFailure(
            StreamReadContext context,
            long seq,
            String code,
            String message,
            Exception cause
        ) {
            relaySyntheticError(context, seq, code, message, false);
            return new StreamProtocolFailureException(message, cause);
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
            String ownerAgentId,
            long ownershipEpoch
        ) {
            private static StreamReadContext from(AgentTurnRequest request) {
                String ownerAgentId = request.currentOwner() == null ? "" : request.currentOwner().agentId();
                return new StreamReadContext(
                    safeString(request.sessionId()),
                    safeString(request.turnId()),
                    safeString(request.turnExecutionId()),
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

        private static final class StreamRelayFailureException extends IOException {
            private StreamRelayFailureException(String message, Exception cause) {
                super(message, cause);
            }
        }
    }
}
