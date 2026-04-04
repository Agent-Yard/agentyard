package com.lynxus.worker.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.worker.runtime.AgentRuntimeGateway;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class AssistantRunActivitiesImplTest {
    private final AssistantRunActivitiesImpl activities = new AssistantRunActivitiesImpl(new AgentRuntimeGateway() {
        @Override
        public WorkflowContracts.WorkflowResult start(WorkflowContracts.WorkflowStartRequest request) {
            assertEquals(request.logContext().traceId(), MDC.get("traceId"));
            assertEquals(request.logContext().workflowId(), MDC.get("workflowId"));
            assertEquals(request.logContext().customerId(), MDC.get("customerId"));
            assertEquals(request.logContext().userId(), MDC.get("userId"));
            boolean waitingHuman = request.question().contains("投诉");
            return waitingHuman ? waitingHumanResult(request.workflowInstanceId(), request.question()) : completedResult(request.workflowInstanceId(), request.question());
        }

        @Override
        public WorkflowContracts.WorkflowResult resume(WorkflowContracts.WorkflowResumeRequest request) {
            assertEquals(request.logContext().traceId(), MDC.get("traceId"));
            assertEquals(request.logContext().workflowId(), MDC.get("workflowId"));
            assertEquals(request.logContext().customerId(), MDC.get("customerId"));
            assertEquals(request.logContext().userId(), MDC.get("userId"));
            return new WorkflowContracts.WorkflowResult(
                request.workflowInstanceId(),
                WorkflowContracts.WorkflowStatus.COMPLETED,
                "人工处理已完成",
                "end",
                null,
                null,
                null,
                null,
                List.of(
                    new WorkflowContracts.NodeSnapshot("human-review", "人工介入", WorkflowContracts.NodeStatus.COMPLETED, request.action().comment(), Instant.now()),
                    new WorkflowContracts.NodeSnapshot("end", "结束", WorkflowContracts.NodeStatus.COMPLETED, "流程结束", Instant.now())
                ),
                List.of(),
                false,
                new WorkflowContracts.ToolOutcomeSummary(
                    "call-tool-1",
                    "resource-tool-ticket",
                    "工单协同 Tool",
                    WorkflowContracts.ToolKind.RESOURCE,
                    "create_ticket",
                    "MCP",
                    "resource-tool-ticket",
                    "工单协同 Tool",
                    Map.of(
                        "ticketId", "TICKET-1",
                        "status", "ACCEPTED",
                        "message", "test"
                    )
                ),
                List.of(),
                List.of(),
                List.of("resource-version-skill-handoff-v1"),
                WorkflowContracts.SharedSessionState.empty(),
                WorkflowContracts.AgentTurnState.empty()
            );
        }
    });

    @Test
    void shouldCreateHumanCheckpointForComplaint() {
        var result = activities.startExecution(sampleRequest("这是投诉，需要人工处理"));

        assertEquals(WorkflowContracts.WorkflowStatus.WAITING_RESUME, result.status());
        assertTrue(result.checkpoint() != null);
        assertEquals("人工介入待办", result.resumeTask().title());
    }

    @Test
    void shouldResumeExecutionAfterResumeAction() {
        var result = activities.resumeExecution(new WorkflowContracts.WorkflowResumeRequest(
            "task-2",
            "wf-2",
            "scenario-customer-ops",
            new WorkflowContracts.ResumeAction(
                WorkflowContracts.ResumeActionType.CONTINUE,
                WorkflowContracts.ResumeSource.HUMAN,
                "人工已处理",
                "user-1",
                java.util.Map.of()
            ),
            sampleSessionContext("客户投诉"),
            sampleAssistantSnapshot(),
            new WorkflowContracts.ExecutionCheckpoint("cp-1", "handoff-close", "human-review", "{}", null, 0),
            sampleLogContext("session-1", "wf-2")
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
            sampleAssistantSnapshot(),
            sampleLogContext("session-1", "wf-1")
        );
    }

    private WorkflowContracts.LogContext sampleLogContext(String sessionId, String workflowId) {
        return new WorkflowContracts.LogContext(
            "0123456789abcdef0123456789abcdef",
            sessionId,
            workflowId,
            "customer-1",
            "user-1"
        );
    }

    private static WorkflowContracts.WorkflowResult waitingHumanResult(String workflowId, String question) {
        return new WorkflowContracts.WorkflowResult(
            workflowId,
            WorkflowContracts.WorkflowStatus.WAITING_RESUME,
            "等待人工处理",
            "human-review",
            new WorkflowContracts.ExecutionCheckpoint("cp-1", "handoff-close", "human-review", "{\"question\":\"" + question + "\"}", null, 0),
            new WorkflowContracts.ResumeTaskSnapshot(
                "human-review",
                "人工介入待办",
                "请人工确认并补充处理意见。",
                "补充处理意见并确认后续动作",
                WorkflowContracts.PauseSource.GRAPH_NODE,
                List.of(WorkflowContracts.ResumeActionType.CONTINUE, WorkflowContracts.ResumeActionType.TERMINATE)
            ),
            new WorkflowContracts.PauseReasonSnapshot("GRAPH_HUMAN_NODE", "请人工确认并补充处理意见。", WorkflowContracts.PauseSource.GRAPH_NODE),
            null,
            List.of(
                new WorkflowContracts.NodeSnapshot("start", "开始", WorkflowContracts.NodeStatus.COMPLETED, question, Instant.now()),
                new WorkflowContracts.NodeSnapshot("human-review", "人工介入", WorkflowContracts.NodeStatus.WAITING_RESUME, "等待人工处理", Instant.now())
            ),
            List.of(),
            true,
            new WorkflowContracts.ToolOutcomeSummary(
                "call-tool-2",
                "resource-tool-ticket",
                "工单协同 Tool",
                WorkflowContracts.ToolKind.RESOURCE,
                "create_ticket",
                "MCP",
                "resource-tool-ticket",
                "工单协同 Tool",
                Map.of(
                    "ticketId", "TICKET-1",
                    "status", "ACCEPTED",
                    "message", "test"
                )
            ),
            List.of(),
            List.of(),
            List.of("resource-version-skill-handoff-v1"),
            WorkflowContracts.SharedSessionState.empty(),
            WorkflowContracts.AgentTurnState.empty()
        );
    }

    private static WorkflowContracts.WorkflowResult completedResult(String workflowId, String question) {
        return new WorkflowContracts.WorkflowResult(
            workflowId,
            WorkflowContracts.WorkflowStatus.COMPLETED,
            "问题已自动处理完成。",
            "end",
            null,
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
            List.of(),
            List.of(),
            List.of(),
            WorkflowContracts.SharedSessionState.empty(),
            WorkflowContracts.AgentTurnState.empty()
        );
    }

    private WorkflowContracts.SessionContext sampleSessionContext(String latestMessage) {
        WorkflowContracts.SessionMessageSnapshot message = new WorkflowContracts.SessionMessageSnapshot(
            "USER",
            "tester",
            WorkflowContracts.ConversationPayloadType.TEXT,
            Map.of("text", latestMessage),
            latestMessage,
            Instant.now()
        );
        return new WorkflowContracts.SessionContext(
            "session-1",
            "tester",
            message,
            List.of(message),
            List.of(),
            WorkflowContracts.SharedSessionState.empty()
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
                8
            ),
            new WorkflowContracts.KnowledgeBindingSnapshot(
                "knowledge-base-support",
                "客服知识库",
                "knowledge-release-support-v1",
                "1.0.0",
                "snapshot-kb-support-v1",
                5,
                "HYBRID",
                0.1
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
                        true,
                        null,
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
