package com.lynxus.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.runtime.WorkflowContracts;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRuntimeGatewayTest {
    @Test
    void shouldSerializeSessionHistoryInstantsAsIsoStrings() throws Exception {
        var payload = new WorkflowContracts.WorkflowStartRequest(
            "task-1",
            "wf-1",
            "scenario-customer-ops",
            "你好",
            "tester",
            new WorkflowContracts.SessionContext(
                "session-1",
                "tester",
                "你好",
                List.of(new WorkflowContracts.SessionMessageSnapshot("USER", "tester", "你好", Instant.parse("2026-03-24T08:35:20Z")))
            ),
            new WorkflowContracts.AssistantRunSnapshot(
                "assistant-customer-ops",
                "客户协同助手",
                "1.0.0",
                new WorkflowContracts.AssistantPolicySnapshot(null, null, null, null, false, null, null, false, 0),
                List.of(),
                List.of(),
                new WorkflowContracts.GraphSnapshot("GRAPH", List.of(), List.of())
            )
        );

        String json = AgentRuntimeGateway.HttpAgentRuntimeGateway.createObjectMapper().writeValueAsString(payload);

        assertTrue(json.contains("\"createdAt\":\"2026-03-24T08:35:20Z\""));
    }
}
