package com.lynxus.worker.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.lynxus.contracts.runtime.AssistantRunWorkflow;
import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
import com.lynxus.worker.runtime.AgentRuntimeGateway;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssistantRunWorkflowTest {
    private TestWorkflowEnvironment environment;

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        var worker = environment.newWorker("test-assistant-run");
        worker.registerWorkflowImplementationTypes(AssistantRunWorkflowImpl.class);
        worker.registerActivitiesImplementations(new AssistantRunActivitiesImpl(new AgentRuntimeGateway() {
            @Override
            public WorkflowContracts.WorkflowResult start(WorkflowContracts.WorkflowStartRequest request) {
                if (request.question().contains("失败")) {
                    throw new IllegalStateException("simulated runtime failure");
                }
                boolean waitingHuman = request.question().contains("投诉");
                return waitingHuman
                    ? new WorkflowContracts.WorkflowResult(
                        request.workflowInstanceId(),
                        WorkflowContracts.WorkflowStatus.WAITING_RESUME,
                        "等待人工处理",
                        "已进入人工协同流程。",
                        "human-review",
                        new WorkflowContracts.ExecutionCheckpoint("cp-1", "handoff-close", "human-review", "{}", null, 0),
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
                        List.of(new WorkflowContracts.NodeSnapshot("human-review", "人工介入", WorkflowContracts.NodeStatus.WAITING_RESUME, "等待人工", Instant.now())),
                        List.of(),
                        true,
                        new WorkflowContracts.ToolOutcomeSummary(
                            "resource-tool-ticket",
                            "工单协同 Tool",
                            "create_ticket",
                            "MCP",
                            Map.of(
                                "ticketId", "TICKET-10001",
                                "status", "ACCEPTED",
                                "message", "stub"
                            )
                        ),
                        List.of(),
                        List.of("resource-version-skill-handoff-v1"),
                        WorkflowContracts.SharedSessionState.empty(),
                        WorkflowContracts.AgentTurnState.empty()
                    )
                    : new WorkflowContracts.WorkflowResult(
                        request.workflowInstanceId(),
                        WorkflowContracts.WorkflowStatus.COMPLETED,
                        "问题已自动处理完成。",
                        "请通过登录页的忘记密码完成密码重置。",
                        "end",
                        null,
                        null,
                        null,
                        null,
                        List.of(new WorkflowContracts.NodeSnapshot("end", "结束", WorkflowContracts.NodeStatus.COMPLETED, "流程结束", Instant.now())),
                        List.of(),
                        false,
                        null,
                        List.of(),
                        List.of(),
                        WorkflowContracts.SharedSessionState.empty(),
                        WorkflowContracts.AgentTurnState.empty()
                    );
            }

            @Override
            public WorkflowContracts.WorkflowResult resume(WorkflowContracts.WorkflowResumeRequest request) {
                assertNotNull(request.checkpoint());
                assertEquals("handoff-close", request.checkpoint().currentNodeKey());
                assertEquals("human-review", request.checkpoint().waitingNodeKey());
                return new WorkflowContracts.WorkflowResult(
                    request.workflowInstanceId(),
                    WorkflowContracts.WorkflowStatus.COMPLETED,
                    "人工处理已完成",
                    "人工处理已完成，已同步客户。",
                    "end",
                    null,
                    null,
                    null,
                    null,
                    List.of(new WorkflowContracts.NodeSnapshot("end", "结束", WorkflowContracts.NodeStatus.COMPLETED, "流程结束", Instant.now())),
                    List.of(),
                    false,
                    new WorkflowContracts.ToolOutcomeSummary(
                        "resource-tool-ticket",
                        "工单协同 Tool",
                        "create_ticket",
                        "MCP",
                        Map.of(
                            "ticketId", "TICKET-10001",
                            "status", "ACCEPTED",
                            "message", "stub"
                        )
                    ),
                    List.of(),
                    List.of("resource-version-skill-handoff-v1"),
                    WorkflowContracts.SharedSessionState.empty(),
                    WorkflowContracts.AgentTurnState.empty()
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
        AssistantRunWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
            AssistantRunWorkflow.class,
            io.temporal.client.WorkflowOptions.newBuilder().setTaskQueue("test-assistant-run").build()
        );

        var result = workflow.run(sampleRequest("怎么重置密码", "wf-1"));

        assertEquals(WorkflowStatus.COMPLETED, result.status());
        assertEquals("end", result.currentNodeKey());
    }

    @Test
    void shouldWaitAndResumeForResumeAction() throws Exception {
        AssistantRunWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
            AssistantRunWorkflow.class,
            io.temporal.client.WorkflowOptions.newBuilder().setTaskQueue("test-assistant-run").setWorkflowId("wf-2").build()
        );

        WorkflowClient.start(workflow::run, sampleRequest("这是一个客户投诉，需要人工处理", "wf-2"));
        WorkflowContracts.WorkflowResult waiting = waitForResult(workflow, result -> result.status() == WorkflowStatus.WAITING_RESUME);
        assertEquals(WorkflowStatus.WAITING_RESUME, waiting.status());
        assertNotNull(waiting.resumeTask());

        workflow.submitResumeAction(
            new WorkflowContracts.ResumeAction(
                WorkflowContracts.ResumeActionType.CONTINUE,
                WorkflowContracts.ResumeSource.HUMAN,
                "人工已确认处理",
                "tester",
                java.util.Map.of()
            )
        );
        WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
        WorkflowContracts.WorkflowResult finalResult = untyped.getResult(WorkflowContracts.WorkflowResult.class);
        assertEquals(WorkflowStatus.COMPLETED, finalResult.status());
    }

    @Test
    void shouldExposeFailedResultWhenActivityThrows() throws Exception {
        AssistantRunWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
            AssistantRunWorkflow.class,
            io.temporal.client.WorkflowOptions.newBuilder().setTaskQueue("test-assistant-run").setWorkflowId("wf-failed").build()
        );

        WorkflowClient.start(workflow::run, sampleRequest("触发失败", "wf-failed"));
        WorkflowContracts.WorkflowResult failed = waitForResult(workflow, result -> result.status() == WorkflowStatus.FAILED);

        assertEquals(WorkflowStatus.FAILED, failed.status());
        assertNotNull(failed.summary());
        assertNotNull(failed.latestFailure());
        assertEquals("WORKFLOW_ACTIVITY_FAILURE", failed.latestFailure().code());
    }

    @Test
    void shouldExposeRunningResultBeforeLongActivityCompletes() throws Exception {
        environment.close();
        environment = TestWorkflowEnvironment.newInstance();
        var worker = environment.newWorker("test-assistant-run");
        worker.registerWorkflowImplementationTypes(AssistantRunWorkflowImpl.class);
        worker.registerActivitiesImplementations(new AssistantRunActivitiesImpl(new AgentRuntimeGateway() {
            @Override
            public WorkflowContracts.WorkflowResult start(WorkflowContracts.WorkflowStartRequest request) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                return new WorkflowContracts.WorkflowResult(
                    request.workflowInstanceId(),
                    WorkflowContracts.WorkflowStatus.COMPLETED,
                    "问题已自动处理完成。",
                    "请通过登录页的忘记密码完成密码重置。",
                    "end",
                    null,
                    null,
                    null,
                    null,
                    List.of(new WorkflowContracts.NodeSnapshot("end", "结束", WorkflowContracts.NodeStatus.COMPLETED, "流程结束", Instant.now())),
                    List.of(),
                    false,
                    null,
                    List.of(),
                    List.of(),
                    WorkflowContracts.SharedSessionState.empty(),
                    WorkflowContracts.AgentTurnState.empty()
                );
            }

            @Override
            public WorkflowContracts.WorkflowResult resume(WorkflowContracts.WorkflowResumeRequest request) {
                throw new UnsupportedOperationException();
            }
        }));
        environment.start();

        AssistantRunWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
            AssistantRunWorkflow.class,
            io.temporal.client.WorkflowOptions.newBuilder().setTaskQueue("test-assistant-run").setWorkflowId("wf-running").build()
        );

        WorkflowClient.start(workflow::run, sampleRequest("怎么重置密码", "wf-running"));
        WorkflowContracts.WorkflowResult running = waitForResult(workflow, result -> result.status() == WorkflowStatus.RUNNING);

        assertEquals(WorkflowStatus.RUNNING, running.status());
        assertEquals("workflow-starting", running.currentNodeKey());
    }

    private WorkflowContracts.WorkflowResult waitForResult(
        AssistantRunWorkflow workflow,
        java.util.function.Predicate<WorkflowContracts.WorkflowResult> matcher
    ) throws InterruptedException {
        WorkflowContracts.WorkflowResult result = null;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                result = workflow.currentResult();
            } catch (RuntimeException ignored) {
                result = null;
            }
            if (result != null && matcher.test(result)) {
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
                List.of(new WorkflowContracts.SessionMessageSnapshot("USER", "tester", question, Instant.now())),
                List.of(),
                WorkflowContracts.SharedSessionState.empty()
            ),
            new WorkflowContracts.AssistantRunSnapshot(
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
                    List.of(
                        new WorkflowContracts.GraphNodeSnapshot("start", "开始", WorkflowContracts.OrchestrationNodeType.START, "开始", null, null),
                        new WorkflowContracts.GraphNodeSnapshot(
                            "human-review",
                            "人工介入",
                            WorkflowContracts.OrchestrationNodeType.HUMAN,
                            "人工介入",
                            null,
                            new WorkflowContracts.HumanNodeConfig("人工介入待办", "请人工确认并补充处理意见。", "CONTINUE", "default")
                        ),
                        new WorkflowContracts.GraphNodeSnapshot("end", "结束", WorkflowContracts.OrchestrationNodeType.END, "结束", null, null)
                    ),
                    List.of(
                        new WorkflowContracts.GraphEdgeSnapshot("edge-start-human", "start", "human-review", "default", "开始", true),
                        new WorkflowContracts.GraphEdgeSnapshot("edge-human-end", "human-review", "end", "default", "继续", true)
                    )
                )
            ),
            new WorkflowContracts.LogContext(
                "0123456789abcdef0123456789abcdef",
                "session-" + workflowId,
                workflowId,
                "customer-1",
                "user-1"
            )
        );
    }
}
