package com.lynxus.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.platform.catalog.CatalogDtos;
import com.lynxus.platform.catalog.CatalogService;
import com.lynxus.platform.catalog.InMemoryCatalogRepository;
import com.lynxus.platform.knowledge.KnowledgeServiceClient;
import com.lynxus.platform.knowledge.KnowledgeWorkflowGateway;
import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.TaskStatus;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class RuntimeServiceTest {
    private final CatalogService catalogService = catalogService();
    private final RuntimeService service = new RuntimeService(new AssistantRunWorkflowGateway() {
        @Override
        public WorkflowContracts.WorkflowResult startAndAwaitFirstResult(WorkflowContracts.WorkflowStartRequest request) {
            boolean waitingHuman = request.question().contains("投诉");
            return new WorkflowContracts.WorkflowResult(
                request.workflowInstanceId(),
                waitingHuman ? WorkflowContracts.WorkflowStatus.WAITING_HUMAN : WorkflowContracts.WorkflowStatus.COMPLETED,
                waitingHuman ? "已创建人工协同工单，等待人工处理。" : "问题已自动处理完成。",
                waitingHuman ? "已进入人工协同流程。" : "请通过登录页的忘记密码完成密码重置。",
                waitingHuman ? "human-review" : "end",
                waitingHuman ? new WorkflowContracts.ExecutionCheckpoint("cp-1", "handoff-close", "human-review", "{}", 0) : null,
                waitingHuman ? new WorkflowContracts.HumanTaskSnapshot("human-review", "人工介入待办", "请人工处理。", "补充处理意见并确认后续动作", "GRAPH_NODE", List.of("CONFIRM", "TERMINATE")) : null,
                waitingHuman ? new WorkflowContracts.PauseReasonSnapshot("GRAPH_HUMAN_NODE", "请人工处理。", "GRAPH_NODE") : null,
                List.of(
                    new WorkflowContracts.NodeSnapshot("start", "开始", WorkflowContracts.NodeStatus.COMPLETED, request.question(), Instant.now()),
                    new WorkflowContracts.NodeSnapshot(waitingHuman ? "human-review" : "end", waitingHuman ? "人工介入" : "结束", waitingHuman ? WorkflowContracts.NodeStatus.WAITING_HUMAN : WorkflowContracts.NodeStatus.COMPLETED, waitingHuman ? "等待人工接管" : "流程结束", Instant.now())
                ),
                List.of(),
                waitingHuman,
                new WorkflowContracts.ToolOutcomeSummary(
                    "resource-tool-ticket",
                    "工单协同 Tool",
                    "create_ticket",
                    "MCP",
                    waitingHuman ? "ACCEPTED" : "RECORDED",
                    "TICKET-10001",
                    waitingHuman ? "HUMAN_HANDOFF" : "AUTO_CLOSE",
                    waitingHuman ? "需要人工介入" : "无需人工介入"
                ),
                List.of()
            );
        }

        @Override
        public WorkflowContracts.WorkflowResult submitHumanActionAndAwaitResult(String workflowId, WorkflowContracts.HumanAction action) {
            return new WorkflowContracts.WorkflowResult(
                workflowId,
                WorkflowContracts.WorkflowStatus.COMPLETED,
                "人工处理已完成。",
                "人工处理已完成，已同步客户。",
                "end",
                null,
                null,
                null,
                List.of(new WorkflowContracts.NodeSnapshot("end", "结束", WorkflowContracts.NodeStatus.COMPLETED, action.comment(), Instant.now())),
                List.of(),
                false,
                new WorkflowContracts.ToolOutcomeSummary("resource-tool-ticket", "工单协同 Tool", "create_ticket", "MCP", "ACCEPTED", "TICKET-10001", "HUMAN_HANDOFF", "已同步工单"),
                List.of()
            );
        }

        @Override
        public WorkflowContracts.WorkflowResult currentResult(String workflowId) {
            return null;
        }
    }, catalogService);

    @Test
    void shouldRouteComplaintToHumanIntervention() {
        RuntimeDtos.TaskInstanceDto task = service.launchTask(
            new RuntimeDtos.TaskLaunchRequest("scenario-customer-ops", "assistant-customer-ops", "客户投诉，需要人工处理", "tester")
        );

        assertEquals(TaskStatus.WAITING_HUMAN, task.status());
        assertFalse(task.assistantReleaseVersion().isBlank());
        assertNotNull(service.getWorkflow(task.workflowInstanceId()).latestToolOutcome());
        assertNotNull(service.getWorkflow(task.workflowInstanceId()).checkpoint());
    }

    @Test
    void shouldCompleteSimpleFaqAndExposeMcpSummary() {
        RuntimeDtos.TaskInstanceDto task = service.launchTask(
            new RuntimeDtos.TaskLaunchRequest("scenario-customer-ops", "assistant-customer-ops", "怎么重置密码", "tester")
        );

        assertEquals(TaskStatus.COMPLETED, task.status());
        assertFalse(service.getWorkflow(task.workflowInstanceId()).resourceAnchors().isEmpty());
        assertNotNull(service.getWorkflow(task.workflowInstanceId()).latestToolOutcome());
    }

    @Test
    void shouldSeedSessionsWithoutLaunchingWorkflowsByDefault() {
        RuntimeService seededService = new RuntimeService(new AssistantRunWorkflowGateway() {
            @Override
            public WorkflowContracts.WorkflowResult startAndAwaitFirstResult(WorkflowContracts.WorkflowStartRequest request) {
                throw new AssertionError("seed should not launch workflow executions");
            }

            @Override
            public WorkflowContracts.WorkflowResult submitHumanActionAndAwaitResult(String workflowId, WorkflowContracts.HumanAction action) {
                throw new AssertionError("seed should not resume workflow executions");
            }

            @Override
            public WorkflowContracts.WorkflowResult currentResult(String workflowId) {
                throw new AssertionError("seed should not query workflow executions");
            }
        }, catalogService());

        seededService.seedDemoData(false);

        assertEquals(2, seededService.listSessions().size());
        assertTrue(seededService.listTasks().isEmpty());
        assertTrue(seededService.listWorkflows().isEmpty());
    }

    @Test
    void shouldExposeNestedFailureReasonInWorkflowSummary() {
        RuntimeService failingService = new RuntimeService(new AssistantRunWorkflowGateway() {
            @Override
            public WorkflowContracts.WorkflowResult startAndAwaitFirstResult(WorkflowContracts.WorkflowStartRequest request) {
                throw new RuntimeException(
                    "Workflow execution failed",
                    new IllegalStateException("agent-runtime request failed: 500 {\"detail\":\"Missing API key env var: OPENAI_API_KEY\"}")
                );
            }

            @Override
            public WorkflowContracts.WorkflowResult submitHumanActionAndAwaitResult(String workflowId, WorkflowContracts.HumanAction action) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WorkflowContracts.WorkflowResult currentResult(String workflowId) {
                return null;
            }
        }, catalogService());

        RuntimeDtos.TaskInstanceDto task = failingService.launchTask(
            new RuntimeDtos.TaskLaunchRequest("scenario-customer-ops", "assistant-customer-ops", "你好", "tester")
        );

        assertEquals(TaskStatus.FAILED, task.status());
        assertTrue(failingService.getWorkflow(task.workflowInstanceId()).summary().contains("Missing API key env var: OPENAI_API_KEY"));
    }

    @Test
    void shouldResumeWaitingWorkflowThroughHumanAction() {
        RuntimeDtos.TaskInstanceDto task = service.launchTask(
            new RuntimeDtos.TaskLaunchRequest("scenario-customer-ops", "assistant-customer-ops", "客户投诉，需要人工处理", "tester")
        );

        var workflow = service.handleHumanAction(task.workflowInstanceId(), new RuntimeDtos.HumanActionRequest("CONFIRM", "人工已处理", "operator-1", Map.of()));

        assertEquals(WorkflowContracts.WorkflowStatus.COMPLETED, workflow.status());
        assertEquals(TaskStatus.COMPLETED, service.listTasks().stream().filter(item -> item.id().equals(task.id())).findFirst().orElseThrow().status());
    }

    @Test
    void shouldRefreshRunningWorkflowResultOnRead() {
        Queue<WorkflowContracts.WorkflowResult> polledResults = new ArrayDeque<>();
        RuntimeService refreshingService = new RuntimeService(new AssistantRunWorkflowGateway() {
            @Override
            public WorkflowContracts.WorkflowResult startAndAwaitFirstResult(WorkflowContracts.WorkflowStartRequest request) {
                WorkflowContracts.WorkflowResult completed = new WorkflowContracts.WorkflowResult(
                    request.workflowInstanceId(),
                    WorkflowContracts.WorkflowStatus.COMPLETED,
                    "问题已自动处理完成。",
                    "请通过登录页的忘记密码完成密码重置。",
                    "end",
                    null,
                    null,
                    null,
                    List.of(new WorkflowContracts.NodeSnapshot("end", "结束", WorkflowContracts.NodeStatus.COMPLETED, "流程结束", Instant.now())),
                    List.of(),
                    false,
                    null,
                    List.of()
                );
                polledResults.add(completed);
                return new WorkflowContracts.WorkflowResult(
                    request.workflowInstanceId(),
                    WorkflowContracts.WorkflowStatus.RUNNING,
                    "流程已启动，正在执行首轮节点。",
                    null,
                    "workflow-starting",
                    null,
                    null,
                    null,
                    List.of(new WorkflowContracts.NodeSnapshot("workflow-starting", "流程运行中", WorkflowContracts.NodeStatus.RUNNING, "流程已启动，正在执行首轮节点。", Instant.now())),
                    List.of(),
                    false,
                    null,
                    List.of()
                );
            }

            @Override
            public WorkflowContracts.WorkflowResult submitHumanActionAndAwaitResult(String workflowId, WorkflowContracts.HumanAction action) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WorkflowContracts.WorkflowResult currentResult(String workflowId) {
                return polledResults.peek();
            }
        }, catalogService());

        RuntimeDtos.ConversationSessionDto session = refreshingService.createSession(
            new RuntimeDtos.CreateConversationSessionRequest("scenario-customer-ops", "assistant-customer-ops", "tester", "怎么重置密码")
        );

        assertEquals("流程已启动，正在执行首轮节点。", session.messages().get(session.messages().size() - 1).content());

        RuntimeDtos.ConversationSessionDto refreshed = refreshingService.getSession(session.id());

        assertEquals("请通过登录页的忘记密码完成密码重置。", refreshed.messages().get(refreshed.messages().size() - 1).content());
        assertEquals(WorkflowContracts.WorkflowStatus.COMPLETED, refreshingService.getWorkflow(refreshed.latestWorkflowInstanceId()).status());
    }

    @Test
    void shouldIncludeKnowledgeDocumentsInRuntimeSnapshot() {
        List<WorkflowContracts.WorkflowStartRequest> capturedRequests = new ArrayList<>();
        RuntimeService snapshotService = new RuntimeService(new AssistantRunWorkflowGateway() {
            @Override
            public WorkflowContracts.WorkflowResult startAndAwaitFirstResult(WorkflowContracts.WorkflowStartRequest request) {
                capturedRequests.add(request);
                return new WorkflowContracts.WorkflowResult(
                    request.workflowInstanceId(),
                    WorkflowContracts.WorkflowStatus.COMPLETED,
                    "问题已自动处理完成。",
                    "请通过登录页的忘记密码完成密码重置。",
                    "end",
                    null,
                    null,
                    null,
                    List.of(new WorkflowContracts.NodeSnapshot("end", "结束", WorkflowContracts.NodeStatus.COMPLETED, "流程结束", Instant.now())),
                    List.of(),
                    false,
                    null,
                    List.of()
                );
            }

            @Override
            public WorkflowContracts.WorkflowResult submitHumanActionAndAwaitResult(String workflowId, WorkflowContracts.HumanAction action) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WorkflowContracts.WorkflowResult currentResult(String workflowId) {
                return null;
            }
        }, catalogService());

        snapshotService.launchTask(new RuntimeDtos.TaskLaunchRequest("scenario-customer-ops", "assistant-customer-ops", "怎么重置密码", "tester"));

        WorkflowContracts.KnowledgeBindingSnapshot assistantKnowledge = capturedRequests.getFirst().assistant().assistantKnowledge();

        assertNotNull(assistantKnowledge);
        assertEquals("knowledge-base-support", assistantKnowledge.knowledgeBaseId());
        assertEquals("knowledge-release-support-v1", assistantKnowledge.knowledgeReleaseId());
        assertEquals("snapshot-kb-support-v1", assistantKnowledge.snapshotId());
        assertEquals("HYBRID", assistantKnowledge.retrievalMode());
    }

    @Test
    void shouldFailWhenEnabledKnowledgeBaseHasNoPublishedRelease() {
        CatalogService catalogService = new CatalogService(
            new InMemoryCatalogRepository(),
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway()
        );
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("知识运营域", "承载知识演示"));
        CatalogDtos.ScenarioDto scenario = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "知识问答", "处理知识问答")
        );
        CatalogDtos.KnowledgeBaseDto knowledgeBase = catalogService.createKnowledgeBase(
            new CatalogDtos.CreateKnowledgeBaseRequest(
                domain.id(),
                "未发布知识库",
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "尚未创建 release",
                "知识运营",
                List.of("FAQ")
            )
        );
        CatalogDtos.AssistantDto assistant = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(
                scenario.id(),
                "知识助手",
                "依赖知识库回答问题",
                null,
                new CatalogDtos.RagPolicyDto(true, knowledgeBase.id()),
                null
            )
        );

        RuntimeService runtimeService = new RuntimeService(new AssistantRunWorkflowGateway() {
            @Override
            public WorkflowContracts.WorkflowResult startAndAwaitFirstResult(WorkflowContracts.WorkflowStartRequest request) {
                throw new AssertionError("workflow should not start when knowledge release is missing");
            }

            @Override
            public WorkflowContracts.WorkflowResult submitHumanActionAndAwaitResult(String workflowId, WorkflowContracts.HumanAction action) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WorkflowContracts.WorkflowResult currentResult(String workflowId) {
                return null;
            }
        }, catalogService);

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> runtimeService.launchTask(new RuntimeDtos.TaskLaunchRequest(scenario.id(), assistant.id(), "怎么重置密码", "tester"))
        );
        assertTrue(error.getMessage().contains("published knowledge release not found"));
    }

    private static CatalogService catalogService() {
        CatalogService catalogService = new CatalogService(
            new InMemoryCatalogRepository(),
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway()
        );
        catalogService.initializeDemoDataIfEmpty();
        return catalogService;
    }

    private static KnowledgeServiceClient readySnapshotKnowledgeClient() {
        return new KnowledgeServiceClient("http://localhost:8091") {
            @Override
            public CatalogDtos.KnowledgeIndexSnapshotDto getIndexSnapshot(String snapshotId) {
                return new CatalogDtos.KnowledgeIndexSnapshotDto(
                    snapshotId,
                    "knowledge-base-support",
                    "OPENSEARCH",
                    "HYBRID",
                    "READY",
                    1,
                    2,
                    null,
                    Instant.now(),
                    Instant.now(),
                    Instant.now()
                );
            }
        };
    }

    private static KnowledgeWorkflowGateway noopKnowledgeWorkflowGateway() {
        return new KnowledgeWorkflowGateway() {
            @Override
            public void startImport(String knowledgeBaseId, String importJobId) {
            }

            @Override
            public void startIndexBuild(String knowledgeBaseId, String indexSnapshotId) {
            }
        };
    }
}
