package com.agentyard.worker.runtime;

import com.agentyard.contracts.agent.AgentContracts.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The control plane owns execution state; Temporal owns only durable execution and waits. */
public interface ExecutionControlPlane {
    void completeActivation(String activationId, ActivationOutcome outcome);
    ToolRunRuntime toolRun(String toolRunId);
    ToolRunRuntime authorizeNode(String toolRunId, String nodeKey, String executionId);
    void progress(String toolRunId, ToolRunProgress progress);
    void acknowledgeSignal(String toolRunId, String signalId);

    @Component
    class HttpExecutionControlPlane implements ExecutionControlPlane {
        private final WorkerHttpClient http;
        private final String baseUrl;

        public HttpExecutionControlPlane(WorkerHttpClient http,
            @Value("${agentyard.api.base-url:http://127.0.0.1:8080/api}") String baseUrl) {
            this.http = http;
            this.baseUrl = baseUrl;
        }

        public void completeActivation(String id, ActivationOutcome outcome) {
            http.post(baseUrl, "/internal/activations/" + id + "/complete", outcome, Void.class);
        }

        public ToolRunRuntime toolRun(String id) {
            return http.get(baseUrl, "/internal/tool-runs/" + id + "/runtime", ToolRunRuntime.class);
        }

        public ToolRunRuntime authorizeNode(String id, String nodeKey, String executionId) {
            return http.post(baseUrl, "/internal/tool-runs/" + id + "/authorize-node",
                Map.of("nodeKey", nodeKey, "executionId", executionId), ToolRunRuntime.class);
        }

        public void progress(String id, ToolRunProgress progress) {
            http.post(baseUrl, "/internal/tool-runs/" + id + "/progress", progress, Void.class);
        }

        public void acknowledgeSignal(String id, String signalId) {
            http.post(baseUrl, "/internal/tool-runs/" + id + "/signals/" + signalId + "/ack", Map.of(), Void.class);
        }
    }
}
