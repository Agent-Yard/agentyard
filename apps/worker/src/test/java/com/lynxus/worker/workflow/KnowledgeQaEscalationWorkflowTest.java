package com.lynxus.worker.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.lynxus.contracts.runtime.KnowledgeQaEscalationWorkflow;
import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
import com.lynxus.worker.runtime.AgentRuntimeGateway;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import java.time.Instant;
import java.util.List;
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
            public WorkflowContracts.WorkflowResult start(WorkflowContracts.WorkflowStartRequest request) {
                if (request.question().contains("失败")) {
                    throw new IllegalStateException("simulated runtime failure");
                }
                boolean waitingHuman = request.question().contains("投诉");
                return waitingHuman
                    ? new WorkflowContracts.WorkflowResult(
                        request.workflowInstanceId(),
                        WorkflowContracts.WorkflowStatus.WAITING_HUMAN,
                        "等待人工处理",
                        "已进入人工协同流程。",
                        "human-review",
                        new WorkflowContracts.ExecutionCheckpoint("cp-1", "handoff-close", "human-review", "{}", 0),
                        new WorkflowContracts.HumanTaskSnapshot("human-review", "人工介入待办", "请人工确认并补充处理意见。", "CONFIRM"),
                        List.of(new WorkflowContracts.NodeSnapshot("human-review", "人工介入", WorkflowContracts.NodeStatus.WAITING_HUMAN, "等待人工", Instant.now())),
                        List.of(),
                        true,
                        new WorkflowContracts.McpInvocationSummary("创建协同工单", "TICKET-10001", "ACCEPTED", "HUMAN_HANDOFF", "stub")
                    )
                    : new WorkflowContracts.WorkflowResult(
                        request.workflowInstanceId(),
                        WorkflowContracts.WorkflowStatus.COMPLETED,
                        "问题已自动处理完成。",
                        "请通过登录页的忘记密码完成密码重置。",
                        "end",
                        null,
                        null,
                        List.of(new WorkflowContracts.NodeSnapshot("end", "结束", WorkflowContracts.NodeStatus.COMPLETED, "流程结束", Instant.now())),
                        List.of(),
                        false,
                        null
                    );
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
                    List.of(new WorkflowContracts.NodeSnapshot("end", "结束", WorkflowContracts.NodeStatus.COMPLETED, "流程结束", Instant.now())),
                    List.of(),
                    false,
                    new WorkflowContracts.McpInvocationSummary("创建协同工单", "TICKET-10001", "ACCEPTED", "HUMAN_HANDOFF", "stub")
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
    void shouldCompleteSimpleFaq() {
        KnowledgeQaEscalationWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
            KnowledgeQaEscalationWorkflow.class,
            io.temporal.client.WorkflowOptions.newBuilder().setTaskQueue("test-knowledge-escalation").build()
        );

        var result = workflow.run(sampleRequest("怎么重置密码", "wf-1"));

        assertEquals(WorkflowStatus.COMPLETED, result.status());
        assertEquals("end", result.currentNodeKey());
    }

    @Test
    void shouldWaitAndResumeForHumanAction() throws Exception {
        KnowledgeQaEscalationWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
            KnowledgeQaEscalationWorkflow.class,
            io.temporal.client.WorkflowOptions.newBuilder().setTaskQueue("test-knowledge-escalation").setWorkflowId("wf-2").build()
        );

        WorkflowClient.start(workflow::run, sampleRequest("这是一个客户投诉，需要人工处理", "wf-2"));
        WorkflowContracts.WorkflowResult waiting = waitForResult(workflow);
        assertEquals(WorkflowStatus.WAITING_HUMAN, waiting.status());
        assertNotNull(waiting.humanTask());

        workflow.submitHumanAction(new WorkflowContracts.HumanAction("CONFIRM", "人工已确认处理", "tester", java.util.Map.of()));
        WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
        WorkflowContracts.WorkflowResult finalResult = untyped.getResult(WorkflowContracts.WorkflowResult.class);
        assertEquals(WorkflowStatus.COMPLETED, finalResult.status());
    }

    @Test
    void shouldExposeFailedResultWhenActivityThrows() throws Exception {
        KnowledgeQaEscalationWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
            KnowledgeQaEscalationWorkflow.class,
            io.temporal.client.WorkflowOptions.newBuilder().setTaskQueue("test-knowledge-escalation").setWorkflowId("wf-failed").build()
        );

        WorkflowClient.start(workflow::run, sampleRequest("触发失败", "wf-failed"));
        WorkflowContracts.WorkflowResult failed = waitForResult(workflow);

        assertEquals(WorkflowStatus.FAILED, failed.status());
        assertNotNull(failed.summary());
    }

    private WorkflowContracts.WorkflowResult waitForResult(KnowledgeQaEscalationWorkflow workflow) throws InterruptedException {
        WorkflowContracts.WorkflowResult result = null;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                result = workflow.currentResult();
            } catch (RuntimeException ignored) {
                result = null;
            }
            if (result != null) {
                return result;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("workflow did not expose a result");
    }

    private WorkflowStartRequest sampleRequest(String question, String workflowId) {
        return new WorkflowStartRequest(
            "task-" + workflowId,
            workflowId,
            "scenario-customer-ops",
            question,
            "tester",
            new WorkflowContracts.SessionContext(
                "session-" + workflowId,
                "tester",
                question,
                List.of(new WorkflowContracts.SessionMessageSnapshot("USER", "tester", question, Instant.now()))
            ),
            new WorkflowContracts.AssistantRunSnapshot(
                "assistant-customer-ops",
                "客服协同助手",
                "1.0.0",
                new WorkflowContracts.AssistantPolicySnapshot("resource-llm-openai", "resource-prompt-router", 0.2, 1200, true, "resource-kb-support", 5, true, 8),
                List.of(
                    new WorkflowContracts.AgentSnapshot(
                        "agent-router",
                        "问题分诊智能体",
                        "router",
                        "决定分支",
                        new WorkflowContracts.AgentExecutionPolicySnapshot(true, null, "resource-prompt-router", "", true, "resource-kb-support", 8, List.of()),
                        List.of()
                    )
                ),
                List.of(),
                new WorkflowContracts.GraphSnapshot(
                    "GRAPH",
                    List.of(
                        new WorkflowContracts.GraphNodeSnapshot("start", "开始", WorkflowContracts.OrchestrationNodeType.START, "开始", null, null),
                        new WorkflowContracts.GraphNodeSnapshot(
                            "human-review",
                            "人工介入",
                            WorkflowContracts.OrchestrationNodeType.HUMAN,
                            "人工介入",
                            null,
                            new WorkflowContracts.HumanNodeConfig("人工介入待办", "请人工确认并补充处理意见。", "CONFIRM", "default")
                        ),
                        new WorkflowContracts.GraphNodeSnapshot("end", "结束", WorkflowContracts.OrchestrationNodeType.END, "结束", null, null)
                    ),
                    List.of(
                        new WorkflowContracts.GraphEdgeSnapshot("edge-start-human", "start", "human-review", "default", "开始", true),
                        new WorkflowContracts.GraphEdgeSnapshot("edge-human-end", "human-review", "end", "default", "继续", true)
                    )
                )
            )
        );
    }
}
