package com.lynxus.worker.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

public interface AgentRuntimeGateway {
    WorkflowResult run(WorkflowStartRequest request);

    @Component
    class HttpAgentRuntimeGateway implements AgentRuntimeGateway {
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final ObjectMapper objectMapper;
        private final String agentRuntimeBaseUrl;

        public HttpAgentRuntimeGateway(
            ObjectMapper objectMapper,
            @Value("${lynxus.agent-runtime.base-url}") String agentRuntimeBaseUrl
        ) {
            this.objectMapper = objectMapper;
            this.agentRuntimeBaseUrl = agentRuntimeBaseUrl;
        }

        @Override
        public WorkflowResult run(WorkflowStartRequest request) {
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(agentRuntimeBaseUrl + "/agent-runs"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .build();
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IllegalStateException("agent-runtime request failed: " + response.statusCode() + " " + response.body());
                }
                return objectMapper.readValue(response.body(), WorkflowResult.class);
            } catch (IOException | InterruptedException error) {
                throw new IllegalStateException("failed to invoke agent-runtime", error);
            }
        }
    }
}
