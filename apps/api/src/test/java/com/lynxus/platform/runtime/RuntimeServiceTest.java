package com.lynxus.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.contracts.runtime.WorkflowContracts.AgentTurnLog;
import com.lynxus.contracts.runtime.WorkflowContracts.AgentTurnState;
import com.lynxus.contracts.runtime.WorkflowContracts.DecisionType;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.TaskStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
import com.lynxus.platform.catalog.CatalogDtos;
import com.lynxus.platform.catalog.CatalogService;
import com.lynxus.platform.catalog.InMemoryCatalogRepository;
import com.lynxus.platform.knowledge.KnowledgeServiceClient;
import com.lynxus.platform.knowledge.KnowledgeWorkflowGateway;
import com.lynxus.platform.shared.ConflictException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeServiceTest {
    private final CatalogFixture fixture = catalogFixture();
    private final CatalogService catalogService = fixture.service();

    @Test
    void shouldAcceptTaskWithoutWaitingForFirstResult() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.TaskInstanceDto task = service.launchTask(
            new RuntimeDtos.TaskLaunchRequest(fixture.scenarioId(), fixture.assistantId(), "怎么重置密码", "tester")
        );

        assertEquals(TaskStatus.RUNNING, task.status());
        RuntimeDtos.WorkflowInstanceDto workflow = service.getWorkflow(task.workflowInstanceId());
        assertEquals(WorkflowContracts.WorkflowStatus.RUNNING, workflow.status());
        assertEquals("流程已提交到 Temporal，等待首个运行结果。", workflow.summary());
        assertEquals(1, gateway.startRequests.size());
    }

    @Test
    void shouldPersistPlaceholderAssistantMessageBeforeLaunchingWorkflow() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        InMemoryRuntimeRepository repository = new InMemoryRuntimeRepository();
        gateway.onStart = request -> {
            RuntimeDtos.ConversationSessionDto storedSession = repository.findSession(request.sessionContext().sessionId()).orElseThrow();
            RuntimeDtos.WorkflowInstanceDto storedWorkflow = repository.findWorkflow(request.workflowInstanceId()).orElseThrow();
            assertEquals(request.workflowInstanceId(), storedSession.latestWorkflowInstanceId());
            assertEquals("怎么重置密码", storedSession.messages().get(storedSession.messages().size() - 2).content());
            assertEquals("流程已提交到 Temporal，等待首个运行结果。", storedSession.messages().getLast().content());
            assertEquals(WorkflowContracts.WorkflowStatus.RUNNING, storedWorkflow.status());
        };
        RuntimeService service = runtimeService(gateway, catalogService, repository);

        RuntimeDtos.ConversationSessionDto session = service.createSession(
            new RuntimeDtos.CreateConversationSessionRequest(fixture.scenarioId(), fixture.assistantId(), "tester", null)
        );
        RuntimeDtos.ConversationSessionDto updated = service.sendMessage(
            session.id(),
            new RuntimeDtos.ConversationMessageRequest("tester", "怎么重置密码")
        );

        assertEquals(2, updated.messages().size());
        assertEquals("流程已提交到 Temporal，等待首个运行结果。", updated.messages().getLast().content());
    }

    @Test
    void shouldMarkProjectionFailedWhenWorkflowStartSubmissionFails() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        gateway.startError = new RuntimeException("agent-runtime unavailable");
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.ConversationSessionDto session = service.createSession(
            new RuntimeDtos.CreateConversationSessionRequest(fixture.scenarioId(), fixture.assistantId(), "tester", null)
        );
        RuntimeDtos.ConversationSessionDto failed = service.sendMessage(
            session.id(),
            new RuntimeDtos.ConversationMessageRequest("tester", "你好")
        );

        RuntimeDtos.WorkflowInstanceDto workflow = service.getWorkflow(failed.latestWorkflowInstanceId());
        assertEquals(WorkflowContracts.WorkflowStatus.FAILED, workflow.status());
        assertTrue(workflow.summary().contains("agent-runtime unavailable"));
        assertNotNull(workflow.latestFailure());
        assertEquals("WORKFLOW_START_SUBMISSION_FAILED", workflow.latestFailure().code());
        assertTrue(failed.messages().getLast().content().contains("agent-runtime unavailable"));
    }

    @Test
    void shouldSubmitHumanActionWithoutWaitingForResumeResult() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        gateway.defaultCurrentResultFactory = request -> waitingHumanResult(request.workflowInstanceId(), request.question());
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.TaskInstanceDto task = service.launchTask(
            new RuntimeDtos.TaskLaunchRequest(fixture.scenarioId(), fixture.assistantId(), "客户投诉，需要人工处理", "tester")
        );
        RuntimeDtos.WorkflowInstanceDto waiting = service.getWorkflow(task.workflowInstanceId());
        assertEquals(WorkflowContracts.WorkflowStatus.WAITING_HUMAN, waiting.status());

        RuntimeDtos.WorkflowInstanceDto resumed = service.handleHumanAction(
            task.workflowInstanceId(),
            new RuntimeDtos.HumanActionRequest("CONFIRM", "人工已处理", "operator-1", Map.of("resolution", "approved"))
        );

        assertEquals(WorkflowContracts.WorkflowStatus.RUNNING, resumed.status());
        assertEquals(TaskStatus.RUNNING, service.listTasks().stream().filter(item -> item.id().equals(task.id())).findFirst().orElseThrow().status());
        RuntimeDtos.WorkflowInstanceDto stored = service.getWorkflow(task.workflowInstanceId());
        assertEquals(RuntimeDtos.HumanInterventionStatus.PENDING, stored.interventions().getLast().status());
        assertEquals(1, gateway.submittedHumanActions.size());
    }

    @Test
    void shouldPersistLatestFailureWhenHumanActionSubmissionFails() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        gateway.defaultCurrentResultFactory = request -> waitingHumanResult(request.workflowInstanceId(), request.question());
        gateway.submitError = new RuntimeException("temporal signal unavailable");
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.TaskInstanceDto task = service.launchTask(
            new RuntimeDtos.TaskLaunchRequest(fixture.scenarioId(), fixture.assistantId(), "客户投诉，需要人工处理", "tester")
        );

        RuntimeException error = assertThrows(
            RuntimeException.class,
            () -> service.handleHumanAction(
                task.workflowInstanceId(),
                new RuntimeDtos.HumanActionRequest("CONFIRM", "人工已处理", "operator-1", Map.of("resolution", "approved"))
            )
        );

        assertTrue(error.getMessage().contains("temporal signal unavailable"));
        RuntimeDtos.WorkflowInstanceDto stored = service.getWorkflow(task.workflowInstanceId());
        assertNotNull(stored.latestFailure());
        assertEquals("WORKFLOW_RESUME_SUBMISSION_FAILED", stored.latestFailure().code());
        assertEquals(WorkflowContracts.WorkflowStatus.WAITING_HUMAN, stored.status());
        assertEquals(RuntimeDtos.HumanInterventionStatus.FAILED, stored.interventions().getLast().status());
    }

    @Test
    void shouldReconcilePendingInterventionAndFinalReplyOnRefresh() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        InMemoryRuntimeRepository repository = new InMemoryRuntimeRepository();
        RuntimeService service = runtimeService(gateway, catalogService, repository);
        Instant now = Instant.now();

        RuntimeDtos.TaskInstanceDto task = new RuntimeDtos.TaskInstanceDto(
            "task-1",
            fixture.scenarioId(),
            fixture.assistantId(),
            fixture.assistantName(),
            "release-v1",
            "客户投诉，需要人工处理",
            "tester",
            TaskStatus.WAITING_HUMAN,
            now,
            "wf-1"
        );
        RuntimeDtos.WorkflowInstanceDto workflow = new RuntimeDtos.WorkflowInstanceDto(
            "wf-1",
            "task-1",
            fixture.assistantId(),
            fixture.assistantName(),
            "release-v1",
            now,
            now,
            WorkflowContracts.WorkflowStatus.WAITING_HUMAN,
            "已创建人工协同工单，等待人工处理。",
            null,
            "human-review",
            true,
            new WorkflowContracts.ExecutionCheckpoint("cp-1", "handoff-close", "human-review", "{}", 0),
            new WorkflowContracts.HumanTaskSnapshot("human-review", "人工介入待办", "请人工处理。", "补充处理意见并确认后续动作", "GRAPH_NODE", List.of("CONFIRM")),
            new WorkflowContracts.PauseReasonSnapshot("GRAPH_HUMAN_NODE", "请人工处理。", "GRAPH_NODE"),
            null,
            null,
            List.of("tool@v1"),
            List.of(new RuntimeDtos.NodeExecutionDto("node-1", "wf-1", "human-review", "人工介入", WorkflowContracts.NodeStatus.WAITING_HUMAN, "等待人工接管", now)),
            List.of(),
            List.of(),
            List.of(),
            WorkflowContracts.SharedSessionState.empty(),
            AgentTurnState.empty()
        );
        RuntimeDtos.ConversationSessionDto session = new RuntimeDtos.ConversationSessionDto(
            "session-1",
            fixture.scenarioId(),
            "投诉处理",
            "tester",
            fixture.assistantId(),
            fixture.assistantName(),
            "release-v1",
            now,
            now,
            List.of(new RuntimeDtos.ConversationMessageDto("msg-1", "session-1", "ASSISTANT", "ASSISTANT", fixture.assistantId(), fixture.assistantName(), "已进入人工协同流程。", now, "task-1", "wf-1")),
            "task-1",
            "wf-1",
            null,
            workflow.humanTask(),
            workflow.pauseReason(),
            List.of(),
            WorkflowContracts.SharedSessionState.empty()
        );
        RuntimeDtos.HumanInterventionDto pending = new RuntimeDtos.HumanInterventionDto(
            "human-1",
            "wf-1",
            "CONFIRM",
            "operator-1",
            "人工已处理",
            Map.of(),
            RuntimeDtos.HumanInterventionStatus.PENDING,
            now,
            null,
            null
        );
        repository.persistProjection(task, workflow, session, pending);
        gateway.currentResults.put("wf-1", completedResult("wf-1", "人工处理已完成，已同步客户。", "turn-1"));

        service.reconcileRunningWorkflows();

        RuntimeDtos.WorkflowInstanceDto reconciledWorkflow = service.getWorkflow("wf-1");
        RuntimeDtos.ConversationSessionDto reconciledSession = service.getSession("session-1");
        assertEquals(WorkflowContracts.WorkflowStatus.COMPLETED, reconciledWorkflow.status());
        assertEquals(RuntimeDtos.HumanInterventionStatus.APPLIED, reconciledWorkflow.interventions().getLast().status());
        assertEquals("人工处理已完成，已同步客户。", reconciledSession.messages().getLast().content());
    }

    @Test
    void shouldKeepPendingInterventionWhileResumeStillExposesRunningProjection() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        gateway.defaultCurrentResultFactory = request -> waitingHumanResult(request.workflowInstanceId(), request.question());
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.TaskInstanceDto task = service.launchTask(
            new RuntimeDtos.TaskLaunchRequest(fixture.scenarioId(), fixture.assistantId(), "客户投诉，需要人工处理", "tester")
        );
        service.handleHumanAction(
            task.workflowInstanceId(),
            new RuntimeDtos.HumanActionRequest("CONFIRM", "人工已处理", "operator-1", Map.of())
        );
        gateway.currentResults.put(task.workflowInstanceId(), runningResult(task.workflowInstanceId(), "human-review"));

        service.reconcileRunningWorkflows();

        RuntimeDtos.WorkflowInstanceDto stored = service.getWorkflow(task.workflowInstanceId());
        assertEquals(WorkflowContracts.WorkflowStatus.RUNNING, stored.status());
        assertEquals(RuntimeDtos.HumanInterventionStatus.PENDING, stored.interventions().getLast().status());
    }

    @Test
    void shouldRejectConcurrentMessagesWhileSessionWorkflowIsActive() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        gateway.defaultCurrentResultFactory = request -> waitingHumanResult(request.workflowInstanceId(), request.question());
        InMemoryRuntimeRepository repository = new InMemoryRuntimeRepository();
        RuntimeService service = runtimeService(gateway, catalogService, repository);

        RuntimeDtos.ConversationSessionDto created = service.createSession(
            new RuntimeDtos.CreateConversationSessionRequest(fixture.scenarioId(), fixture.assistantId(), "tester", "客户投诉，需要人工处理")
        );

        int taskCount = service.listTasks().size();
        int workflowCount = service.listWorkflows().size();
        ConflictException error = assertThrows(
            ConflictException.class,
            () -> service.sendMessage(created.id(), new RuntimeDtos.ConversationMessageRequest("tester", "第二条消息"))
        );

        assertTrue(error.getMessage().contains("active workflow"));
        assertEquals(taskCount, service.listTasks().size());
        assertEquals(workflowCount, service.listWorkflows().size());
        assertEquals(2, service.getSession(created.id()).messages().size());
    }

    @Test
    void shouldAllowNextMessageAfterPreviousWorkflowFinishes() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.ConversationSessionDto created = service.createSession(
            new RuntimeDtos.CreateConversationSessionRequest(fixture.scenarioId(), fixture.assistantId(), "tester", "第一条消息")
        );
        String firstWorkflowId = created.latestWorkflowInstanceId();
        gateway.currentResults.put(firstWorkflowId, completedResult(firstWorkflowId, "已完成处理。", "turn-1"));

        RuntimeDtos.ConversationSessionDto secondTurn = service.sendMessage(
            created.id(),
            new RuntimeDtos.ConversationMessageRequest("tester", "第二条消息")
        );

        assertNotEquals(firstWorkflowId, secondTurn.latestWorkflowInstanceId());
        assertEquals(4, secondTurn.messages().size());
    }

    @Test
    void shouldCarrySharedStateIntoNextMessageAndExposeAgentTurnState() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.ConversationSessionDto created = service.createSession(
            new RuntimeDtos.CreateConversationSessionRequest(fixture.scenarioId(), fixture.assistantId(), "tester", "第一条消息")
        );
        String firstWorkflowId = created.latestWorkflowInstanceId();
        gateway.currentResults.put(firstWorkflowId, completedResult(firstWorkflowId, "已完成处理。", "turn-1"));

        RuntimeDtos.ConversationSessionDto reconciled = service.getSession(created.id());
        RuntimeDtos.WorkflowInstanceDto firstWorkflow = service.getWorkflow(firstWorkflowId);

        assertEquals("已完成处理。", reconciled.messages().getLast().content());
        assertEquals("FINAL", firstWorkflow.agentTurnState().latestDecision().decisionType().name());
        assertEquals("FINALIZE", firstWorkflow.agentTurnState().phase());

        service.sendMessage(
            created.id(),
            new RuntimeDtos.ConversationMessageRequest("tester", "第二条消息")
        );

        WorkflowContracts.WorkflowStartRequest secondRequest = gateway.startRequests.getLast();
        assertEquals(
            "turn-1",
            ((Map<?, ?>) secondRequest.sessionContext().sharedState().facts().get("conversation")).get("marker")
        );
    }

    @Test
    void shouldIncludeKnowledgeDocumentsInRuntimeSnapshot() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        RuntimeService service = runtimeService(gateway);

        service.launchTask(new RuntimeDtos.TaskLaunchRequest(fixture.scenarioId(), fixture.assistantId(), "怎么重置密码", "tester"));

        WorkflowContracts.KnowledgeBindingSnapshot assistantKnowledge = gateway.startRequests.getFirst().assistant().assistantKnowledge();
        assertNotNull(assistantKnowledge);
        assertEquals(fixture.knowledgeBaseId(), assistantKnowledge.knowledgeBaseId());
        assertEquals(fixture.knowledgeReleaseId(), assistantKnowledge.knowledgeReleaseId());
        assertEquals(fixture.snapshotId(), assistantKnowledge.snapshotId());
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

        RuntimeService runtimeService = runtimeService(new StubWorkflowGateway(), catalogService);

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> runtimeService.launchTask(new RuntimeDtos.TaskLaunchRequest(scenario.id(), assistant.id(), "怎么重置密码", "tester"))
        );
        assertTrue(error.getMessage().contains("published knowledge release not found"));
    }

    private static WorkflowContracts.WorkflowResult waitingHumanResult(String workflowId, String question) {
        return new WorkflowContracts.WorkflowResult(
            workflowId,
            WorkflowContracts.WorkflowStatus.WAITING_HUMAN,
            "已创建人工协同工单，等待人工处理。",
            null,
            "human-review",
            new WorkflowContracts.ExecutionCheckpoint("cp-1", "handoff-close", "human-review", "{\"question\":\"" + question + "\"}", 0),
            new WorkflowContracts.HumanTaskSnapshot("human-review", "人工介入待办", "请人工处理。", "补充处理意见并确认后续动作", "GRAPH_NODE", List.of("CONFIRM", "TERMINATE")),
            new WorkflowContracts.PauseReasonSnapshot("GRAPH_HUMAN_NODE", "请人工处理。", "GRAPH_NODE"),
            null,
            List.of(
                new WorkflowContracts.NodeSnapshot("start", "开始", WorkflowContracts.NodeStatus.COMPLETED, question, Instant.now()),
                new WorkflowContracts.NodeSnapshot("human-review", "人工介入", WorkflowContracts.NodeStatus.WAITING_HUMAN, "等待人工接管", Instant.now())
            ),
            List.of(),
            true,
            new WorkflowContracts.ToolOutcomeSummary(
                "resource-tool-ticket",
                "工单协同 Tool",
                "create_ticket",
                "MCP",
                Map.of("ticketId", "TICKET-10001", "status", "ACCEPTED")
            ),
            List.of(),
            WorkflowContracts.SharedSessionState.empty(),
            AgentTurnState.empty()
        );
    }

    private static WorkflowContracts.WorkflowResult runningResult(String workflowId, String currentNodeKey) {
        return new WorkflowContracts.WorkflowResult(
            workflowId,
            WorkflowContracts.WorkflowStatus.RUNNING,
            "已收到人工动作，流程继续执行中。",
            null,
            currentNodeKey,
            null,
            null,
            null,
            null,
            List.of(new WorkflowContracts.NodeSnapshot("workflow-resuming", "流程恢复", WorkflowContracts.NodeStatus.RUNNING, "已收到人工动作，流程继续执行中。", Instant.now())),
            List.of(),
            false,
            null,
            List.of(),
            WorkflowContracts.SharedSessionState.empty(),
            AgentTurnState.empty()
        );
    }

    private static WorkflowContracts.WorkflowResult completedResult(String workflowId, String reply, String marker) {
        return new WorkflowContracts.WorkflowResult(
            workflowId,
            WorkflowContracts.WorkflowStatus.COMPLETED,
            reply,
            reply,
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
            new WorkflowContracts.SharedSessionState(
                Map.of("conversation", Map.of("marker", marker)),
                Map.of(),
                Map.of()
            ),
            new AgentTurnState(
                "FINALIZE",
                1,
                new WorkflowContracts.StructuredAgentDecision(
                    DecisionType.FINAL,
                    reply,
                    "default",
                    List.of(),
                    List.of(),
                    null,
                    null
                ),
                List.of(new AgentTurnLog(1, "FINALIZE", DecisionType.FINAL, 0, 0, 0, "default", ""))
            )
        );
    }

    private static CatalogFixture catalogFixture() {
        CatalogService catalogService = new CatalogService(
            new InMemoryCatalogRepository(),
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway()
        );
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("客服运营域", "承载客服运行测试"));
        CatalogDtos.ScenarioDto scenario = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "客服处理", "处理用户咨询与售后问题")
        );
        CatalogDtos.KnowledgeBaseDto knowledgeBase = catalogService.createKnowledgeBase(
            new CatalogDtos.CreateKnowledgeBaseRequest(
                domain.id(),
                "客服知识库",
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "用于客服问答与流程说明",
                "知识运营",
                List.of("FAQ", "支持")
            )
        );
        CatalogDtos.KnowledgeReleaseDto knowledgeRelease = catalogService.createKnowledgeRelease(
            knowledgeBase.id(),
            new CatalogDtos.CreateKnowledgeReleaseRequest(
                "客服知识正式版",
                VersionStatus.PUBLISHED,
                "snapshot-kb-support-v1",
                new CatalogDtos.KnowledgeRetrievalProfileDto(5, "HYBRID", 0.1)
            )
        );
        CatalogDtos.AssistantDto assistant = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(
                scenario.id(),
                "客服助手",
                "处理客服问题",
                null,
                new CatalogDtos.RagPolicyDto(true, knowledgeBase.id()),
                null
            )
        );
        catalogService.updateAssistant(
            assistant.id(),
            new CatalogDtos.UpdateAssistantRequest(
                assistant.name(),
                assistant.description(),
                VersionStatus.PUBLISHED,
                assistant.modelPolicy(),
                new CatalogDtos.RagPolicyDto(true, knowledgeBase.id()),
                assistant.memoryPolicy()
            )
        );
        return new CatalogFixture(
            catalogService,
            scenario.id(),
            assistant.id(),
            assistant.name(),
            knowledgeBase.id(),
            knowledgeRelease.id(),
            knowledgeRelease.snapshotId()
        );
    }

    private static KnowledgeServiceClient readySnapshotKnowledgeClient() {
        return new KnowledgeServiceClient("http://localhost:8091") {
            @Override
            public CatalogDtos.KnowledgeIndexSnapshotDto getIndexSnapshot(String snapshotId) {
                return new CatalogDtos.KnowledgeIndexSnapshotDto(
                    snapshotId,
                    "knowledge-base-runtime-test",
                    "OPENSEARCH",
                    "HYBRID",
                    "READY",
                    "READY",
                    100,
                    0,
                    false,
                    1,
                    2,
                    null,
                    Instant.now(),
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

    private RuntimeService runtimeService(StubWorkflowGateway workflowGateway) {
        return runtimeService(workflowGateway, catalogService);
    }

    private static RuntimeService runtimeService(StubWorkflowGateway workflowGateway, CatalogService catalogService) {
        return runtimeService(workflowGateway, catalogService, new InMemoryRuntimeRepository());
    }

    private static RuntimeService runtimeService(
        StubWorkflowGateway workflowGateway,
        CatalogService catalogService,
        InMemoryRuntimeRepository repository
    ) {
        return new RuntimeService(
            workflowGateway,
            catalogService,
            catalogService.knowledgeService(),
            repository
        );
    }

    private record CatalogFixture(
        CatalogService service,
        String scenarioId,
        String assistantId,
        String assistantName,
        String knowledgeBaseId,
        String knowledgeReleaseId,
        String snapshotId
    ) {
    }

    private static final class StubWorkflowGateway implements AssistantRunWorkflowGateway {
        private final List<WorkflowContracts.WorkflowStartRequest> startRequests = new ArrayList<>();
        private final List<WorkflowContracts.HumanAction> submittedHumanActions = new ArrayList<>();
        private final Map<String, WorkflowContracts.WorkflowResult> currentResults = new LinkedHashMap<>();
        private java.util.function.Consumer<WorkflowContracts.WorkflowStartRequest> onStart;
        private java.util.function.Function<WorkflowContracts.WorkflowStartRequest, WorkflowContracts.WorkflowResult> defaultCurrentResultFactory;
        private RuntimeException startError;
        private RuntimeException submitError;

        @Override
        public void start(WorkflowContracts.WorkflowStartRequest request) {
            if (startError != null) {
                throw startError;
            }
            startRequests.add(request);
            if (onStart != null) {
                onStart.accept(request);
            }
            if (defaultCurrentResultFactory != null) {
                currentResults.put(request.workflowInstanceId(), defaultCurrentResultFactory.apply(request));
            }
        }

        @Override
        public void submitHumanAction(String workflowId, WorkflowContracts.HumanAction action) {
            if (submitError != null) {
                throw submitError;
            }
            submittedHumanActions.add(action);
            currentResults.remove(workflowId);
        }

        @Override
        public WorkflowContracts.WorkflowResult currentResult(String workflowId) {
            return currentResults.get(workflowId);
        }
    }
}
