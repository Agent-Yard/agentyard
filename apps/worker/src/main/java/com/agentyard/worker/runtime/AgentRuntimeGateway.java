package com.agentyard.worker.runtime;

import com.agentyard.contracts.agent.AgentContracts.*;
import com.agentyard.contracts.runtime.RuntimeContracts.PlaybookToolTaskResult;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

public interface AgentRuntimeGateway {
    ActivationOutcome executeActivation(String activationId);
    PlaybookToolTaskResult executeToolNode(ToolRunNodeRequest request);

    @Component
    class HttpAgentRuntimeGateway implements AgentRuntimeGateway {
        private final WorkerHttpClient http;
        private final String baseUrl;

        public HttpAgentRuntimeGateway(WorkerHttpClient http,
            @Value("${agentyard.agent-runtime.base-url}") String baseUrl) {
            this.http = http;
            this.baseUrl = baseUrl;
        }

        public ActivationOutcome executeActivation(String id) {
            return http.post(baseUrl, "/activations/execute", new ActivationStart(id),
                ActivationOutcome.class, Duration.ofMinutes(14));
        }

        public PlaybookToolTaskResult executeToolNode(ToolRunNodeRequest request) {
            return http.post(baseUrl, "/tool-run-nodes/execute", request,
                PlaybookToolTaskResult.class, Duration.ofMinutes(5));
        }
    }
}
