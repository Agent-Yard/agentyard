package com.lynxus.worker.runtime;

import com.lynxus.contracts.session.SessionContracts.AgentTurnStreamFrame;
import com.lynxus.contracts.session.SessionContracts.AgentTurnStreamFrameKind;
import com.lynxus.contracts.session.SessionContracts.AgentTurnRequest;
import com.lynxus.contracts.session.SessionContracts.AgentTurnExecutionOutcome;
import com.lynxus.contracts.session.SessionContracts.PlaybookToolTaskRequest;
import com.lynxus.contracts.session.SessionContracts.PlaybookToolTaskResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

        public HttpSessionAgentRuntimeGateway(
            @Value("${lynxus.agent-runtime.base-url}") String agentRuntimeBaseUrl,
            @Value("${lynxus.api.base-url:http://127.0.0.1:8080}") String apiBaseUrl,
            @Value("${lynxus.internal-auth.token}") String internalAuthToken,
            ObjectMapper objectMapper
        ) {
            this.agentRuntimeBaseUrl = agentRuntimeBaseUrl;
            this.apiBaseUrl = apiBaseUrl;
            this.authorizationHeaderValue = "Bearer " + requireInternalAuthToken(internalAuthToken);
            this.objectMapper = objectMapper.rebuild()
                .findAndAddModules()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        }

        @Override
        public AgentTurnExecutionOutcome executeTurn(AgentTurnRequest request) {
            return executeTurnStream(request);
        }

        @Override
        public AgentTurnExecutionOutcome executeTurnStream(AgentTurnRequest request) {
            try {
                String requestBody = objectMapper.writeValueAsString(request);
                HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(agentRuntimeBaseUrl + "/agent-turns/execute-stream"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/x-ndjson")
                    .header("Authorization", authorizationHeaderValue)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
                HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 400) {
                    String body = readBody(response.body());
                    log.error("agent-turn streaming execution failed status={} body={}", response.statusCode(), body);
                    throw new IllegalStateException("agent-turn streaming execution failed: " + response.statusCode() + " " + body);
                }
                return readTurnStream(response.body());
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

        private AgentTurnExecutionOutcome readTurnStream(InputStream body) throws IOException, InterruptedException {
            AgentTurnExecutionOutcome finalOutcome = null;
            int finalOutcomeCount = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    AgentTurnStreamFrame frame = objectMapper.readValue(line, AgentTurnStreamFrame.class);
                    if (frame.kind() == AgentTurnStreamFrameKind.FINAL_OUTCOME) {
                        finalOutcomeCount += 1;
                        if (finalOutcomeCount > 1) {
                            throw new IllegalStateException("agent-runtime stream returned duplicate FINAL_OUTCOME");
                        }
                        finalOutcome = readFinalOutcome(frame);
                    } else {
                        relayFrame(frame);
                    }
                }
            }
            if (finalOutcomeCount == 0 || finalOutcome == null) {
                throw new IllegalStateException("agent-runtime stream ended without FINAL_OUTCOME");
            }
            return finalOutcome;
        }

        private AgentTurnExecutionOutcome readFinalOutcome(AgentTurnStreamFrame frame) throws IOException {
            Object outcome = frame.payload().get("outcome");
            if (outcome == null) {
                throw new IllegalStateException("FINAL_OUTCOME frame missing payload.outcome");
            }
            return objectMapper.readValue(objectMapper.writeValueAsString(outcome), AgentTurnExecutionOutcome.class);
        }

        private void relayFrame(AgentTurnStreamFrame frame) throws IOException, InterruptedException {
            String requestBody = objectMapper.writeValueAsString(frame);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/api/internal/session-runtime/stream-frames"))
                .header("Content-Type", "application/json")
                .header("Authorization", authorizationHeaderValue)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.error(
                    "session stream frame relay failed frameId={} status={} body={}",
                    frame.frameId(),
                    response.statusCode(),
                    response.body()
                );
                throw new IllegalStateException("session stream frame relay failed: " + response.statusCode() + " " + response.body());
            }
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
    }
}
