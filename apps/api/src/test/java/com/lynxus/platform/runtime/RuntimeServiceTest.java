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
import com.lynxus.contracts.runtime.WorkflowContracts.ConversationPayloadType;
import com.lynxus.contracts.runtime.WorkflowContracts.DecisionType;
import com.lynxus.contracts.runtime.WorkflowContracts.ResourceType;
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
            new RuntimeDtos.TaskLaunchRequest(fixture.scenarioId(), fixture.assistantId(), "怎么重置密码", "customer-1")
        );

        assertEquals(TaskStatus.RUNNING, task.status());
        RuntimeDtos.WorkflowInstanceDto workflow = service.getWorkflow(task.workflowInstanceId());
        assertEquals(WorkflowContracts.WorkflowStatus.RUNNING, workflow.status());
        assertEquals("流程已提交到 Temporal，等待首个运行结果。", workflow.summary());
        assertEquals(1, gateway.startRequests.size());
    }

    @Test
    void shouldPersistUserMessageBeforeLaunchingWorkflow() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        InMemoryRuntimeRepository repository = new InMemoryRuntimeRepository();
        gateway.onStart = request -> {
            RuntimeDtos.ConversationSessionDto storedSession = repository.findSession(request.sessionContext().sessionId()).orElseThrow();
            RuntimeDtos.WorkflowInstanceDto storedWorkflow = repository.findWorkflow(request.workflowInstanceId()).orElseThrow();
            assertEquals(request.workflowInstanceId(), storedSession.latestWorkflowInstanceId());
            assertEquals(1, storedSession.messages().size());
            assertEquals("怎么重置密码", storedSession.messages().getLast().content());
            assertEquals(WorkflowContracts.WorkflowStatus.RUNNING, storedWorkflow.status());
        };
        RuntimeService service = runtimeService(gateway, catalogService, repository);

        RuntimeDtos.ConversationSessionDto session = service.createSession(
            new RuntimeDtos.CreateConversationSessionRequest(fixture.scenarioId(), fixture.assistantId(), "customer-1", null)
        );
        RuntimeDtos.ConversationSessionDto updated = service.sendMessage(
            session.id(),
            textRequest("customer-1", "怎么重置密码")
        );

        assertEquals(1, updated.messages().size());
        assertEquals("怎么重置密码", updated.messages().getLast().content());
    }

    @Test
    void shouldMarkProjectionFailedWhenWorkflowStartSubmissionFails() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        gateway.startError = new RuntimeException("agent-runtime unavailable");
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.ConversationSessionDto session = service.createSession(
            new RuntimeDtos.CreateConversationSessionRequest(fixture.scenarioId(), fixture.assistantId(), "customer-1", null)
        );
        RuntimeDtos.ConversationSessionDto failed = service.sendMessage(
            session.id(),
            textRequest("customer-1", "你好")
        );

        RuntimeDtos.WorkflowInstanceDto workflow = service.getWorkflow(failed.latestWorkflowInstanceId());
        assertEquals(WorkflowContracts.WorkflowStatus.FAILED, workflow.status());
        assertTrue(workflow.summary().contains("agent-runtime unavailable"));
        assertNotNull(workflow.latestFailure());
        assertEquals("WORKFLOW_START_SUBMISSION_FAILED", workflow.latestFailure().code());
        assertEquals(1, failed.messages().size());
        assertEquals("你好", failed.messages().getLast().content());
    }

    @Test
    void shouldSubmitResumeActionWithoutWaitingForResumeResult() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        gateway.defaultCurrentResultFactory = request -> waitingHumanResult(request.workflowInstanceId(), request.question());
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.TaskInstanceDto task = service.launchTask(
            new RuntimeDtos.TaskLaunchRequest(fixture.scenarioId(), fixture.assistantId(), "客户投诉，需要人工处理", "customer-1")
        );
        RuntimeDtos.WorkflowInstanceDto waiting = service.getWorkflow(task.workflowInstanceId());
        assertEquals(WorkflowContracts.WorkflowStatus.WAITING_RESUME, waiting.status());

        RuntimeDtos.WorkflowInstanceDto resumed = service.handleResumeAction(
            task.workflowInstanceId(),
            new RuntimeDtos.ResumeActionRequest("CONTINUE", "人工已处理", "user-1", Map.of("resolution", "approved"))
        );

        assertEquals(WorkflowContracts.WorkflowStatus.RUNNING, resumed.status());
        assertEquals(TaskStatus.RUNNING, service.listTasks().stream().filter(item -> item.id().equals(task.id())).findFirst().orElseThrow().status());
        RuntimeDtos.WorkflowInstanceDto stored = service.getWorkflow(task.workflowInstanceId());
        assertEquals(RuntimeDtos.ResumeInterventionStatus.PENDING, stored.resumeInterventions().getLast().status());
        assertEquals(1, gateway.submittedResumeActions.size());
    }

    @Test
    void shouldUseCheckpointResumeSourceFromWorkflowState() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        gateway.defaultCurrentResultFactory = request -> waitingResult(
            request.workflowInstanceId(),
            request.question(),
            WorkflowContracts.ResumeSource.EXTERNAL_SYSTEM
        );
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.TaskInstanceDto task = service.launchTask(
            new RuntimeDtos.TaskLaunchRequest(fixture.scenarioId(), fixture.assistantId(), "客户投诉，需要外部系统处理", "customer-1")
        );

        service.handleResumeAction(
            task.workflowInstanceId(),
            new RuntimeDtos.ResumeActionRequest("CONTINUE", "外部系统已处理", "system-user", Map.of())
        );

        assertEquals(WorkflowContracts.ResumeSource.EXTERNAL_SYSTEM, gateway.submittedResumeActions.getLast().source());
        RuntimeDtos.WorkflowInstanceDto stored = service.getWorkflow(task.workflowInstanceId());
        assertEquals("EXTERNAL_SYSTEM", stored.resumeInterventions().getLast().source());
    }

    @Test
    void shouldPersistLatestFailureWhenResumeActionSubmissionFails() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        gateway.defaultCurrentResultFactory = request -> waitingHumanResult(request.workflowInstanceId(), request.question());
        gateway.submitError = new RuntimeException("temporal signal unavailable");
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.TaskInstanceDto task = service.launchTask(
            new RuntimeDtos.TaskLaunchRequest(fixture.scenarioId(), fixture.assistantId(), "客户投诉，需要人工处理", "customer-1")
        );

        RuntimeException error = assertThrows(
            RuntimeException.class,
            () -> service.handleResumeAction(
                task.workflowInstanceId(),
                new RuntimeDtos.ResumeActionRequest("CONTINUE", "人工已处理", "user-1", Map.of("resolution", "approved"))
            )
        );

        assertTrue(error.getMessage().contains("temporal signal unavailable"));
        RuntimeDtos.WorkflowInstanceDto stored = service.getWorkflow(task.workflowInstanceId());
        assertNotNull(stored.latestFailure());
        assertEquals("WORKFLOW_RESUME_SUBMISSION_FAILED", stored.latestFailure().code());
        assertEquals(WorkflowContracts.WorkflowStatus.WAITING_RESUME, stored.status());
        assertEquals(RuntimeDtos.ResumeInterventionStatus.FAILED, stored.resumeInterventions().getLast().status());
    }

    @Test
    void shouldReconcilePendingInterventionAndOutputMessagesOnRefresh() {
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
            "customer-1",
            TaskStatus.WAITING_RESUME,
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
            WorkflowContracts.WorkflowStatus.WAITING_RESUME,
            "已创建人工协同工单，等待人工处理。",
            "human-review",
            true,
            new WorkflowContracts.ExecutionCheckpoint("cp-1", "handoff-close", "human-review", "{}", null, 0),
            new WorkflowContracts.ResumeTaskSnapshot(
                "human-review",
                "人工介入待办",
                "请人工处理。",
                "补充处理意见并确认后续动作",
                WorkflowContracts.PauseSource.GRAPH_NODE,
                List.of(WorkflowContracts.ResumeActionType.CONTINUE)
            ),
            new WorkflowContracts.PauseReasonSnapshot("GRAPH_HUMAN_NODE", "请人工处理。", WorkflowContracts.PauseSource.GRAPH_NODE),
            null,
            null,
            List.of("tool@v1"),
            List.of(new RuntimeDtos.NodeExecutionDto("node-1", "wf-1", "human-review", "人工介入", WorkflowContracts.NodeStatus.WAITING_RESUME, "等待人工接管", now)),
            List.of(),
            List.of(),
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
            "customer-1",
            fixture.assistantId(),
            fixture.assistantName(),
            "release-v1",
            now,
            now,
            List.of(textMessage("msg-1", "session-1", "ASSISTANT", "ASSISTANT", fixture.assistantId(), fixture.assistantName(), "已进入人工协同流程。", now, "task-1", "wf-1")),
            "task-1",
            "wf-1",
            null,
            workflow.resumeTask(),
            workflow.pauseReason(),
            List.of(),
            WorkflowContracts.SharedSessionState.empty()
        );
        RuntimeDtos.ResumeInterventionDto pending = new RuntimeDtos.ResumeInterventionDto(
            "human-1",
            "wf-1",
            "CONTINUE",
            "HUMAN",
            "user-1",
            "人工已处理",
            Map.of(),
            RuntimeDtos.ResumeInterventionStatus.PENDING,
            now,
            null,
            null
        );
        repository.persistProjection(new RuntimeDtos.ProjectionPlanDto(task, workflow, session, session.messages(), List.of(), List.of(), pending));
        gateway.currentResults.put("wf-1", completedResult("wf-1", "人工处理已完成，已同步客户。", "turn-1"));

        service.reconcileRunningWorkflows();

        RuntimeDtos.WorkflowInstanceDto reconciledWorkflow = service.getWorkflow("wf-1");
        RuntimeDtos.ConversationSessionDto reconciledSession = service.getSession("session-1");
        assertEquals(WorkflowContracts.WorkflowStatus.COMPLETED, reconciledWorkflow.status());
        assertEquals(RuntimeDtos.ResumeInterventionStatus.APPLIED, reconciledWorkflow.resumeInterventions().getLast().status());
        assertEquals("人工处理已完成，已同步客户。", reconciledSession.messages().getLast().content());
    }

    @Test
    void shouldNotDuplicateWorkflowOutputMessagesAcrossRepeatedRefreshes() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.ConversationSessionDto session = service.createSession(
            new RuntimeDtos.CreateConversationSessionRequest(fixture.scenarioId(), fixture.assistantId(), "customer-1", null)
        );
        RuntimeDtos.ConversationSessionDto activeSession = service.sendMessage(
            session.id(),
            textRequest("customer-1", "请帮我处理退款")
        );
        gateway.currentResults.put(
            activeSession.latestWorkflowInstanceId(),
            completedResult(activeSession.latestWorkflowInstanceId(), "退款已经处理完成。", "turn-1")
        );

        RuntimeDtos.ConversationSessionDto firstRefresh = service.getSession(activeSession.id());
        RuntimeDtos.ConversationSessionDto secondRefresh = service.getSession(activeSession.id());
        RuntimeDtos.WorkflowInstanceDto workflow = service.getWorkflow(activeSession.latestWorkflowInstanceId());

        assertEquals(2, firstRefresh.messages().size());
        assertEquals(2, secondRefresh.messages().size());
        assertEquals("退款已经处理完成。", secondRefresh.messages().getLast().content());
        assertEquals(List.of("assistant-final-reply"), workflow.emittedMessageKeys());
    }

    @Test
    void shouldNotDuplicateExternalInteractionProjectionAcrossRepeatedRefreshes() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        gateway.defaultCurrentResultFactory = request -> waitingResult(
            request.workflowInstanceId(),
            request.question(),
            WorkflowContracts.ResumeSource.EXTERNAL_SYSTEM
        );
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.ConversationSessionDto session = service.createSession(
            new RuntimeDtos.CreateConversationSessionRequest(fixture.scenarioId(), fixture.assistantId(), "customer-1", null)
        );
        RuntimeDtos.ConversationSessionDto activeSession = service.sendMessage(session.id(), textRequest("customer-1", "需要继续外部授权"));
        gateway.currentResults.put(
            activeSession.latestWorkflowInstanceId(),
            waitingExternalInteractionResult(
                activeSession.latestWorkflowInstanceId(),
                "需要继续外部授权",
                WorkflowContracts.ExternalInteractionType.OAUTH_REDIRECT,
                "完成第三方授权",
                "请前往外部页面完成授权后返回。",
                "oauth-demo",
                "provider-ref-1",
                "https://example.com/oauth/start",
                "/console/runtime",
                "去授权",
                Map.of("intent", "oauth")
            )
        );

        RuntimeDtos.ConversationSessionDto firstRefresh = service.getSession(activeSession.id());
        RuntimeDtos.ConversationSessionDto secondRefresh = service.getSession(activeSession.id());
        String interactionTaskId = service.getWorkflow(activeSession.latestWorkflowInstanceId()).checkpoint().resumeContext().interactionTaskId();
        RuntimeDtos.ExternalInteractionTaskDto interaction = service.getExternalInteractionTask(interactionTaskId);

        assertEquals(2, firstRefresh.messages().size());
        assertEquals(2, secondRefresh.messages().size());
        assertEquals(1, interaction.events().size());
        assertEquals("create:" + activeSession.latestWorkflowInstanceId() + ":external-interaction-card", interaction.events().getFirst().dedupeKey());
    }

    @Test
    void shouldKeepPendingInterventionWhileResumeStillExposesRunningProjection() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        gateway.defaultCurrentResultFactory = request -> waitingHumanResult(request.workflowInstanceId(), request.question());
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.TaskInstanceDto task = service.launchTask(
            new RuntimeDtos.TaskLaunchRequest(fixture.scenarioId(), fixture.assistantId(), "客户投诉，需要人工处理", "customer-1")
        );
        service.handleResumeAction(
            task.workflowInstanceId(),
            new RuntimeDtos.ResumeActionRequest("CONTINUE", "人工已处理", "user-1", Map.of())
        );
        gateway.currentResults.put(task.workflowInstanceId(), runningResult(task.workflowInstanceId(), "human-review"));

        service.reconcileRunningWorkflows();

        RuntimeDtos.WorkflowInstanceDto stored = service.getWorkflow(task.workflowInstanceId());
        assertEquals(WorkflowContracts.WorkflowStatus.RUNNING, stored.status());
        assertEquals(RuntimeDtos.ResumeInterventionStatus.PENDING, stored.resumeInterventions().getLast().status());
    }

    @Test
    void shouldRejectConcurrentMessagesWhileSessionWorkflowIsActive() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        gateway.defaultCurrentResultFactory = request -> waitingHumanResult(request.workflowInstanceId(), request.question());
        InMemoryRuntimeRepository repository = new InMemoryRuntimeRepository();
        RuntimeService service = runtimeService(gateway, catalogService, repository);

        RuntimeDtos.ConversationSessionDto created = service.createSession(
            new RuntimeDtos.CreateConversationSessionRequest(fixture.scenarioId(), fixture.assistantId(), "customer-1", textInput("客户投诉，需要人工处理"))
        );

        int taskCount = service.listTasks().size();
        int workflowCount = service.listWorkflows().size();
        ConflictException error = assertThrows(
            ConflictException.class,
            () -> service.sendMessage(created.id(), textRequest("customer-1", "第二条消息"))
        );

        assertTrue(error.getMessage().contains("active workflow"));
        assertEquals(taskCount, service.listTasks().size());
        assertEquals(workflowCount, service.listWorkflows().size());
        assertEquals(1, service.getSession(created.id()).messages().size());
    }

    @Test
    void shouldAllowNextMessageAfterPreviousWorkflowFinishes() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.ConversationSessionDto created = service.createSession(
            new RuntimeDtos.CreateConversationSessionRequest(fixture.scenarioId(), fixture.assistantId(), "customer-1", textInput("第一条消息"))
        );
        String firstWorkflowId = created.latestWorkflowInstanceId();
        gateway.currentResults.put(firstWorkflowId, completedResult(firstWorkflowId, "已完成处理。", "turn-1"));

        RuntimeDtos.ConversationSessionDto secondTurn = service.sendMessage(
            created.id(),
            textRequest("customer-1", "第二条消息")
        );

        assertNotEquals(firstWorkflowId, secondTurn.latestWorkflowInstanceId());
        assertEquals(3, secondTurn.messages().size());
    }

    @Test
    void shouldCarrySharedStateIntoNextMessageAndExposeAgentTurnState() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.ConversationSessionDto created = service.createSession(
            new RuntimeDtos.CreateConversationSessionRequest(fixture.scenarioId(), fixture.assistantId(), "customer-1", textInput("第一条消息"))
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
            textRequest("customer-1", "第二条消息")
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

        service.launchTask(new RuntimeDtos.TaskLaunchRequest(fixture.scenarioId(), fixture.assistantId(), "怎么重置密码", "customer-1"));

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
        CatalogDtos.ResourceDto defaultModel = catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "知识助手默认模型",
                ResourceType.LLM_MODEL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "运行态测试默认模型",
                "平台模型团队",
                List.of("LLM"),
                null
            )
        );
        CatalogDtos.AssistantDto assistant = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(
                scenario.id(),
                "知识助手",
                "依赖知识库回答问题",
                new CatalogDtos.AssistantModelPolicyDto(defaultModel.id()),
                new CatalogDtos.RagPolicyDto(true, knowledgeBase.id()),
                null
            )
        );

        RuntimeService runtimeService = runtimeService(new StubWorkflowGateway(), catalogService);

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> runtimeService.launchTask(new RuntimeDtos.TaskLaunchRequest(scenario.id(), assistant.id(), "怎么重置密码", "customer-1"))
        );
        assertTrue(error.getMessage().contains("published knowledge release not found"));
    }

    @Test
    void shouldFailFastWhenLaunchingUnpublishedDraftWithoutDefaultModel() {
        CatalogService catalogService = new CatalogService(
            new InMemoryCatalogRepository(),
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway()
        );
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("运行域", "验证未发布草稿运行前置校验"));
        CatalogDtos.ScenarioDto scenario = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "草稿运行场景", "测试 launchTask 校验")
        );
        CatalogDtos.AssistantDto assistant = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(scenario.id(), "未发布草稿助手", "未配置默认模型", null, null, null)
        );
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        RuntimeService runtimeService = runtimeService(gateway, catalogService);

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> runtimeService.launchTask(new RuntimeDtos.TaskLaunchRequest(scenario.id(), assistant.id(), "怎么处理", "customer-1"))
        );

        assertTrue(error.getMessage().contains("assistant default model must be configured before running an unpublished draft"));
        assertTrue(gateway.startRequests.isEmpty());
    }

    @Test
    void shouldFailFastWhenSendingMessageToUnpublishedDraftWithoutDefaultModel() {
        CatalogService catalogService = new CatalogService(
            new InMemoryCatalogRepository(),
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway()
        );
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("会话域", "验证 sendMessage 校验"));
        CatalogDtos.ScenarioDto scenario = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "会话场景", "测试 sendMessage 校验")
        );
        CatalogDtos.AssistantDto assistant = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(scenario.id(), "草稿会话助手", "未配置默认模型", null, null, null)
        );
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        RuntimeService runtimeService = runtimeService(gateway, catalogService);
        RuntimeDtos.ConversationSessionDto session = runtimeService.createSession(
            new RuntimeDtos.CreateConversationSessionRequest(scenario.id(), assistant.id(), "customer-1", null)
        );

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> runtimeService.sendMessage(session.id(), textRequest("customer-1", "继续处理"))
        );

        assertTrue(error.getMessage().contains("assistant default model must be configured before running an unpublished draft"));
        assertTrue(gateway.startRequests.isEmpty());
    }

    @Test
    void shouldStillRunPublishedReleaseAfterDraftDefaultModelIsCleared() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        CatalogDtos.AssistantDto publishedAssistant = catalogService.listAssistants().stream()
            .filter(item -> item.id().equals(fixture.assistantId()))
            .findFirst()
            .orElseThrow();
        catalogService.updateAssistant(
            fixture.assistantId(),
            new CatalogDtos.UpdateAssistantRequest(
                publishedAssistant.name(),
                publishedAssistant.description(),
                VersionStatus.DRAFT,
                new CatalogDtos.AssistantModelPolicyDto(null),
                publishedAssistant.ragPolicy(),
                publishedAssistant.memoryPolicy()
            )
        );
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.TaskInstanceDto task = service.launchTask(
            new RuntimeDtos.TaskLaunchRequest(fixture.scenarioId(), fixture.assistantId(), "继续处理已发布助手", "customer-1")
        );

        assertEquals(TaskStatus.RUNNING, task.status());
        assertEquals(1, gateway.startRequests.size());
        WorkflowContracts.AssistantRunSnapshot snapshot = gateway.startRequests.getFirst().assistant();
        assertEquals("0.1.0", snapshot.assistantReleaseVersion());
        assertNotNull(snapshot.assistantPolicy().providerResourceVersionId());
    }

    @Test
    void shouldPersistModelHitsFromWorkflowResult() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.TaskInstanceDto task = service.launchTask(
            new RuntimeDtos.TaskLaunchRequest(fixture.scenarioId(), fixture.assistantId(), "怎么重置密码", "customer-1")
        );
        List<WorkflowContracts.ModelHitSnapshot> modelHits = List.of(
            new WorkflowContracts.ModelHitSnapshot(
                "agent-1",
                "客服执行智能体",
                "agent-node",
                "客服节点",
                WorkflowContracts.ModelSelectionSource.ASSISTANT_DEFAULT,
                "resource-model",
                "客服运行默认模型",
                "resource-version-model",
                "0.1.0",
                "OPENAI_COMPATIBLE",
                "custom-compatible-model",
                1,
                Instant.now()
            )
        );
        gateway.currentResults.put(task.workflowInstanceId(), completedResult(task.workflowInstanceId(), "已完成处理。", "turn-1", modelHits));

        RuntimeDtos.WorkflowInstanceDto workflow = service.getWorkflow(task.workflowInstanceId());

        assertEquals(1, workflow.modelHits().size());
        assertEquals(WorkflowContracts.ModelSelectionSource.ASSISTANT_DEFAULT, workflow.modelHits().getFirst().source());
        assertEquals("resource-model", workflow.modelHits().getFirst().resourceId());
    }

    @Test
    void shouldCreateExternalInteractionTaskAndAppendCardProjection() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        gateway.defaultCurrentResultFactory = request -> waitingResult(
            request.workflowInstanceId(),
            request.question(),
            WorkflowContracts.ResumeSource.EXTERNAL_SYSTEM
        );
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.ConversationSessionDto session = service.createSession(
            new RuntimeDtos.CreateConversationSessionRequest(fixture.scenarioId(), fixture.assistantId(), "customer-1", null)
        );
        RuntimeDtos.ConversationSessionDto activeSession = service.sendMessage(session.id(), textRequest("customer-1", "请继续外部授权"));
        gateway.currentResults.put(
            activeSession.latestWorkflowInstanceId(),
            waitingExternalInteractionResult(
                activeSession.latestWorkflowInstanceId(),
                "请继续外部授权",
                WorkflowContracts.ExternalInteractionType.OAUTH_REDIRECT,
                "完成第三方授权",
                "请前往外部页面完成授权后返回。",
                "oauth-demo",
                "provider-ref-1",
                "https://example.com/oauth/start",
                "/console/runtime",
                "去授权",
                Map.of("intent", "oauth")
            )
        );
        RuntimeDtos.ConversationSessionDto storedSession = service.getSession(activeSession.id());
        String interactionTaskId = service.getWorkflow(activeSession.latestWorkflowInstanceId()).checkpoint().resumeContext().interactionTaskId();
        RuntimeDtos.ExternalInteractionTaskDto interaction = service.getExternalInteractionTask(interactionTaskId);

        assertEquals(WorkflowContracts.ExternalInteractionStatus.AWAITING_USER_ACTION, interaction.status());
        assertEquals(1, interaction.events().size());
        assertEquals(ConversationPayloadType.EXTERNAL_INTERACTION, storedSession.messages().getLast().payloadType());
        assertEquals(
            "AWAITING_USER_ACTION",
            String.valueOf(((Map<?, ?>) storedSession.messages().getLast().payload().get("projection")).get("status"))
        );
        assertEquals(
            interaction.id(),
            service.getWorkflow(activeSession.latestWorkflowInstanceId()).checkpoint().resumeContext().interactionTaskId()
        );
    }

    @Test
    void shouldResumeWorkflowOnFrontendReturn() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        gateway.defaultCurrentResultFactory = request -> waitingResult(
            request.workflowInstanceId(),
            request.question(),
            WorkflowContracts.ResumeSource.EXTERNAL_SYSTEM
        );
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.ConversationSessionDto session = service.createSession(
            new RuntimeDtos.CreateConversationSessionRequest(fixture.scenarioId(), fixture.assistantId(), "customer-1", null)
        );
        RuntimeDtos.ConversationSessionDto activeSession = service.sendMessage(session.id(), textRequest("customer-1", "需要站外交互"));
        gateway.currentResults.put(
            activeSession.latestWorkflowInstanceId(),
            waitingExternalInteractionResult(
                activeSession.latestWorkflowInstanceId(),
                "需要站外交互",
                WorkflowContracts.ExternalInteractionType.GENERIC_REDIRECT,
                "继续站外操作",
                "请完成站外操作后返回。",
                "generic-provider",
                "provider-ref-2",
                "https://example.com/redirect",
                "/console/runtime",
                "打开",
                Map.of()
            )
        );
        String interactionTaskId = service.getWorkflow(activeSession.latestWorkflowInstanceId()).checkpoint().resumeContext().interactionTaskId();
        RuntimeDtos.ExternalInteractionTaskDto interaction = service.getExternalInteractionTask(interactionTaskId);

        RuntimeDtos.ExternalInteractionTaskDto returned = service.acknowledgeExternalInteractionReturn(
            interaction.id(),
            new RuntimeDtos.ExternalInteractionReturnRequest(
                interaction.returnToken(),
                "provider-ref-2",
                "return-dedupe-1",
                Map.of("status", "returned")
            )
        );

        assertEquals(WorkflowContracts.ExternalInteractionStatus.RETURNED, returned.status());
        assertNotNull(returned.resumedAt());
        assertEquals(1, gateway.submittedResumeActions.size());
        assertEquals("FRONTEND_RETURN", gateway.submittedResumeActions.getFirst().attributes().get("callbackSource"));
        assertEquals(interaction.id(), gateway.submittedResumeActions.getFirst().attributes().get("interactionTaskId"));
        assertEquals(WorkflowContracts.WorkflowStatus.RUNNING, service.getWorkflow(activeSession.latestWorkflowInstanceId()).status());
    }

    @Test
    void shouldIncludeCallbackResultPayloadWhenResumingExternalInteraction() {
        StubWorkflowGateway gateway = new StubWorkflowGateway();
        gateway.defaultCurrentResultFactory = request -> waitingResult(
            request.workflowInstanceId(),
            request.question(),
            WorkflowContracts.ResumeSource.EXTERNAL_SYSTEM
        );
        RuntimeService service = runtimeService(gateway);

        RuntimeDtos.ConversationSessionDto session = service.createSession(
            new RuntimeDtos.CreateConversationSessionRequest(fixture.scenarioId(), fixture.assistantId(), "customer-1", null)
        );
        RuntimeDtos.ConversationSessionDto activeSession = service.sendMessage(session.id(), textRequest("customer-1", "等待 provider callback"));
        gateway.currentResults.put(
            activeSession.latestWorkflowInstanceId(),
            waitingExternalInteractionResult(
                activeSession.latestWorkflowInstanceId(),
                "等待 provider callback",
                WorkflowContracts.ExternalInteractionType.PAYMENT_REDIRECT,
                "完成支付",
                "请完成支付后等待系统回调。",
                "payment-demo",
                "pay-ref-1",
                "https://example.com/pay",
                "/console/runtime",
                "去支付",
                Map.of()
            )
        );
        String interactionTaskId = service.getWorkflow(activeSession.latestWorkflowInstanceId()).checkpoint().resumeContext().interactionTaskId();
        RuntimeDtos.ExternalInteractionTaskDto interaction = service.getExternalInteractionTask(interactionTaskId);

        RuntimeDtos.ExternalInteractionTaskDto processed = service.receiveExternalInteractionCallback(
            "payment-demo",
            new RuntimeDtos.ExternalInteractionCallbackRequest(
                interaction.id(),
                "pay-ref-1",
                "callback-dedupe-1",
                Map.of("providerStatus", "APPROVED"),
                new WorkflowContracts.ExternalInteractionResult(
                    WorkflowContracts.ExternalInteractionOutcome.SUCCEEDED,
                    "APPROVED",
                    "支付成功",
                    "APPROVED",
                    Map.of("transactionId", "txn-1")
                )
            )
        );

        assertEquals(WorkflowContracts.ExternalInteractionStatus.PROCESSING, processed.status());
        assertNotNull(processed.latestResult());
        assertEquals("SUCCEEDED", processed.latestResult().outcome().name());
        assertEquals("PROVIDER_CALLBACK", gateway.submittedResumeActions.getFirst().attributes().get("callbackSource"));
        assertTrue(gateway.submittedResumeActions.getFirst().attributes().get("resultPayload").contains("\"transactionId\":\"txn-1\""));
    }

    private static WorkflowContracts.WorkflowResult waitingHumanResult(String workflowId, String question) {
        return waitingResult(workflowId, question, WorkflowContracts.ResumeSource.HUMAN);
    }

    private static WorkflowContracts.WorkflowResult waitingResult(
        String workflowId,
        String question,
        WorkflowContracts.ResumeSource resumeSource
    ) {
        return new WorkflowContracts.WorkflowResult(
            workflowId,
            WorkflowContracts.WorkflowStatus.WAITING_RESUME,
            "已创建人工协同工单，等待人工处理。",
            "human-review",
            new WorkflowContracts.ExecutionCheckpoint(
                "cp-1",
                "handoff-close",
                "human-review",
                "{\"question\":\"" + question + "\"}",
                new WorkflowContracts.ResumeContextSnapshot(resumeSource, "GRAPH_HUMAN_NODE", null, null, null),
                0
            ),
            new WorkflowContracts.ResumeTaskSnapshot(
                "human-review",
                "人工介入待办",
                "请人工处理。",
                "补充处理意见并确认后续动作",
                WorkflowContracts.PauseSource.GRAPH_NODE,
                List.of(WorkflowContracts.ResumeActionType.CONTINUE, WorkflowContracts.ResumeActionType.TERMINATE)
            ),
            new WorkflowContracts.PauseReasonSnapshot("GRAPH_HUMAN_NODE", "请人工处理。", WorkflowContracts.PauseSource.GRAPH_NODE),
            null,
            List.of(
                new WorkflowContracts.NodeSnapshot("start", "开始", WorkflowContracts.NodeStatus.COMPLETED, question, Instant.now()),
                new WorkflowContracts.NodeSnapshot("human-review", "人工介入", WorkflowContracts.NodeStatus.WAITING_RESUME, "等待人工接管", Instant.now())
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
            List.of(),
            List.of(),
            WorkflowContracts.SharedSessionState.empty(),
            AgentTurnState.empty()
        );
    }

    private static WorkflowContracts.WorkflowResult runningResult(String workflowId, String currentNodeKey) {
        return new WorkflowContracts.WorkflowResult(
            workflowId,
            WorkflowContracts.WorkflowStatus.RUNNING,
            "已收到恢复动作，流程继续执行中。",
            currentNodeKey,
            null,
            null,
            null,
            null,
            List.of(new WorkflowContracts.NodeSnapshot("workflow-resuming", "流程恢复", WorkflowContracts.NodeStatus.RUNNING, "已收到恢复动作，流程继续执行中。", Instant.now())),
            List.of(),
            false,
            null,
            List.of(),
            List.of(),
            List.of(),
            WorkflowContracts.SharedSessionState.empty(),
            AgentTurnState.empty()
        );
    }

    private static WorkflowContracts.WorkflowResult completedResult(String workflowId, String reply, String marker) {
        return completedResult(workflowId, reply, marker, List.of());
    }

    private static WorkflowContracts.WorkflowResult completedResult(
        String workflowId,
        String reply,
        String marker,
        List<WorkflowContracts.ModelHitSnapshot> modelHits
    ) {
        return new WorkflowContracts.WorkflowResult(
            workflowId,
            WorkflowContracts.WorkflowStatus.COMPLETED,
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
            modelHits,
            List.of(textOutputMessage("assistant-final-reply", reply)),
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
                    "default",
                    List.of(new WorkflowContracts.OutputMessageDraft(
                        ConversationPayloadType.TEXT,
                        Map.of("text", reply)
                    )),
                    List.of(),
                    List.of(),
                    null,
                    null
                ),
                List.of(new AgentTurnLog(1, "FINALIZE", DecisionType.FINAL, 0, 0, 0, "default", ""))
            )
        );
    }

    private static WorkflowContracts.WorkflowResult waitingExternalInteractionResult(
        String workflowId,
        String question,
        WorkflowContracts.ExternalInteractionType interactionType,
        String title,
        String instruction,
        String provider,
        String providerReference,
        String launchUrl,
        String returnPath,
        String primaryActionLabel,
        Map<String, Object> displayHints
    ) {
        return new WorkflowContracts.WorkflowResult(
            workflowId,
            WorkflowContracts.WorkflowStatus.WAITING_RESUME,
            instruction,
            "external-interaction",
            new WorkflowContracts.ExecutionCheckpoint(
                "cp-ext-1",
                "resume-after-interaction",
                "external-interaction",
                "{\"question\":\"" + question + "\"}",
                new WorkflowContracts.ResumeContextSnapshot(WorkflowContracts.ResumeSource.EXTERNAL_SYSTEM, "EXTERNAL_INTERACTION_REQUIRED", null, null, null),
                0
            ),
            new WorkflowContracts.ResumeTaskSnapshot(
                "external-interaction",
                title,
                instruction,
                "完成外部页面操作后返回",
                WorkflowContracts.PauseSource.EXTERNAL_INTERACTION,
                List.of(WorkflowContracts.ResumeActionType.CONTINUE)
            ),
            new WorkflowContracts.PauseReasonSnapshot("EXTERNAL_INTERACTION_REQUIRED", instruction, WorkflowContracts.PauseSource.EXTERNAL_INTERACTION),
            null,
            List.of(new WorkflowContracts.NodeSnapshot("external-interaction", title, WorkflowContracts.NodeStatus.WAITING_RESUME, instruction, Instant.now())),
            List.of(),
            true,
            null,
            List.of(),
            List.of(externalInteractionOutputMessage(
                "external-interaction-card",
                interactionType,
                title,
                instruction,
                provider,
                providerReference,
                launchUrl,
                returnPath,
                primaryActionLabel,
                displayHints
            )),
            List.of(),
            WorkflowContracts.SharedSessionState.empty(),
            AgentTurnState.empty()
        );
    }

    private static WorkflowContracts.WorkflowOutputMessage textOutputMessage(String messageKey, String text) {
        return new WorkflowContracts.WorkflowOutputMessage(
            messageKey,
            ConversationPayloadType.TEXT,
            Map.of("text", text),
            Instant.now()
        );
    }

    private static WorkflowContracts.WorkflowOutputMessage externalInteractionOutputMessage(
        String messageKey,
        WorkflowContracts.ExternalInteractionType interactionType,
        String title,
        String instruction,
        String provider,
        String providerReference,
        String launchUrl,
        String returnPath,
        String primaryActionLabel,
        Map<String, Object> displayHints
    ) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("interactionType", interactionType.name());
        spec.put("title", title);
        spec.put("instruction", instruction);
        spec.put("provider", provider);
        spec.put("providerReference", providerReference);
        spec.put("launchUrl", launchUrl);
        spec.put("returnPath", returnPath);
        spec.put("expiresAt", null);
        spec.put("primaryActionLabel", primaryActionLabel);
        spec.put("secondaryActions", List.of());
        spec.put("displayHints", displayHints);
        return new WorkflowContracts.WorkflowOutputMessage(
            messageKey,
            ConversationPayloadType.EXTERNAL_INTERACTION,
            Map.of("spec", spec),
            Instant.now()
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
        CatalogDtos.ResourceDto defaultModel = catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "客服运行默认模型",
                ResourceType.LLM_MODEL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "运行态默认模型",
                "平台模型团队",
                List.of("LLM"),
                null
            )
        );
        CatalogDtos.AssistantDto assistant = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(
                scenario.id(),
                "客服助手",
                "处理客服问题",
                new CatalogDtos.AssistantModelPolicyDto(defaultModel.id()),
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
        return new KnowledgeServiceClient("http://localhost:8091", "test-internal-token") {
            @Override
            public CatalogDtos.KnowledgeIndexSnapshotDto getIndexSnapshot(String snapshotId) {
                return new CatalogDtos.KnowledgeIndexSnapshotDto(
                    snapshotId,
                    "knowledge-base-runtime-test",
                    "PGVECTOR",
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

    private static RuntimeDtos.ConversationMessageInputDto textInput(String text) {
        return new RuntimeDtos.ConversationMessageInputDto(ConversationPayloadType.TEXT, Map.of("text", text));
    }

    private static RuntimeDtos.ConversationMessageRequest textRequest(String customerId, String text) {
        return new RuntimeDtos.ConversationMessageRequest(customerId, ConversationPayloadType.TEXT, Map.of("text", text));
    }

    private static RuntimeDtos.ConversationMessageDto textMessage(
        String id,
        String sessionId,
        String role,
        String senderType,
        String senderId,
        String senderName,
        String content,
        Instant createdAt,
        String taskId,
        String workflowInstanceId
    ) {
        return new RuntimeDtos.ConversationMessageDto(
            id,
            null,
            sessionId,
            role,
            senderType,
            senderId,
            senderName,
            ConversationPayloadType.TEXT,
            Map.of("text", content),
            content,
            createdAt,
            taskId,
            workflowInstanceId
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
        private final List<WorkflowContracts.ResumeAction> submittedResumeActions = new ArrayList<>();
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
        public void submitResumeAction(String workflowId, WorkflowContracts.ResumeAction action) {
            if (submitError != null) {
                throw submitError;
            }
            submittedResumeActions.add(action);
            currentResults.remove(workflowId);
        }

        @Override
        public WorkflowContracts.WorkflowResult currentResult(String workflowId) {
            return currentResults.get(workflowId);
        }
    }
}
