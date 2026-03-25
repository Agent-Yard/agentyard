package com.lynxus.worker.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.worker.runtime.AgentRuntimeGateway;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssistantRunActivitiesImplTest {
    private final AssistantRunActivitiesImpl activities = new AssistantRunActivitiesImpl(new AgentRuntimeGateway() {
        @Override
        public WorkflowContracts.WorkflowResult start(WorkflowContracts.WorkflowStartRequest request) {
            boolean waitingHuman = request.question().contains("投诉");
            return waitingHuman ? waitingHumanResult(request.workflowInstanceId(), request.question()) : completedResult(request.workflowInstanceId(), request.question());
        }

        @Override
        public WorkflowContracts.WorkflowResult resume(WorkflowContracts.WorkflowResumeRequest request) {
            return new WorkflowContracts.WorkflowResult(
                request.workflowInstanceId(),
                WorkflowContracts.WorkflowStatus.COMPLETED,
                "人工处理已完成",
                "人工处理已完成，已同步客户。",
                "end",
                null,
                null,
                null,
                List.of(
                    new WorkflowContracts.NodeSnapshot("human-review", "人工介入", WorkflowContracts.NodeStatus.COMPLETED, request.action().comment(), Instant.now()),
                    new WorkflowContracts.NodeSnapshot("end", "结束", WorkflowContracts.NodeStatus.COMPLETED, "流程结束", Instant.now())
                ),
                List.of(),
                false,
                new WorkflowContracts.ToolOutcomeSummary("resource-tool-ticket", "工单协同 Tool", "create_ticket", "MCP", "ACCEPTED", "TICKET-1", "HUMAN_HANDOFF", "test"),
                List.of("resource-version-skill-handoff-v1")
            );
        }
    });

    @Test
    void shouldCreateHumanCheckpointForComplaint() {
        var result = activities.startExecution(sampleRequest("这是投诉，需要人工处理"));

        assertEquals(WorkflowContracts.WorkflowStatus.WAITING_HUMAN, result.status());
        assertTrue(result.checkpoint() != null);
        assertEquals("人工介入待办", result.humanTask().title());
    }

    @Test
    void shouldResumeExecutionAfterHumanAction() {
        var result = activities.resumeExecution(new WorkflowContracts.WorkflowResumeRequest(
            "task-2",
            "wf-2",
            "scenario-customer-ops",
            new WorkflowContracts.HumanAction("CONFIRM", "人工已处理", "operator-1", java.util.Map.of()),
            sampleSessionContext("客户投诉"),
            sampleAssistantSnapshot(),
            new WorkflowContracts.ExecutionCheckpoint("cp-1", "handoff-close", "human-review", "{}", 0)
        ));

        assertEquals(WorkflowContracts.WorkflowStatus.COMPLETED, result.status());
        assertEquals("end", result.currentNodeKey());
    }

    private WorkflowContracts.WorkflowStartRequest sampleRequest(String question) {
        return new WorkflowContracts.WorkflowStartRequest(
            "task-1",
            "wf-1",
            "scenario-customer-ops",
            question,
            "tester",
            sampleSessionContext(question),
            sampleAssistantSnapshot()
        );
    }

    private static WorkflowContracts.WorkflowResult waitingHumanResult(String workflowId, String question) {
        return new WorkflowContracts.WorkflowResult(
            workflowId,
            WorkflowContracts.WorkflowStatus.WAITING_HUMAN,
            "等待人工处理",
            "已进入人工协同流程。",
            "human-review",
            new WorkflowContracts.ExecutionCheckpoint("cp-1", "handoff-close", "human-review", "{\"question\":\"" + question + "\"}", 0),
            new WorkflowContracts.HumanTaskSnapshot("human-review", "人工介入待办", "请人工确认并补充处理意见。", "补充处理意见并确认后续动作", "GRAPH_NODE", List.of("CONFIRM", "TERMINATE")),
            new WorkflowContracts.PauseReasonSnapshot("GRAPH_HUMAN_NODE", "请人工确认并补充处理意见。", "GRAPH_NODE"),
            List.of(
                new WorkflowContracts.NodeSnapshot("start", "开始", WorkflowContracts.NodeStatus.COMPLETED, question, Instant.now()),
                new WorkflowContracts.NodeSnapshot("human-review", "人工介入", WorkflowContracts.NodeStatus.WAITING_HUMAN, "等待人工处理", Instant.now())
            ),
            List.of(),
            true,
            new WorkflowContracts.ToolOutcomeSummary("resource-tool-ticket", "工单协同 Tool", "create_ticket", "MCP", "ACCEPTED", "TICKET-1", "HUMAN_HANDOFF", "test"),
            List.of("resource-version-skill-handoff-v1")
        );
    }

    private static WorkflowContracts.WorkflowResult completedResult(String workflowId, String question) {
        return new WorkflowContracts.WorkflowResult(
            workflowId,
            WorkflowContracts.WorkflowStatus.COMPLETED,
            "问题已自动处理完成。",
            "请通过登录页的忘记密码完成密码重置。",
            "end",
            null,
            null,
            null,
            List.of(
                new WorkflowContracts.NodeSnapshot("start", "开始", WorkflowContracts.NodeStatus.COMPLETED, question, Instant.now()),
                new WorkflowContracts.NodeSnapshot("end", "结束", WorkflowContracts.NodeStatus.COMPLETED, "流程结束", Instant.now())
            ),
            List.of(),
            false,
            null,
            List.of()
        );
    }

    private WorkflowContracts.SessionContext sampleSessionContext(String latestMessage) {
        return new WorkflowContracts.SessionContext(
            "session-1",
            "tester",
            latestMessage,
            List.of(new WorkflowContracts.SessionMessageSnapshot("USER", "tester", latestMessage, Instant.now())),
            List.of()
        );
    }

    private WorkflowContracts.AssistantRunSnapshot sampleAssistantSnapshot() {
        return new WorkflowContracts.AssistantRunSnapshot(
            "assistant-customer-ops",
            "客服协同助手",
            "1.0.0",
            new WorkflowContracts.AssistantPolicySnapshot(
                "resource-llm-openai",
                "resource-version-llm-v1",
                true,
                "resource-kb-support",
                "resource-version-kb-v1",
                true,
                8
            ),
            List.of(
                new WorkflowContracts.AgentSnapshot(
                    "agent-router",
                    "问题分诊智能体",
                    "router",
                    "决定分支",
                    new WorkflowContracts.AgentExecutionPolicySnapshot(
                        true,
                        null,
                        null,
                        "你是问题分诊智能体",
                        true,
                        "resource-kb-support",
                        "resource-version-kb-v1",
                        8,
                        List.of("resource-skill-router"),
                        List.of("resource-version-skill-router-v1"),
                        List.of(),
                        List.of()
                    )
                )
            ),
            List.of(),
            new WorkflowContracts.GraphSnapshot(
                "GRAPH",
                List.of(new WorkflowContracts.GraphNodeSnapshot("start", "开始", WorkflowContracts.OrchestrationNodeType.START, "开始", null, null)),
                List.of()
            )
        );
    }
}
