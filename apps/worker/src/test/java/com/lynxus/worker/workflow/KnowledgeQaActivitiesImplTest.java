package com.lynxus.worker.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.worker.runtime.AgentRuntimeGateway;
import org.junit.jupiter.api.Test;

class KnowledgeQaActivitiesImplTest {
    private final KnowledgeQaActivitiesImpl activities = new KnowledgeQaActivitiesImpl(new AgentRuntimeGateway() {
        @Override
        public WorkflowContracts.WorkflowResult run(WorkflowContracts.WorkflowStartRequest request) {
            boolean waitingHuman = request.question().contains("投诉");
            return new WorkflowContracts.WorkflowResult(
                request.workflowInstanceId(),
                waitingHuman ? WorkflowContracts.WorkflowStatus.WAITING_HUMAN : WorkflowContracts.WorkflowStatus.COMPLETED,
                waitingHuman ? "需要人工处理" : "已自动处理",
                java.util.List.of(
                    new WorkflowContracts.NodeSnapshot("question-received", "问题接收", WorkflowContracts.NodeStatus.COMPLETED, request.question(), java.time.Instant.now()),
                    new WorkflowContracts.NodeSnapshot("knowledge-retrieval", "知识检索", WorkflowContracts.NodeStatus.COMPLETED, "命中 FAQ", java.time.Instant.now()),
                    new WorkflowContracts.NodeSnapshot("answer-generation", "回答生成", WorkflowContracts.NodeStatus.COMPLETED, "已生成回答", java.time.Instant.now()),
                    new WorkflowContracts.NodeSnapshot("mcp-ticketing", "MCP 协同调用", WorkflowContracts.NodeStatus.COMPLETED, "创建工单", java.time.Instant.now()),
                    new WorkflowContracts.NodeSnapshot("escalation-decision", "升级判定", waitingHuman ? WorkflowContracts.NodeStatus.WAITING_HUMAN : WorkflowContracts.NodeStatus.COMPLETED, waitingHuman ? "等待人工接管" : "流程结束", java.time.Instant.now())
                ),
                waitingHuman,
                new WorkflowContracts.McpInvocationSummary("创建协同工单", "TICKET-1", waitingHuman ? "ACCEPTED" : "RECORDED", waitingHuman ? "HUMAN_HANDOFF" : "AUTO_CLOSE", "test")
            );
        }
    });

    @Test
    void shouldCreateHumanHandoffTicketForComplaint() {
        var result = activities.executeAgentRuntime(new WorkflowContracts.WorkflowStartRequest(
            "task-1",
            "wf-1",
            "scenario-knowledge-escalation",
            "assistant-knowledge-escalation",
            "问答升级助手",
            "0.1.0",
            java.util.List.of("客服知识库@1.0.0"),
            "这是投诉，需要人工处理",
            "tester",
            "tester",
            "{}",
            "{}",
            "{}"
        ));

        assertEquals("HUMAN_HANDOFF", result.mcpSummary().recommendedAction());
        assertTrue(result.mcpSummary().externalTicketId().startsWith("TICKET-"));
    }

    @Test
    void shouldAutoCloseForSimpleFaq() {
        var result = activities.executeAgentRuntime(new WorkflowContracts.WorkflowStartRequest(
            "task-2",
            "wf-2",
            "scenario-knowledge-escalation",
            "assistant-knowledge-escalation",
            "问答升级助手",
            "0.1.0",
            java.util.List.of("客服知识库@1.0.0"),
            "怎么重置密码",
            "tester",
            "tester",
            "{}",
            "{}",
            "{}"
        ));

        assertEquals("AUTO_CLOSE", result.mcpSummary().recommendedAction());
        assertFalse(result.mcpSummary().detail().isBlank());
    }
}
