package com.lynxus.platform.runtime;

import static com.lynxus.platform.runtime.RuntimeDtos.*;

import com.lynxus.platform.catalog.CatalogDtos.AgentDto;
import com.lynxus.platform.catalog.CatalogDtos.AgentExecutionPolicyDto;
import com.lynxus.platform.catalog.CatalogDtos.AssistantDto;
import com.lynxus.platform.catalog.CatalogDtos.AssistantModelPolicyDto;
import com.lynxus.platform.catalog.CatalogDtos.AssistantOrchestrationDto;
import com.lynxus.platform.catalog.CatalogDtos.AssistantReleaseAgentDto;
import com.lynxus.platform.catalog.CatalogDtos.AssistantReleaseDto;
import com.lynxus.platform.catalog.CatalogDtos.AssistantReleaseResourceDto;
import com.lynxus.platform.catalog.CatalogDtos.HumanNodeConfigDto;
import com.lynxus.platform.catalog.CatalogDtos.KnowledgeBindingSnapshotDto;
import com.lynxus.platform.catalog.CatalogDtos.MemoryPolicyDto;
import com.lynxus.platform.catalog.CatalogDtos.OrchestrationEdgeDto;
import com.lynxus.platform.catalog.CatalogDtos.OrchestrationNodeDto;
import com.lynxus.platform.catalog.CatalogDtos.RagPolicyDto;
import com.lynxus.platform.catalog.CatalogDtos.ResourceDto;
import com.lynxus.platform.catalog.CatalogDtos.ResourceVersionConfigurationDto;
import com.lynxus.platform.catalog.CatalogDtos.ScenarioDto;
import com.lynxus.platform.catalog.CatalogService;
import com.lynxus.platform.knowledge.KnowledgeService;
import com.lynxus.platform.shared.ConflictException;
import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.contracts.runtime.WorkflowContracts.AgentExecutionPolicySnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.AgentTurnState;
import com.lynxus.contracts.runtime.WorkflowContracts.AgentSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.AssistantPolicySnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.AssistantRunSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.ExecutionCheckpoint;
import com.lynxus.contracts.runtime.WorkflowContracts.GraphEdgeSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.GraphNodeSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.GraphSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.HumanAction;
import com.lynxus.contracts.runtime.WorkflowContracts.HumanNodeConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.HumanTaskSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.HttpToolProviderConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeBindingSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.LlmModelConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.McpToolProviderConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.NodeStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.ResourceConfigurationSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.ResourceVersionSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.SessionContext;
import com.lynxus.contracts.runtime.WorkflowContracts.SessionMessageSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.SharedSessionState;
import com.lynxus.contracts.runtime.WorkflowContracts.SkillConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.TaskStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolOperationConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolOutcomeSummary;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowFailureCategory;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowFailureSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class RuntimeService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeService.class);
    private final AssistantRunWorkflowGateway workflowGateway;
    private final CatalogService catalogService;
    private final KnowledgeService knowledgeService;
    private final RuntimeRepository runtimeRepository;

    public RuntimeService(AssistantRunWorkflowGateway workflowGateway, CatalogService catalogService) {
        this(workflowGateway, catalogService, catalogService.knowledgeService(), new InMemoryRuntimeRepository());
    }

    @Autowired
    RuntimeService(
        AssistantRunWorkflowGateway workflowGateway,
        CatalogService catalogService,
        KnowledgeService knowledgeService,
        RuntimeRepository runtimeRepository
    ) {
        this.workflowGateway = workflowGateway;
        this.catalogService = catalogService;
        this.knowledgeService = knowledgeService;
        this.runtimeRepository = runtimeRepository;
    }

    public List<TaskInstanceDto> listTasks() {
        refreshRunningWorkflows();
        return runtimeRepository.listTasks().stream()
            .sorted(Comparator.comparing(TaskInstanceDto::createdAt).reversed())
            .toList();
    }

    public List<WorkflowInstanceDto> listWorkflows() {
        refreshRunningWorkflows();
        return runtimeRepository.listWorkflows().stream()
            .sorted(Comparator.comparing(WorkflowInstanceDto::updatedAt, Comparator.reverseOrder())
                .thenComparing(WorkflowInstanceDto::createdAt, Comparator.reverseOrder()))
            .toList();
    }

    public List<ConversationSessionDto> listSessions() {
        refreshRunningWorkflows();
        return runtimeRepository.listSessions().stream()
            .sorted(Comparator.comparing(ConversationSessionDto::updatedAt).reversed())
            .toList();
    }

    public ConversationSessionDto getSession(String sessionId) {
        refreshRunningWorkflows();
        return runtimeRepository.findSession(sessionId).orElseThrow();
    }

    public ConversationSessionDto createSession(CreateConversationSessionRequest request) {
        ScenarioDto scenario = catalogService.getScenario(request.scenarioId());
        AssistantDto assistant = resolveAssistant(scenario, request.assistantId());
        Instant now = Instant.now();
        ConversationSessionDto session = new ConversationSessionDto(
            nextId("session"),
            scenario.id(),
            summarizeTitle(request.openingMessage()),
            request.requester(),
            assistant.id(),
            assistant.name(),
            runtimeReleaseVersion(assistant),
            now,
            now,
            new ArrayList<>(),
            null,
            null,
            null,
            null,
            null,
            List.of(),
            SharedSessionState.empty()
        );
        persistSession(session);

        if (request.openingMessage() != null && !request.openingMessage().isBlank()) {
            return sendMessage(session.id(), new ConversationMessageRequest(request.requester(), request.openingMessage()));
        }

        return session;
    }

    public ConversationSessionDto sendMessage(String sessionId, ConversationMessageRequest request) {
        ConversationSessionDto existing = getSession(sessionId);
        ensureSessionReadyForNewTurn(existing);
        ScenarioDto scenario = catalogService.getScenario(existing.scenarioId());
        AssistantDto assistant = resolveAssistant(scenario, existing.assistantId());

        List<ConversationMessageDto> messages = new ArrayList<>(existing.messages());
        ConversationMessageDto userMessage = new ConversationMessageDto(
            nextId("msg"),
            sessionId,
            "USER",
            "USER",
            "user",
            request.requester(),
            request.message(),
            Instant.now(),
            null,
            null
        );
        messages.add(userMessage);
        PreparedTurn prepared = prepareTurn(sessionId, scenario.id(), assistant, request.requester(), request.message(), existing.sharedState());
        messages.add(placeholderAssistantMessage(sessionId, assistant, prepared.task(), prepared.workflow()));
        ConversationSessionDto startedSession = new ConversationSessionDto(
            existing.id(),
            existing.scenarioId(),
            existing.title(),
            existing.requester(),
            assistant.id(),
            assistant.name(),
            runtimeReleaseVersion(assistant),
            existing.createdAt(),
            Instant.now(),
            messages,
            prepared.task().id(),
            prepared.workflow().id(),
            prepared.workflow().latestToolOutcome(),
            prepared.workflow().humanTask(),
            prepared.workflow().pauseReason(),
            existing.loadedSkillResourceVersionIds(),
            existing.sharedState()
        );
        runtimeRepository.persistProjection(prepared.task(), prepared.workflow(), startedSession, null);

        try {
            workflowGateway.start(new WorkflowStartRequest(
                prepared.task().id(),
                prepared.workflow().id(),
                scenario.id(),
                request.message(),
                request.requester(),
                buildSessionContext(
                    sessionId,
                    request.requester(),
                    request.message(),
                    messages,
                    existing.loadedSkillResourceVersionIds(),
                    existing.sharedState()
                ),
                prepared.assistantSnapshot()
            ));
            return startedSession;
        } catch (RuntimeException error) {
            WorkflowInstanceDto workflow = failedWorkflow(
                prepared,
                "WORKFLOW_START_SUBMISSION_FAILED",
                describeFailure(error)
            );
            TaskInstanceDto task = withTaskStatus(prepared.task(), TaskStatus.FAILED);
            ConversationSessionDto failedSession = refreshSessionForWorkflow(startedSession, prepared.workflow(), workflow);
            runtimeRepository.persistProjection(task, workflow, failedSession, null);
            return failedSession;
        }
    }

    public TaskInstanceDto launchTask(TaskLaunchRequest request) {
        ScenarioDto scenario = catalogService.getScenario(request.scenarioId());
        AssistantDto assistant = resolveAssistant(scenario, request.assistantId());
        return executeTurn(request.scenarioId(), assistant, request.requester(), request.question()).task();
    }

    public WorkflowInstanceDto getWorkflow(String workflowId) {
        refreshRunningWorkflows();
        return runtimeRepository.findWorkflow(workflowId).orElseThrow();
    }

    public WorkflowInstanceDto handleHumanAction(String workflowId, HumanActionRequest request) {
        WorkflowInstanceDto existing = getWorkflow(workflowId);
        if (runtimeRepository.findPendingIntervention(workflowId).isPresent()) {
            throw new IllegalStateException("workflow has a pending human intervention awaiting reconciliation: " + workflowId);
        }
        HumanInterventionDto intervention = new HumanInterventionDto(
            nextId("human"),
            workflowId,
            request.action(),
            request.operatorId() == null || request.operatorId().isBlank() ? "system-operator" : request.operatorId(),
            request.comment(),
            request.attributes() == null ? Map.of() : Map.copyOf(request.attributes()),
            HumanInterventionStatus.PENDING,
            Instant.now(),
            null,
            null
        );
        runtimeRepository.saveHumanIntervention(intervention);
        try {
            workflowGateway.submitHumanAction(
                workflowId,
                new HumanAction(
                    request.action(),
                    request.comment(),
                    intervention.operator(),
                    intervention.attributes()
                )
            );
            WorkflowInstanceDto updated = workflowResuming(existing);
            TaskInstanceDto task = runtimeRepository.findTaskByWorkflowInstanceId(workflowId).orElseThrow();
            TaskInstanceDto updatedTask = withTaskStatus(task, TaskStatus.RUNNING);
            ConversationSessionDto session = findSessionByWorkflowId(workflowId).orElse(null);
            ConversationSessionDto updatedSession = session == null ? null : refreshSessionAfterHumanAction(session, updated);
            runtimeRepository.persistProjection(updatedTask, updated, updatedSession, intervention);
            return updated;
        } catch (RuntimeException error) {
            HumanInterventionDto failedIntervention = markInterventionFailed(intervention, describeFailure(error));
            WorkflowInstanceDto failedWorkflow = withLatestFailure(
                existing,
                new WorkflowFailureSnapshot(
                    WorkflowFailureCategory.RUNTIME_FAILURE,
                    "WORKFLOW_RESUME_SUBMISSION_FAILED",
                    describeFailure(error),
                    describeFailure(error),
                    existing.currentNodeKey(),
                    null,
                    null,
                    null,
                    Instant.now()
                )
            );
            runtimeRepository.saveHumanIntervention(failedIntervention);
            TaskInstanceDto task = runtimeRepository.findTaskByWorkflowInstanceId(workflowId).orElseThrow();
            ConversationSessionDto session = findSessionByWorkflowId(workflowId).orElse(null);
            runtimeRepository.persistProjection(task, failedWorkflow, session, failedIntervention);
            throw error;
        }
    }

    private TurnExecutionResult executeTurn(String scenarioId, AssistantDto assistant, String requester, String message) {
        PreparedTurn prepared = prepareTurn(null, scenarioId, assistant, requester, message, SharedSessionState.empty());
        runtimeRepository.persistProjection(prepared.task(), prepared.workflow(), null, null);

        try {
            workflowGateway.start(new WorkflowStartRequest(
                prepared.task().id(),
                prepared.workflow().id(),
                scenarioId,
                message,
                requester,
                buildSessionContext(null, requester, message, List.of(), List.of(), SharedSessionState.empty()),
                prepared.assistantSnapshot()
            ));
            return new TurnExecutionResult(prepared.task(), prepared.workflow(), prepared.workflow().summary());
        } catch (RuntimeException error) {
            WorkflowInstanceDto workflow = failedWorkflow(
                prepared,
                "WORKFLOW_START_SUBMISSION_FAILED",
                describeFailure(error)
            );
            TaskInstanceDto task = withTaskStatus(prepared.task(), TaskStatus.FAILED);
            runtimeRepository.persistProjection(task, workflow, null, null);
            return new TurnExecutionResult(task, workflow, firstNonBlank(workflow.finalReply(), workflow.summary()));
        }
    }

    private PreparedTurn prepareTurn(
        String sessionId,
        String scenarioId,
        AssistantDto assistant,
        String requester,
        String message,
        SharedSessionState sharedState
    ) {
        String taskId = nextId("task");
        String workflowId = nextId("wf");
        Instant now = Instant.now();
        AssistantRunSnapshot assistantSnapshot = buildAssistantSnapshot(assistant);
        log.info(
            "runtime start workflow={} assistant={} graphEdges={}",
            workflowId,
            assistant.id(),
            assistantSnapshot.graph().edges().stream()
                .map(edge -> "%s:%s->%s routeKey=%s default=%s".formatted(
                    edge.edgeKey(),
                    edge.sourceNodeKey(),
                    edge.targetNodeKey(),
                    edge.routeKey(),
                    edge.defaultEdge()
                ))
                .toList()
        );
        List<String> resourceAnchors = assistantSnapshot.resources().stream()
            .map(item -> item.resourceName() + "@" + item.resourceVersion())
            .toList();
        TaskInstanceDto task = new TaskInstanceDto(
            taskId,
            scenarioId,
            assistant.id(),
            assistant.name(),
            assistantSnapshot.assistantReleaseVersion(),
            message,
            requester,
            TaskStatus.RUNNING,
            Instant.now(),
            workflowId
        );

        WorkflowInstanceDto initialWorkflow = new WorkflowInstanceDto(
            workflowId,
            taskId,
            assistant.id(),
            assistant.name(),
            assistantSnapshot.assistantReleaseVersion(),
            now,
            now,
            WorkflowStatus.RUNNING,
            "流程已提交到 Temporal，等待首个运行结果。",
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            resourceAnchors,
            List.of(node(workflowId, "workflow-submitted", "流程提交", NodeStatus.RUNNING, "已提交到 Temporal 工作流队列")),
            List.of(),
            List.of(),
            List.of(),
            sharedStateOrEmpty(sharedState),
            AgentTurnState.empty()
        );
        return new PreparedTurn(task, initialWorkflow, assistantSnapshot);
    }

    private WorkflowInstanceDto mergeWorkflowResult(
        WorkflowInstanceDto existing,
        WorkflowResult result,
        List<HumanInterventionDto> interventions
    ) {
        WorkflowFailureSnapshot latestFailure = result.latestFailure() == null ? existing.latestFailure() : result.latestFailure();
        return new WorkflowInstanceDto(
            existing.id(),
            existing.taskId(),
            existing.assistantId(),
            existing.assistantName(),
            existing.assistantReleaseVersion(),
            existing.createdAt(),
            Instant.now(),
            result.status(),
            result.summary(),
            result.finalReply(),
            result.currentNodeKey(),
            result.escalationRequired(),
            result.checkpoint(),
            result.humanTask(),
            result.pauseReason(),
            latestFailure,
            result.latestToolOutcome(),
            existing.resourceAnchors(),
            result.nodes().stream()
                .map(node -> new NodeExecutionDto(nextId("node"), existing.id(), node.nodeKey(), node.nodeName(), node.status(), node.detail(), node.updatedAt()))
                .toList(),
            result.toolCalls(),
            interventions,
            result.loadedSkillResourceVersionIds(),
            sharedStateOrEmpty(result.sharedState()),
            result.agentTurnState() == null ? AgentTurnState.empty() : result.agentTurnState()
        );
    }

    private void refreshRunningWorkflows() {
        reconcilePendingHumanInterventions();
        List<WorkflowInstanceDto> runningWorkflows = runtimeRepository.listActiveWorkflows();
        for (WorkflowInstanceDto workflow : runningWorkflows) {
            if (runtimeRepository.findPendingIntervention(workflow.id()).isPresent()) {
                continue;
            }
            WorkflowResult latest = workflowGateway.currentResult(workflow.id());
            if (latest == null || matchesWorkflowResult(workflow, latest)) {
                continue;
            }
            WorkflowInstanceDto updated = mergeWorkflowResult(workflow, latest, workflow.interventions());
            TaskInstanceDto task = runtimeRepository.findTaskByWorkflowInstanceId(workflow.id()).orElseThrow();
            TaskInstanceDto updatedTask = withTaskStatus(task, toTaskStatus(updated.status()));
            ConversationSessionDto session = findSessionByWorkflowId(workflow.id()).orElse(null);
            ConversationSessionDto updatedSession = session == null ? null : refreshSessionForWorkflow(session, workflow, updated);
            runtimeRepository.persistProjection(updatedTask, updated, updatedSession, null);
        }
    }

    private void reconcilePendingHumanInterventions() {
        for (HumanInterventionDto intervention : runtimeRepository.listPendingInterventions()) {
            WorkflowInstanceDto workflow = runtimeRepository.findWorkflow(intervention.workflowInstanceId()).orElse(null);
            if (workflow == null) {
                continue;
            }
            WorkflowResult latest = workflowGateway.currentResult(workflow.id());
            if (!shouldApplyPendingIntervention(workflow, latest)) {
                continue;
            }
            HumanInterventionDto appliedIntervention = markInterventionApplied(intervention);
            WorkflowInstanceDto updated = mergeWorkflowResult(workflow, latest, replaceIntervention(workflow.interventions(), appliedIntervention));
            TaskInstanceDto task = runtimeRepository.findTaskByWorkflowInstanceId(workflow.id()).orElseThrow();
            TaskInstanceDto updatedTask = withTaskStatus(task, toTaskStatus(updated.status()));
            ConversationSessionDto session = findSessionByWorkflowId(workflow.id()).orElse(null);
            ConversationSessionDto updatedSession = session == null ? null : refreshSessionForWorkflow(session, workflow, updated);
            runtimeRepository.persistProjection(updatedTask, updated, updatedSession, appliedIntervention);
        }
    }

    private ConversationSessionDto refreshSessionForWorkflow(
        ConversationSessionDto session,
        WorkflowInstanceDto previous,
        WorkflowInstanceDto updated
    ) {
        if (!Objects.equals(session.latestWorkflowInstanceId(), updated.id())) {
            return session;
        }
        List<ConversationMessageDto> messages = new ArrayList<>(session.messages());
        int latestWorkflowMessageIndex = findLatestWorkflowMessageIndex(messages, updated.id());
        if (latestWorkflowMessageIndex >= 0) {
            ConversationMessageDto original = messages.get(latestWorkflowMessageIndex);
            String content = firstNonBlank(updated.finalReply(), updated.summary());
            messages.set(latestWorkflowMessageIndex, new ConversationMessageDto(
                original.id(),
                original.sessionId(),
                original.role(),
                original.senderType(),
                original.senderId(),
                original.senderName(),
                content,
                original.createdAt(),
                original.taskId(),
                original.workflowInstanceId()
            ));
        } else if (updated.finalReply() != null || updated.summary() != null) {
            messages.add(placeholderAssistantMessage(session.id(), session.assistantId(), session.assistantName(), updated.taskId(), updated.id(), firstNonBlank(updated.finalReply(), updated.summary())));
        }
        boolean changed = previous.status() != updated.status()
            || !Objects.equals(previous.summary(), updated.summary())
            || !Objects.equals(previous.finalReply(), updated.finalReply())
            || !Objects.equals(session.latestHumanTask(), updated.humanTask())
            || !Objects.equals(session.latestPauseReason(), updated.pauseReason())
            || !Objects.equals(previous.latestFailure(), updated.latestFailure())
            || !Objects.equals(session.latestToolOutcome(), updated.latestToolOutcome())
            || !Objects.equals(session.sharedState(), updated.sharedState())
            || !Objects.equals(previous.agentTurnState(), updated.agentTurnState())
            || latestWorkflowMessageIndex < 0;
        if (!changed) {
            return session;
        }
        return new ConversationSessionDto(
            session.id(),
            session.scenarioId(),
            session.title(),
            session.requester(),
            session.assistantId(),
            session.assistantName(),
            session.assistantReleaseVersion(),
            session.createdAt(),
            Instant.now(),
            messages,
            session.latestTaskId(),
            session.latestWorkflowInstanceId(),
            updated.latestToolOutcome(),
            updated.humanTask(),
            updated.pauseReason(),
            updated.loadedSkillResourceVersionIds(),
            updated.sharedState()
        );
    }

    private AssistantDto resolveAssistant(ScenarioDto scenario, String assistantId) {
        return scenario.assistants().stream()
            .filter(item -> item.id().equals(assistantId))
            .findFirst()
            .orElseThrow();
    }

    private void ensureSessionReadyForNewTurn(ConversationSessionDto session) {
        if (session.latestWorkflowInstanceId() == null) {
            return;
        }
        WorkflowInstanceDto latestWorkflow = runtimeRepository.findWorkflow(session.latestWorkflowInstanceId()).orElse(null);
        if (latestWorkflow != null && isWorkflowActive(latestWorkflow.status())) {
            throw new ConflictException("session has an active workflow that must finish before sending a new message: " + latestWorkflow.id());
        }
    }

    private AssistantRunSnapshot buildAssistantSnapshot(AssistantDto assistant) {
        AssistantReleaseDto release = assistant.currentRelease();
        if (release != null) {
            return new AssistantRunSnapshot(
                assistant.id(),
                assistant.name(),
                release.releaseVersion(),
                toAssistantPolicySnapshot(release.modelPolicy(), release.memoryPolicy(), release.resources()),
                toKnowledgeBindingSnapshot(release.assistantKnowledge()),
                release.agents().stream().map(agent -> toAgentSnapshot(agent, release.resources())).toList(),
                release.resources().stream().map(this::toResourceSnapshot).toList(),
                toGraphSnapshot(release.orchestration())
            );
        }

        AssistantOrchestrationDto orchestration = catalogService.getOrchestration(assistant.id());
        List<ResourceDto> resourceViews = catalogService.summary().resources();
        List<AssistantReleaseResourceDto> resolvedResources = collectAdHocResources(assistant, resourceViews);

        return new AssistantRunSnapshot(
            assistant.id(),
            assistant.name(),
            assistant.version().version(),
            toAssistantPolicySnapshot(assistant.modelPolicy(), assistant.memoryPolicy(), resolvedResources),
            toKnowledgeBindingSnapshot(resolveAssistantKnowledgeBinding(assistant)),
            assistant.agents().stream()
                .map(agent -> toAgentSnapshot(assistant, agent, resolvedResources))
                .toList(),
            resolvedResources.stream().map(this::toResourceSnapshot).toList(),
            toGraphSnapshot(orchestration)
        );
    }

    private AssistantPolicySnapshot toAssistantPolicySnapshot(
        AssistantModelPolicyDto modelPolicy,
        MemoryPolicyDto memoryPolicy,
        List<AssistantReleaseResourceDto> resources
    ) {
        return new AssistantPolicySnapshot(
            modelPolicy.providerResourceId(),
            resolveReleasedVersionId(resources, modelPolicy.providerResourceId()),
            memoryPolicy.enabled(),
            memoryPolicy.windowSize()
        );
    }

    private AgentSnapshot toAgentSnapshot(AssistantReleaseAgentDto agent, List<AssistantReleaseResourceDto> resources) {
        return new AgentSnapshot(
            agent.agentId(),
            agent.name(),
            agent.role(),
            agent.responsibility(),
            toAgentExecutionPolicySnapshot(agent.executionPolicy(), resources, agent.knowledge(), agent.skillResourceVersionIds(), agent.toolResourceVersionIds())
        );
    }

    private AgentSnapshot toAgentSnapshot(AssistantDto assistant, AgentDto agent, List<AssistantReleaseResourceDto> resources) {
        List<String> skillVersionIds = agent.executionPolicy().skillResourceIds().stream()
            .map(skillResourceId -> resolveReleasedVersionId(resources, skillResourceId))
            .toList();
        List<String> toolVersionIds = agent.executionPolicy().toolResourceIds().stream()
            .map(toolResourceId -> resolveReleasedVersionId(resources, toolResourceId))
            .toList();
        return new AgentSnapshot(
            agent.id(),
            agent.name(),
            agent.role(),
            agent.responsibility(),
            toAgentExecutionPolicySnapshot(agent.executionPolicy(), resources, resolveAgentKnowledgeBinding(assistant, agent), skillVersionIds, toolVersionIds)
        );
    }

    private AgentExecutionPolicySnapshot toAgentExecutionPolicySnapshot(
        AgentExecutionPolicyDto policy,
        List<AssistantReleaseResourceDto> resources,
        KnowledgeBindingSnapshotDto knowledge,
        List<String> skillResourceVersionIds,
        List<String> toolResourceVersionIds
    ) {
        return new AgentExecutionPolicySnapshot(
            policy.inheritAssistantDefaults(),
            policy.modelResourceId(),
            resolveReleasedVersionId(resources, policy.modelResourceId()),
            policy.systemPrompt(),
            policy.ragEnabled(),
            policy.inheritAssistantKnowledge(),
            toKnowledgeBindingSnapshot(knowledge),
            policy.memoryWindowSize(),
            policy.skillResourceIds(),
            skillResourceVersionIds,
            policy.toolResourceIds(),
            toolResourceVersionIds
        );
    }

    private List<AssistantReleaseResourceDto> collectAdHocResources(AssistantDto assistant, List<ResourceDto> resourceViews) {
        Map<String, AssistantReleaseResourceDto> resolved = new java.util.LinkedHashMap<>();
        captureAdHocEffectiveResource(resolved, resourceViews, assistant.modelPolicy().providerResourceId(), "ASSISTANT_DEFAULT_MODEL");

        for (AgentDto agent : assistant.agents()) {
            captureAdHocEffectiveResource(resolved, resourceViews, agent.executionPolicy().modelResourceId(), agent.name());
            for (String skillResourceId : agent.executionPolicy().skillResourceIds()) {
                captureAdHocEffectiveResource(resolved, resourceViews, skillResourceId, agent.name());
            }
            for (String toolResourceId : agent.executionPolicy().toolResourceIds()) {
                captureAdHocEffectiveResource(resolved, resourceViews, toolResourceId, agent.name());
            }
        }
        return List.copyOf(resolved.values());
    }

    private KnowledgeBindingSnapshotDto resolveAssistantKnowledgeBinding(AssistantDto assistant) {
        if (!assistant.ragPolicy().enabled() || assistant.ragPolicy().knowledgeBaseId() == null || assistant.ragPolicy().knowledgeBaseId().isBlank()) {
            return null;
        }
        return resolveKnowledgeBinding(assistant.ragPolicy().knowledgeBaseId());
    }

    private KnowledgeBindingSnapshotDto resolveAgentKnowledgeBinding(AssistantDto assistant, AgentDto agent) {
        if (!agent.executionPolicy().ragEnabled()) {
            return null;
        }
        if (agent.executionPolicy().inheritAssistantKnowledge()) {
            return null;
        }
        String knowledgeBaseId = agent.executionPolicy().knowledgeBaseId();
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            knowledgeBaseId = assistant.ragPolicy().knowledgeBaseId();
        }
        return knowledgeBaseId == null || knowledgeBaseId.isBlank() ? null : resolveKnowledgeBinding(knowledgeBaseId);
    }

    private KnowledgeBindingSnapshotDto resolveKnowledgeBinding(String knowledgeBaseId) {
        try {
            return knowledgeService.resolveKnowledgeBinding(knowledgeBaseId);
        } catch (IllegalStateException error) {
            if (error.getMessage() != null && error.getMessage().contains("has no published release")) {
                throw new IllegalStateException("published knowledge release not found: " + knowledgeBaseId, error);
            }
            throw error;
        }
    }

    private void captureAdHocEffectiveResource(
        Map<String, AssistantReleaseResourceDto> resolved,
        List<ResourceDto> resourceViews,
        String resourceId,
        String boundAgent
    ) {
        if (resourceId == null || resourceId.isBlank()) {
            return;
        }
        ResourceDto resource = findRuntimeResource(resourceViews, resourceId);
        var version = resource.effectiveVersion() == null ? resource.latestVersion() : resource.effectiveVersion();
        mergeAdHocResource(resolved, resource, version, boundAgent);
    }

    private void mergeAdHocResource(
        Map<String, AssistantReleaseResourceDto> resolved,
        ResourceDto resource,
        com.lynxus.platform.catalog.CatalogDtos.ResourceVersionDto version,
        String boundAgent
    ) {
        AssistantReleaseResourceDto current = resolved.get(version.id());
        if (current == null) {
            resolved.put(
                version.id(),
                new AssistantReleaseResourceDto(
                    resource.id(),
                    resource.name(),
                    resource.type(),
                    version.id(),
                    version.version(),
                    List.of(boundAgent),
                    version.configuration()
                )
            );
            return;
        }
        resolved.put(
            version.id(),
            new AssistantReleaseResourceDto(
                current.resourceId(),
                current.resourceName(),
                current.resourceType(),
                current.resourceVersionId(),
                current.resourceVersion(),
                append(current.boundAgents(), boundAgent),
                current.configuration()
            )
        );
    }

    private ResourceDto findRuntimeResource(List<ResourceDto> resourceViews, String resourceId) {
        return resourceViews.stream()
            .filter(resource -> resource.id().equals(resourceId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("runtime resource not found: " + resourceId));
    }

    private String resolveReleasedVersionId(List<AssistantReleaseResourceDto> resources, String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return null;
        }
        return resources.stream()
            .filter(resource -> resource.resourceId().equals(resourceId))
            .map(AssistantReleaseResourceDto::resourceVersionId)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("released resource version not found: " + resourceId));
    }

    private ResourceVersionSnapshot toResourceSnapshot(AssistantReleaseResourceDto resource) {
        return new ResourceVersionSnapshot(
            resource.resourceId(),
            resource.resourceName(),
            resource.resourceType(),
            resource.resourceVersionId(),
            resource.resourceVersion(),
            resource.boundAgents(),
            toResourceConfigurationSnapshot(resource.configuration())
        );
    }

    private ResourceConfigurationSnapshot toResourceConfigurationSnapshot(ResourceVersionConfigurationDto configuration) {
        return new ResourceConfigurationSnapshot(
            configuration.type(),
            configuration.tool() == null ? null : new ToolConfig(
                configuration.tool().operations() == null ? List.of() : configuration.tool().operations().stream()
                    .map(operation -> new ToolOperationConfig(
                        operation.name(),
                        operation.description(),
                        operation.inputSchema(),
                        operation.outputSchema()
                    ))
                    .toList(),
                configuration.tool().providerType(),
                configuration.tool().authType(),
                configuration.tool().timeoutSeconds(),
                configuration.tool().retryPolicy(),
                configuration.tool().http() == null ? null : new HttpToolProviderConfig(
                    configuration.tool().http().endpoint(),
                    configuration.tool().http().method()
                ),
                configuration.tool().mcp() == null ? null : new McpToolProviderConfig(
                    configuration.tool().mcp().serverName(),
                    configuration.tool().mcp().transport(),
                    configuration.tool().mcp().connectionUri(),
                    configuration.tool().mcp().namespace(),
                    configuration.tool().mcp().heartbeatSeconds(),
                    configuration.tool().mcp().operationMappings()
                )
            ),
            configuration.llmModel() == null ? null : new LlmModelConfig(
                configuration.llmModel().providerType(),
                configuration.llmModel().modelId(),
                configuration.llmModel().baseUrl(),
                configuration.llmModel().apiKeyEnvVar(),
                configuration.llmModel().organization(),
                configuration.llmModel().project(),
                configuration.llmModel().region(),
                configuration.llmModel().temperature(),
                configuration.llmModel().maxTokens()
            ),
            configuration.skill() == null ? null : new SkillConfig(
                configuration.skill().skillName(),
                configuration.skill().skillDesc(),
                configuration.skill().skillPrompt()
            )
        );
    }

    private KnowledgeBindingSnapshot toKnowledgeBindingSnapshot(KnowledgeBindingSnapshotDto binding) {
        if (binding == null) {
            return null;
        }
        return new KnowledgeBindingSnapshot(
            binding.knowledgeBaseId(),
            binding.knowledgeBaseName(),
            binding.knowledgeReleaseId(),
            binding.knowledgeReleaseVersion(),
            binding.snapshotId(),
            binding.defaultTopK(),
            binding.retrievalMode(),
            binding.minScore()
        );
    }

    private GraphSnapshot toGraphSnapshot(AssistantOrchestrationDto orchestration) {
        return new GraphSnapshot(
            orchestration.executionMode(),
            orchestration.nodes().stream().map(this::toGraphNodeSnapshot).toList(),
            orchestration.edges().stream().map(edge -> new GraphEdgeSnapshot(
                edge.edgeKey(),
                edge.sourceNodeKey(),
                edge.targetNodeKey(),
                edge.routeKey(),
                edge.label(),
                edge.defaultEdge()
            )).toList()
        );
    }

    private GraphNodeSnapshot toGraphNodeSnapshot(OrchestrationNodeDto node) {
        HumanNodeConfig humanNode = node.humanNode() == null ? null : new HumanNodeConfig(
            node.humanNode().title(),
            node.humanNode().instruction(),
            node.humanNode().expectedAction(),
            node.humanNode().resumeRouteKey()
        );
        return new GraphNodeSnapshot(node.nodeKey(), node.nodeName(), node.nodeType(), node.description(), node.agentId(), humanNode);
    }

    private SessionContext buildSessionContext(
        String sessionId,
        String requester,
        String latestMessage,
        List<ConversationMessageDto> currentMessages,
        List<String> loadedSkillResourceVersionIds,
        SharedSessionState sharedState
    ) {
        return new SessionContext(
            sessionId == null ? "adhoc-session" : sessionId,
            requester,
            latestMessage,
            currentMessages.stream()
                .map(message -> new SessionMessageSnapshot(message.role(), message.senderName(), message.content(), message.createdAt()))
                .toList(),
            loadedSkillResourceVersionIds == null ? List.of() : List.copyOf(loadedSkillResourceVersionIds),
            sharedStateOrEmpty(sharedState)
        );
    }

    private SharedSessionState sharedStateOrEmpty(SharedSessionState sharedState) {
        return sharedState == null ? SharedSessionState.empty() : sharedState;
    }

    private TaskInstanceDto withTaskStatus(TaskInstanceDto task, TaskStatus status) {
        return new TaskInstanceDto(
            task.id(),
            task.scenarioId(),
            task.assistantId(),
            task.assistantName(),
            task.assistantReleaseVersion(),
            task.question(),
            task.requester(),
            status,
            task.createdAt(),
            task.workflowInstanceId()
        );
    }

    private static TaskStatus toTaskStatus(WorkflowStatus status) {
        return switch (status) {
            case WAITING_HUMAN -> TaskStatus.WAITING_HUMAN;
            case COMPLETED -> TaskStatus.COMPLETED;
            case FAILED -> TaskStatus.FAILED;
            case CANCELLED -> TaskStatus.CANCELLED;
            default -> TaskStatus.RUNNING;
        };
    }

    private static String runtimeReleaseVersion(AssistantDto assistant) {
        return assistant.currentRelease() == null ? assistant.version().version() : assistant.currentRelease().releaseVersion();
    }

    private static String summarizeTitle(String openingMessage) {
        if (openingMessage == null || openingMessage.isBlank()) {
            return "新会话";
        }
        return openingMessage.length() > 18 ? openingMessage.substring(0, 18) + "..." : openingMessage;
    }

    public void reconcileRunningWorkflows() {
        refreshRunningWorkflows();
    }

    private void persistSession(ConversationSessionDto session) {
        runtimeRepository.saveSession(session);
    }

    private static NodeExecutionDto node(String workflowId, String key, String name, NodeStatus status, String detail) {
        return new NodeExecutionDto(nextId("node"), workflowId, key, name, status, detail, Instant.now());
    }

    private static ConversationMessageDto placeholderAssistantMessage(
        String sessionId,
        AssistantDto assistant,
        TaskInstanceDto task,
        WorkflowInstanceDto workflow
    ) {
        return placeholderAssistantMessage(sessionId, assistant.id(), assistant.name(), task.id(), workflow.id(), workflow.summary());
    }

    private static ConversationMessageDto placeholderAssistantMessage(
        String sessionId,
        String assistantId,
        String assistantName,
        String taskId,
        String workflowId,
        String content
    ) {
        return new ConversationMessageDto(
            nextId("msg"),
            sessionId,
            "ASSISTANT",
            "ASSISTANT",
            assistantId,
            assistantName,
            content,
            Instant.now(),
            taskId,
            workflowId
        );
    }

    private static int findLatestWorkflowMessageIndex(List<ConversationMessageDto> messages, String workflowId) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (Objects.equals(messages.get(index).workflowInstanceId(), workflowId)) {
                return index;
            }
        }
        return -1;
    }

    private Optional<ConversationSessionDto> findSessionByWorkflowId(String workflowId) {
        return runtimeRepository.listSessions().stream()
            .filter(session -> Objects.equals(session.latestWorkflowInstanceId(), workflowId))
            .findFirst();
    }

    private ConversationSessionDto refreshSessionAfterHumanAction(ConversationSessionDto session, WorkflowInstanceDto workflow) {
        return new ConversationSessionDto(
            session.id(),
            session.scenarioId(),
            session.title(),
            session.requester(),
            session.assistantId(),
            session.assistantName(),
            session.assistantReleaseVersion(),
            session.createdAt(),
            Instant.now(),
            session.messages(),
            session.latestTaskId(),
            session.latestWorkflowInstanceId(),
            workflow.latestToolOutcome(),
            workflow.humanTask(),
            workflow.pauseReason(),
            workflow.loadedSkillResourceVersionIds(),
            workflow.sharedState()
        );
    }

    private WorkflowInstanceDto workflowResuming(WorkflowInstanceDto workflow) {
        return new WorkflowInstanceDto(
            workflow.id(),
            workflow.taskId(),
            workflow.assistantId(),
            workflow.assistantName(),
            workflow.assistantReleaseVersion(),
            workflow.createdAt(),
            Instant.now(),
            WorkflowStatus.RUNNING,
            "已收到人工动作，流程继续执行中。",
            null,
            workflow.currentNodeKey(),
            false,
            workflow.checkpoint(),
            null,
            null,
            workflow.latestFailure(),
            workflow.latestToolOutcome(),
            workflow.resourceAnchors(),
            append(
                workflow.nodes(),
                node(workflow.id(), "workflow-resuming", "流程恢复", NodeStatus.RUNNING, "已收到人工动作，流程继续执行中。")
            ),
            workflow.toolCalls(),
            workflow.interventions(),
            workflow.loadedSkillResourceVersionIds(),
            workflow.sharedState(),
            workflow.agentTurnState() == null ? AgentTurnState.empty() : workflow.agentTurnState()
        );
    }

    private WorkflowInstanceDto withLatestFailure(WorkflowInstanceDto workflow, WorkflowFailureSnapshot latestFailure) {
        return new WorkflowInstanceDto(
            workflow.id(),
            workflow.taskId(),
            workflow.assistantId(),
            workflow.assistantName(),
            workflow.assistantReleaseVersion(),
            workflow.createdAt(),
            Instant.now(),
            workflow.status(),
            workflow.summary(),
            workflow.finalReply(),
            workflow.currentNodeKey(),
            workflow.escalationRequired(),
            workflow.checkpoint(),
            workflow.humanTask(),
            workflow.pauseReason(),
            latestFailure,
            workflow.latestToolOutcome(),
            workflow.resourceAnchors(),
            workflow.nodes(),
            workflow.toolCalls(),
            workflow.interventions(),
            workflow.loadedSkillResourceVersionIds(),
            workflow.sharedState(),
            workflow.agentTurnState() == null ? AgentTurnState.empty() : workflow.agentTurnState()
        );
    }

    private boolean shouldApplyPendingIntervention(WorkflowInstanceDto workflow, WorkflowResult latest) {
        if (latest == null || matchesWorkflowResult(workflow, latest)) {
            return false;
        }
        if (latest.status() == WorkflowStatus.RUNNING) {
            return false;
        }
        return latest.status() != WorkflowStatus.WAITING_HUMAN
            || (latest.checkpoint() != null && latest.checkpoint().resumeCount() > 0);
    }

    private WorkflowInstanceDto failedWorkflow(PreparedTurn prepared, String failureCode, String failureDetail) {
        WorkflowFailureSnapshot latestFailure = new WorkflowFailureSnapshot(
            WorkflowFailureCategory.RUNTIME_FAILURE,
            failureCode,
            failureDetail,
            failureDetail,
            null,
            null,
            null,
            null,
            Instant.now()
        );
        return new WorkflowInstanceDto(
            prepared.workflow().id(),
            prepared.task().id(),
            prepared.task().assistantId(),
            prepared.task().assistantName(),
            prepared.assistantSnapshot().assistantReleaseVersion(),
            prepared.workflow().createdAt(),
            Instant.now(),
            WorkflowStatus.FAILED,
            "流程执行失败：" + failureDetail,
            null,
            null,
            false,
            null,
            null,
            null,
            latestFailure,
            null,
            prepared.workflow().resourceAnchors(),
            List.of(node(prepared.workflow().id(), "workflow-failed", "流程执行失败", NodeStatus.FAILED, failureDetail)),
            List.of(),
            List.of(),
            List.of(),
            prepared.workflow().sharedState(),
            AgentTurnState.empty()
        );
    }

    private HumanInterventionDto markInterventionApplied(HumanInterventionDto intervention) {
        return new HumanInterventionDto(
            intervention.id(),
            intervention.workflowInstanceId(),
            intervention.action(),
            intervention.operator(),
            intervention.comment(),
            intervention.attributes(),
            HumanInterventionStatus.APPLIED,
            intervention.createdAt(),
            Instant.now(),
            null
        );
    }

    private HumanInterventionDto markInterventionFailed(HumanInterventionDto intervention, String failureReason) {
        return new HumanInterventionDto(
            intervention.id(),
            intervention.workflowInstanceId(),
            intervention.action(),
            intervention.operator(),
            intervention.comment(),
            intervention.attributes(),
            HumanInterventionStatus.FAILED,
            intervention.createdAt(),
            null,
            failureReason
        );
    }

    private static List<HumanInterventionDto> replaceIntervention(List<HumanInterventionDto> items, HumanInterventionDto updated) {
        List<HumanInterventionDto> next = new ArrayList<>();
        boolean replaced = false;
        for (HumanInterventionDto item : items) {
            if (item.id().equals(updated.id())) {
                next.add(updated);
                replaced = true;
            } else {
                next.add(item);
            }
        }
        if (!replaced) {
            next.add(updated);
        }
        return List.copyOf(next);
    }

    private static <T> List<T> append(List<T> items, T item) {
        List<T> updated = new ArrayList<>(items);
        updated.add(item);
        return updated;
    }

    private static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String describeFailure(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        if (error instanceof WorkflowFailedException workflowFailedException) {
            Throwable cause = workflowFailedException.getCause();
            String nested = cause == null ? workflowFailedException.getMessage() : describeFailure(cause);
            return nested == null || nested.isBlank() ? workflowFailedException.getMessage() : nested;
        }
        if (error instanceof ActivityFailure activityFailure) {
            Throwable cause = activityFailure.getCause();
            String nested = cause == null ? activityFailure.getMessage() : describeFailure(cause);
            return "Activity " + activityFailure.getActivityType() + " 失败: " + nested;
        }
        if (error instanceof ApplicationFailure applicationFailure) {
            String message = firstNonBlank(applicationFailure.getOriginalMessage(), applicationFailure.getMessage());
            Throwable cause = applicationFailure.getCause();
            if (cause == null) {
                return message;
            }
            return message + " | cause: " + describeFailure(cause);
        }
        if (error instanceof TimeoutFailure timeoutFailure) {
            String timeoutType = timeoutFailure.getTimeoutType() == null ? "UNKNOWN" : timeoutFailure.getTimeoutType().name();
            return "调用超时(" + timeoutType + "): " + firstNonBlank(timeoutFailure.getMessage(), "timeout");
        }
        Throwable cause = error.getCause();
        String message = firstNonBlank(error.getMessage(), error.getClass().getSimpleName());
        if (cause == null || cause == error) {
            return message;
        }
        String nested = describeFailure(cause);
        if (nested == null || nested.isBlank() || nested.equals(message)) {
            return message;
        }
        return message + " | cause: " + nested;
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback == null || fallback.isBlank() ? "未知错误" : fallback;
    }

    private static boolean matchesWorkflowResult(WorkflowInstanceDto existing, WorkflowResult result) {
        WorkflowFailureSnapshot latestFailure = result.latestFailure() == null ? existing.latestFailure() : result.latestFailure();
        return existing.status() == result.status()
            && Objects.equals(existing.summary(), result.summary())
            && Objects.equals(existing.finalReply(), result.finalReply())
            && Objects.equals(existing.currentNodeKey(), result.currentNodeKey())
            && existing.escalationRequired() == result.escalationRequired()
            && Objects.equals(existing.checkpoint(), result.checkpoint())
            && Objects.equals(existing.humanTask(), result.humanTask())
            && Objects.equals(existing.pauseReason(), result.pauseReason())
            && Objects.equals(existing.latestFailure(), latestFailure)
            && Objects.equals(existing.latestToolOutcome(), result.latestToolOutcome())
            && Objects.equals(existing.loadedSkillResourceVersionIds(), result.loadedSkillResourceVersionIds())
            && Objects.equals(existing.sharedState(), result.sharedState())
            && Objects.equals(existing.agentTurnState(), result.agentTurnState())
            && existing.toolCalls().equals(result.toolCalls())
            && sameNodes(existing.nodes(), result.nodes());
    }

    private static boolean isWorkflowActive(WorkflowStatus status) {
        return switch (status) {
            case DRAFT, RUNNING, WAITING_HUMAN -> true;
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }

    private static boolean sameNodes(List<NodeExecutionDto> existing, List<WorkflowContracts.NodeSnapshot> latest) {
        if (existing.size() != latest.size()) {
            return false;
        }
        for (int index = 0; index < existing.size(); index++) {
            NodeExecutionDto existingNode = existing.get(index);
            WorkflowContracts.NodeSnapshot latestNode = latest.get(index);
            if (!Objects.equals(existingNode.nodeKey(), latestNode.nodeKey())
                || !Objects.equals(existingNode.nodeName(), latestNode.nodeName())
                || existingNode.status() != latestNode.status()
                || !Objects.equals(existingNode.detail(), latestNode.detail())
                || !Objects.equals(existingNode.updatedAt(), latestNode.updatedAt())) {
                return false;
            }
        }
        return true;
    }

    private record PreparedTurn(TaskInstanceDto task, WorkflowInstanceDto workflow, AssistantRunSnapshot assistantSnapshot) {
    }

    private record TurnExecutionResult(TaskInstanceDto task, WorkflowInstanceDto workflow, String reply) {
    }
}
