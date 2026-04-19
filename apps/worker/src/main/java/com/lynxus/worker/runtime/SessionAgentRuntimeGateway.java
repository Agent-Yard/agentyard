package com.lynxus.worker.runtime;

import com.lynxus.contracts.session.SessionContracts.AgentTurnRequest;
import com.lynxus.contracts.session.SessionContracts.AgentTurnResult;
import com.lynxus.contracts.session.SessionContracts.PlaybookToolTaskRequest;
import com.lynxus.contracts.session.SessionContracts.PlaybookToolTaskResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

public interface SessionAgentRuntimeGateway {
    AgentTurnResult executeTurn(AgentTurnRequest request);

    PlaybookToolTaskResult executePlaybookToolTask(PlaybookToolTaskRequest request);

    @Component
    class HttpSessionAgentRuntimeGateway implements SessionAgentRuntimeGateway {
        private static final Logger log = LoggerFactory.getLogger(HttpSessionAgentRuntimeGateway.class);
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final ObjectMapper objectMapper;
        private final String agentRuntimeBaseUrl;
        private final String authorizationHeaderValue;

        public HttpSessionAgentRuntimeGateway(
            @Value("${lynxus.agent-runtime.base-url}") String agentRuntimeBaseUrl,
            @Value("${lynxus.internal-auth.token}") String internalAuthToken,
            ObjectMapper objectMapper
        ) {
            this.agentRuntimeBaseUrl = agentRuntimeBaseUrl;
            this.authorizationHeaderValue = "Bearer " + requireInternalAuthToken(internalAuthToken);
            this.objectMapper = objectMapper.rebuild()
                .findAndAddModules()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        }

        @Override
        public AgentTurnResult executeTurn(AgentTurnRequest request) {
            return post("/agent-turns/execute", request, AgentTurnResult.class, "agent-turn execution failed");
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

        private static String requireInternalAuthToken(String internalAuthToken) {
            if (internalAuthToken == null || internalAuthToken.isBlank()) {
                throw new IllegalStateException("lynxus.internal-auth.token must be configured");
            }
            return internalAuthToken.trim();
        }
    }
}
