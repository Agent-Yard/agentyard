package com.lynxus.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                List.of(new WorkflowContracts.SessionMessageSnapshot("USER", "tester", "你好", Instant.parse("2026-03-24T08:35:20Z"))),
                List.of()
            ),
            new WorkflowContracts.AssistantRunSnapshot(
                "assistant-customer-ops",
                "客户协同助手",
                "1.0.0",
                new WorkflowContracts.AssistantPolicySnapshot(null, null, false, 0),
                null,
                List.of(),
                List.of(),
                new WorkflowContracts.GraphSnapshot("GRAPH", List.of(), List.of())
            )
        );

        String json = AgentRuntimeGateway.HttpAgentRuntimeGateway.createObjectMapper().writeValueAsString(payload);

        assertTrue(json.contains("\"createdAt\":\"2026-03-24T08:35:20Z\""));
    }

    @Test
    void shouldDeserializeWorkflowResultWithHumanTaskSource() throws Exception {
        String payload = """
            {
              "workflowInstanceId": "wf-1",
              "status": "WAITING_HUMAN",
              "summary": "等待人工处理",
              "finalReply": "已进入人工协同流程。",
              "currentNodeKey": "human-review",
              "checkpoint": null,
              "humanTask": {
                "nodeKey": "human-review",
                "title": "人工介入待办",
                "instruction": "请人工确认并补充处理意见。",
                "expectedAction": "CONFIRM",
                "source": "AGENT_REQUEST",
                "allowedActions": ["CONFIRM", "TERMINATE"]
              },
              "pauseReason": {
                "code": "HUMAN_HANDOFF_REQUESTED",
                "detail": "请人工确认并补充处理意见。",
                "source": "AGENT_REQUEST"
              },
              "nodes": [],
              "toolCalls": [],
              "escalationRequired": true,
              "latestToolOutcome": null,
              "loadedSkillResourceVersionIds": []
            }
            """;

        WorkflowContracts.WorkflowResult result = AgentRuntimeGateway.HttpAgentRuntimeGateway.createObjectMapper()
            .readValue(payload, WorkflowContracts.WorkflowResult.class);

        assertEquals("AGENT_REQUEST", result.humanTask().source());
    }
}
