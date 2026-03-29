package com.lynxus.worker.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResumeRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface AgentRuntimeGateway {
    WorkflowResult start(WorkflowStartRequest request);

    WorkflowResult resume(WorkflowResumeRequest request);

    @Component
    class HttpAgentRuntimeGateway implements AgentRuntimeGateway {
        private static final Logger log = LoggerFactory.getLogger(HttpAgentRuntimeGateway.class);
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final ObjectMapper objectMapper = createObjectMapper();
        private final String agentRuntimeBaseUrl;

        public HttpAgentRuntimeGateway(@Value("${lynxus.agent-runtime.base-url}") String agentRuntimeBaseUrl) {
            this.agentRuntimeBaseUrl = agentRuntimeBaseUrl;
        }

        static ObjectMapper createObjectMapper() {
            return new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }

        @Override
        public WorkflowResult start(WorkflowStartRequest request) {
            return post("/agent-runs/start", request);
        }

        @Override
        public WorkflowResult resume(WorkflowResumeRequest request) {
            return post("/agent-runs/resume", request);
        }

        private WorkflowResult post(String path, Object payload) {
            try {
                String requestBody = objectMapper.writeValueAsString(payload);
                log.info("agent-runtime request path={} payload={}", path, requestBody);
                HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(agentRuntimeBaseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
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
    }
}
