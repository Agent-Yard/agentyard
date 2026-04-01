package com.lynxus.worker.runtime;

import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResumeRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import com.lynxus.worker.logging.WorkerLogContext;
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

public interface AgentRuntimeGateway {
    WorkflowResult start(WorkflowStartRequest request);

    WorkflowResult resume(WorkflowResumeRequest request);

    @Component
    class HttpAgentRuntimeGateway implements AgentRuntimeGateway {
        private static final Logger log = LoggerFactory.getLogger(HttpAgentRuntimeGateway.class);
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final ObjectMapper objectMapper;
        private final String agentRuntimeBaseUrl;
        private final String authorizationHeaderValue;

        public HttpAgentRuntimeGateway(
            @Value("${lynxus.agent-runtime.base-url}") String agentRuntimeBaseUrl,
            @Value("${lynxus.internal-auth.token}") String internalAuthToken,
            ObjectMapper objectMapper
        ) {
            this.agentRuntimeBaseUrl = agentRuntimeBaseUrl;
            this.authorizationHeaderValue = "Bearer " + requireInternalAuthToken(internalAuthToken);
            this.objectMapper = configureObjectMapper(objectMapper);
        }

        static ObjectMapper createObjectMapper() {
            return configureObjectMapper(new ObjectMapper());
        }

        private static ObjectMapper configureObjectMapper(ObjectMapper objectMapper) {
            return objectMapper.rebuild()
                .findAndAddModules()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        }

        @Override
        public WorkflowResult start(WorkflowStartRequest request) {
            return post("/agent-runs/start", request, request.logContext());
        }

        @Override
        public WorkflowResult resume(WorkflowResumeRequest request) {
            return post("/agent-runs/resume", request, request.logContext());
        }

        private WorkflowResult post(String path, Object payload, com.lynxus.contracts.runtime.WorkflowContracts.LogContext logContext) {
            try {
                String requestBody = objectMapper.writeValueAsString(payload);
                log.info("agent-runtime request path={} workflowId={} sessionId={}", path, logContext == null ? null : logContext.workflowId(), logContext == null ? null : logContext.sessionId());
                HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(agentRuntimeBaseUrl + path))
                    .header("Content-Type", "application/json")
                    .header("Authorization", authorizationHeaderValue)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));
                WorkerLogContext.outboundHeaders(logContext).forEach(httpRequestBuilder::header);
                HttpRequest httpRequest = httpRequestBuilder.build();
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    log.error("agent-runtime request failed path={} status={} body={}", path, response.statusCode(), response.body());
                    throw new IllegalStateException("agent-runtime request failed: " + response.statusCode() + " " + response.body());
                }
                return objectMapper.readValue(response.body(), WorkflowResult.class);
            } catch (IOException | InterruptedException error) {
                throw new IllegalStateException("failed to invoke agent-runtime", error);
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
