package com.lynxus.worker.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.lynxus.contracts.runtime.KnowledgeQaEscalationWorkflow;
import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
import io.temporal.testing.TestWorkflowEnvironment;
import com.lynxus.worker.runtime.AgentRuntimeGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeQaEscalationWorkflowTest {
    private TestWorkflowEnvironment environment;

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        var worker = environment.newWorker("test-knowledge-escalation");
        worker.registerWorkflowImplementationTypes(KnowledgeQaEscalationWorkflowImpl.class);
        worker.registerActivitiesImplementations(new KnowledgeQaActivitiesImpl(new AgentRuntimeGateway() {
            @Override
            public WorkflowContracts.WorkflowResult run(WorkflowContracts.WorkflowStartRequest request) {
                boolean waitingHuman = request.question().contains("投诉");
                return new WorkflowContracts.WorkflowResult(
                    request.workflowInstanceId(),
                    waitingHuman ? WorkflowContracts.WorkflowStatus.WAITING_HUMAN : WorkflowContracts.WorkflowStatus.COMPLETED,
                    waitingHuman ? "已创建人工协同工单，等待人工处理。" : "问题已自动处理完成。",
                    java.util.List.of(
                        new WorkflowContracts.NodeSnapshot("question-received", "问题接收", WorkflowContracts.NodeStatus.COMPLETED, request.question(), java.time.Instant.now()),
                        new WorkflowContracts.NodeSnapshot("knowledge-retrieval", "知识检索", WorkflowContracts.NodeStatus.COMPLETED, "命中 FAQ", java.time.Instant.now()),
                        new WorkflowContracts.NodeSnapshot("answer-generation", "回答生成", WorkflowContracts.NodeStatus.COMPLETED, "已生成答案", java.time.Instant.now()),
                        new WorkflowContracts.NodeSnapshot("mcp-ticketing", "MCP 协同调用", WorkflowContracts.NodeStatus.COMPLETED, "创建协同工单", java.time.Instant.now()),
                        new WorkflowContracts.NodeSnapshot("escalation-decision", "升级判定", waitingHuman ? WorkflowContracts.NodeStatus.WAITING_HUMAN : WorkflowContracts.NodeStatus.COMPLETED, waitingHuman ? "等待人工接管" : "流程结束", java.time.Instant.now())
                    ),
                    waitingHuman,
                    new WorkflowContracts.McpInvocationSummary("创建协同工单", "TICKET-10001", waitingHuman ? "ACCEPTED" : "RECORDED", waitingHuman ? "HUMAN_HANDOFF" : "AUTO_CLOSE", "stub")
                );
            }
        }));
        environment.start();
    }

    @AfterEach
    void tearDown() {
        environment.close();
    }

    @Test
    void shouldCompleteSimpleFaqWithMcpNode() {
        KnowledgeQaEscalationWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
            KnowledgeQaEscalationWorkflow.class,
            io.temporal.client.WorkflowOptions.newBuilder()
                .setTaskQueue("test-knowledge-escalation")
                .build()
        );

        var result = workflow.run(new WorkflowStartRequest(
            "task-1",
            "wf-1",
            "scenario-knowledge-escalation",
            "assistant-knowledge-escalation",
            "问答升级助手",
            "0.1.0",
            java.util.List.of("客服知识库@1.0.0", "工单系统 MCP@1.0.0"),
            "怎么重置密码",
            "tester",
            "tester",
            "{}",
            "{}",
            "{}"
        ));

        assertEquals(WorkflowStatus.COMPLETED, result.status());
        assertEquals(5, result.nodes().size());
        assertEquals("mcp-ticketing", result.nodes().get(3).nodeKey());
        assertNotNull(result.mcpSummary());
    }

    @Test
    void shouldWaitForHumanWhenComplaintNeedsHandoff() {
        KnowledgeQaEscalationWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
            KnowledgeQaEscalationWorkflow.class,
            io.temporal.client.WorkflowOptions.newBuilder()
                .setTaskQueue("test-knowledge-escalation")
                .build()
        );

        var result = workflow.run(new WorkflowStartRequest(
            "task-2",
            "wf-2",
            "scenario-knowledge-escalation",
            "assistant-knowledge-escalation",
            "问答升级助手",
            "0.1.0",
            java.util.List.of("客服知识库@1.0.0", "工单系统 MCP@1.0.0"),
            "这是一个客户投诉，需要人工处理",
            "tester",
            "tester",
            "{}",
            "{}",
            "{}"
        ));

        assertEquals(WorkflowStatus.WAITING_HUMAN, result.status());
        assertEquals("HUMAN_HANDOFF", result.mcpSummary().recommendedAction());
    }
}
