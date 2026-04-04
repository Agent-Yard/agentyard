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
import com.lynxus.platform.catalog.CatalogDtos.ResourceDto;
import com.lynxus.platform.catalog.CatalogDtos.ResourceVersionConfigurationDto;
import com.lynxus.platform.catalog.CatalogDtos.ScenarioDto;
import com.lynxus.platform.catalog.CatalogService;
import com.lynxus.platform.knowledge.KnowledgeService;
import com.lynxus.platform.shared.logging.PlatformLogContext;
import com.lynxus.platform.shared.ConflictException;
import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.contracts.runtime.WorkflowContracts.AgentExecutionPolicySnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.AgentTurnState;
import com.lynxus.contracts.runtime.WorkflowContracts.AgentSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.AssistantPolicySnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.AssistantRunSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.ConversationPayloadType;
import com.lynxus.contracts.runtime.WorkflowContracts.ExecutionCheckpoint;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionEventSource;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionEventType;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionResult;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionTask;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionType;
import com.lynxus.contracts.runtime.WorkflowContracts.GraphEdgeSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.GraphNodeSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.GraphSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.ResumeAction;
import com.lynxus.contracts.runtime.WorkflowContracts.HumanNodeConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.ResumeTaskSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.HttpToolProviderConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeBindingSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.LlmModelConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.McpToolProviderConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.ModelHitSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.NodeStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.ResourceConfigurationSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.ResourceVersionSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.ResumeActionType;
import com.lynxus.contracts.runtime.WorkflowContracts.ResumeSource;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class RuntimeService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
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

    public ExternalInteractionTaskDto getExternalInteractionTask(String interactionTaskId) {
        refreshRunningWorkflows();
        return runtimeRepository.findExternalInteractionTask(interactionTaskId).orElseThrow();
    }

    public ConversationSessionDto createSession(CreateConversationSessionRequest request) {
        try (PlatformLogContext.Scope ignored = PlatformLogContext.openBusiness(null, null, request.customerId())) {
            ScenarioDto scenario = catalogService.getScenario(request.scenarioId());
            AssistantDto assistant = resolveAssistant(scenario, request.assistantId());
            Instant now = Instant.now();
            String openingContent = request.openingMessage() == null ? null : payloadContent(request.openingMessage().payloadType(), request.openingMessage().payload());
            ConversationSessionDto session = new ConversationSessionDto(
                nextId("session"),
                scenario.id(),
                summarizeTitle(openingContent),
                request.customerId(),
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

            if (request.openingMessage() != null && openingContent != null && !openingContent.isBlank()) {
                return sendMessage(session.id(), new ConversationMessageRequest(
                    request.customerId(),
                    request.openingMessage().payloadType(),
                    request.openingMessage().payload()
                ));
            }

            return session;
        }
    }

    public ConversationSessionDto sendMessage(String sessionId, ConversationMessageRequest request) {
        try (PlatformLogContext.Scope ignored = PlatformLogContext.openBusiness(sessionId, null, request.customerId())) {
            ConversationSessionDto existing = getSession(sessionId);
            ensureSessionReadyForNewTurn(existing);
            ScenarioDto scenario = catalogService.getScenario(existing.scenarioId());
            AssistantDto assistant = resolveAssistant(scenario, existing.assistantId());
            String requestContent = payloadContent(request.payloadType(), request.payload());

            List<ConversationMessageDto> messages = new ArrayList<>(existing.messages());
            ConversationMessageDto userMessage = conversationMessage(
                nextId("msg"),
                null,
                sessionId,
                "USER",
                "USER",
                "user",
                request.customerId(),
                request.payloadType(),
                request.payload(),
                requestContent,
                Instant.now(),
                null,
                null
            );
            messages.add(userMessage);
            PreparedTurn prepared = prepareTurn(sessionId, scenario.id(), assistant, request.customerId(), requestContent, existing.sharedState());
            ConversationSessionDto startedSession = new ConversationSessionDto(
                existing.id(),
                existing.scenarioId(),
                existing.title(),
                existing.customerId(),
                assistant.id(),
                assistant.name(),
                runtimeReleaseVersion(assistant),
                existing.createdAt(),
                Instant.now(),
                messages,
                prepared.task().id(),
                prepared.workflow().id(),
                prepared.workflow().latestToolOutcome(),
                prepared.workflow().resumeTask(),
                prepared.workflow().pauseReason(),
                existing.loadedSkillResourceVersionIds(),
                existing.sharedState()
            );
            runtimeRepository.persistProjection(projectionPlan(prepared.task(), prepared.workflow(), startedSession, startedSession == null ? List.of() : List.of(userMessage), null));

            try {
                workflowGateway.start(new WorkflowStartRequest(
                    prepared.task().id(),
                    prepared.workflow().id(),
                    scenario.id(),
                    requestContent,
                    request.customerId(),
                    buildSessionContext(
                        sessionId,
                        request.customerId(),
                        toSessionMessageSnapshot(userMessage),
                        messages,
                        existing.loadedSkillResourceVersionIds(),
                        existing.sharedState()
                    ),
                    prepared.assistantSnapshot(),
                    PlatformLogContext.capture(sessionId, prepared.workflow().id(), request.customerId())
                ));
                return startedSession;
            } catch (RuntimeException error) {
                WorkflowInstanceDto workflow = failedWorkflow(
                    prepared,
                    "WORKFLOW_START_SUBMISSION_FAILED",
                    describeFailure(error)
                );
                TaskInstanceDto task = withTaskStatus(prepared.task(), TaskStatus.FAILED);
                ConversationSessionDto failedSession = refreshSessionForWorkflow(startedSession, workflow);
                runtimeRepository.persistProjection(projectionPlan(task, workflow, failedSession, List.of(), null));
                return failedSession;
            }
        }
    }

    public ExternalInteractionTaskDto acknowledgeExternalInteractionReturn(String interactionTaskId, ExternalInteractionReturnRequest request) {
        ExternalInteractionTaskDto existing = getExternalInteractionTask(interactionTaskId);
        if (!Objects.equals(existing.returnToken(), request.returnToken())) {
            throw new IllegalArgumentException("invalid interaction return token");
        }
        return handleInteractionEvent(
            existing,
            ExternalInteractionEventSource.FRONTEND_RETURN,
            ExternalInteractionEventType.RETURNED,
            ExternalInteractionStatus.RETURNED,
            blankToNull(request.providerReference()),
            dedupeKey(
                ExternalInteractionEventSource.FRONTEND_RETURN,
                request.dedupeKey(),
                existing.id(),
                request.returnToken()
            ),
            request.payload(),
            null
        );
    }

    public ExternalInteractionTaskDto receiveExternalInteractionCallback(String provider, ExternalInteractionCallbackRequest request) {
        ExternalInteractionTaskDto existing;
        if (request.taskId() != null && !request.taskId().isBlank()) {
            existing = getExternalInteractionTask(request.taskId());
        } else if (request.providerReference() != null && !request.providerReference().isBlank()) {
            existing = runtimeRepository.findExternalInteractionTaskByProviderReference(provider, request.providerReference()).orElseThrow();
        } else {
            throw new IllegalArgumentException("callback requires taskId or providerReference");
        }
        if (existing.provider() != null && provider != null && !provider.isBlank() && !Objects.equals(existing.provider(), provider)) {
            throw new IllegalArgumentException("callback provider does not match interaction task");
        }
        return handleInteractionEvent(
            existing,
            ExternalInteractionEventSource.PROVIDER_CALLBACK,
            ExternalInteractionEventType.CALLBACK_RECEIVED,
            ExternalInteractionStatus.PROCESSING,
            blankToNull(request.providerReference()),
            dedupeKey(
                ExternalInteractionEventSource.PROVIDER_CALLBACK,
                request.dedupeKey(),
                existing.id(),
                firstNonBlank(blankToNull(request.providerReference()), existing.id())
            ),
            request.payload(),
            request.result()
        );
    }

    public TaskInstanceDto launchTask(TaskLaunchRequest request) {
        try (PlatformLogContext.Scope ignored = PlatformLogContext.openBusiness(null, null, request.customerId())) {
            ScenarioDto scenario = catalogService.getScenario(request.scenarioId());
            AssistantDto assistant = resolveAssistant(scenario, request.assistantId());
            return executeTurn(request.scenarioId(), assistant, request.customerId(), request.question()).task();
        }
    }

    public WorkflowInstanceDto getWorkflow(String workflowId) {
        refreshRunningWorkflows();
        return runtimeRepository.findWorkflow(workflowId).orElseThrow();
    }

    public WorkflowInstanceDto handleResumeAction(String workflowId, ResumeActionRequest request) {
        ConversationSessionDto sessionForContext = findSessionByWorkflowId(workflowId).orElse(null);
        try (PlatformLogContext.Scope ignored = PlatformLogContext.openBusiness(
            sessionForContext == null ? null : sessionForContext.id(),
            workflowId,
            sessionForContext == null ? null : sessionForContext.customerId()
        )) {
            WorkflowInstanceDto existing = getWorkflow(workflowId);
            if (runtimeRepository.findPendingResumeIntervention(workflowId).isPresent()) {
                throw new IllegalStateException("workflow has a pending resume intervention awaiting reconciliation: " + workflowId);
            }
            ResumeActionType actionType = parseResumeActionType(request.type());
            ResumeSource resumeSource = resolveResumeSource(existing);
            if (actionType == ResumeActionType.TERMINATE && resumeSource != ResumeSource.HUMAN) {
                throw new IllegalArgumentException("TERMINATE is only supported for HUMAN resume actions");
            }
            ResumeInterventionDto intervention = new ResumeInterventionDto(
                nextId("resume"),
                workflowId,
                actionType.name(),
                resumeSource.name(),
                request.userId() == null || request.userId().isBlank() ? "system-user" : request.userId(),
                request.comment(),
                request.attributes() == null ? Map.of() : Map.copyOf(request.attributes()),
                ResumeInterventionStatus.PENDING,
                Instant.now(),
                null,
                null
            );
            runtimeRepository.saveResumeIntervention(intervention);
            try {
                workflowGateway.submitResumeAction(
                    workflowId,
                    new ResumeAction(
                        actionType,
                        resumeSource,
                        request.comment(),
                        intervention.userId(),
                        intervention.attributes()
                    )
                );
                WorkflowInstanceDto updated = workflowResuming(existing);
                TaskInstanceDto task = runtimeRepository.findTaskByWorkflowInstanceId(workflowId).orElseThrow();
                TaskInstanceDto updatedTask = withTaskStatus(task, TaskStatus.RUNNING);
                ConversationSessionDto session = findSessionByWorkflowId(workflowId).orElse(null);
                ConversationSessionDto updatedSession = session == null ? null : refreshSessionAfterResumeAction(session, updated);
                runtimeRepository.persistProjection(projectionPlan(updatedTask, updated, updatedSession, List.of(), intervention));
                return updated;
            } catch (RuntimeException error) {
                ResumeInterventionDto failedIntervention = markInterventionFailed(intervention, describeFailure(error));
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
                runtimeRepository.saveResumeIntervention(failedIntervention);
                TaskInstanceDto task = runtimeRepository.findTaskByWorkflowInstanceId(workflowId).orElseThrow();
                ConversationSessionDto session = findSessionByWorkflowId(workflowId).orElse(null);
                runtimeRepository.persistProjection(projectionPlan(task, failedWorkflow, session, List.of(), failedIntervention));
                throw error;
            }
        }
    }

    private TurnExecutionResult executeTurn(String scenarioId, AssistantDto assistant, String customerId, String message) {
        PreparedTurn prepared = prepareTurn(null, scenarioId, assistant, customerId, message, SharedSessionState.empty());
        runtimeRepository.persistProjection(projectionPlan(prepared.task(), prepared.workflow(), null, List.of(), null));

        try {
            workflowGateway.start(new WorkflowStartRequest(
                prepared.task().id(),
                prepared.workflow().id(),
                scenarioId,
                message,
                customerId,
                buildSessionContext(
                    null,
                    customerId,
                    new SessionMessageSnapshot(
                        "USER",
                        customerId,
                        ConversationPayloadType.TEXT,
                        textPayload(message),
                        message,
                        Instant.now()
                    ),
                    List.of(),
                    List.of(),
                    SharedSessionState.empty()
                ),
                prepared.assistantSnapshot(),
                PlatformLogContext.capture(null, prepared.workflow().id(), customerId)
            ));
            return new TurnExecutionResult(prepared.task(), prepared.workflow(), prepared.workflow().summary());
        } catch (RuntimeException error) {
            WorkflowInstanceDto workflow = failedWorkflow(
                prepared,
                "WORKFLOW_START_SUBMISSION_FAILED",
                describeFailure(error)
            );
            TaskInstanceDto task = withTaskStatus(prepared.task(), TaskStatus.FAILED);
            runtimeRepository.persistProjection(projectionPlan(task, workflow, null, List.of(), null));
            return new TurnExecutionResult(task, workflow, workflow.summary());
        }
    }

    private PreparedTurn prepareTurn(
        String sessionId,
        String scenarioId,
        AssistantDto assistant,
        String customerId,
        String message,
        SharedSessionState sharedState
    ) {
        String taskId = nextId("task");
        String workflowId = nextId("wf");
        Instant now = Instant.now();
        ensureAssistantReadyForRuntime(assistant);
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
            customerId,
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
        List<ResumeInterventionDto> resumeInterventions
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
            result.currentNodeKey(),
            result.escalationRequired(),
            mergeCheckpoint(existing.checkpoint(), result.checkpoint()),
            result.resumeTask(),
            result.pauseReason(),
            latestFailure,
            result.latestToolOutcome(),
            existing.resourceAnchors(),
            result.nodes().stream()
                .map(node -> new NodeExecutionDto(nextId("node"), existing.id(), node.nodeKey(), node.nodeName(), node.status(), node.detail(), node.updatedAt()))
                .toList(),
            result.toolCalls(),
            result.modelHits(),
            resumeInterventions,
            normalizeOutputMessageKeys(result.outputMessages()),
            result.loadedSkillResourceVersionIds(),
            sharedStateOrEmpty(result.sharedState()),
            result.agentTurnState() == null ? AgentTurnState.empty() : result.agentTurnState()
        );
    }

    private void refreshRunningWorkflows() {
        reconcilePendingResumeInterventions();
        List<WorkflowInstanceDto> runningWorkflows = runtimeRepository.listActiveWorkflows();
        for (WorkflowInstanceDto workflow : runningWorkflows) {
            if (runtimeRepository.findPendingResumeIntervention(workflow.id()).isPresent()) {
                continue;
            }
            WorkflowResult latest = workflowGateway.currentResult(workflow.id());
            if (latest == null || matchesWorkflowResult(workflow, latest)) {
                continue;
            }
            WorkflowInstanceDto updated = mergeWorkflowResult(workflow, latest, workflow.resumeInterventions());
            TaskInstanceDto task = runtimeRepository.findTaskByWorkflowInstanceId(workflow.id()).orElseThrow();
            TaskInstanceDto updatedTask = withTaskStatus(task, toTaskStatus(updated.status()));
            ConversationSessionDto session = findSessionByWorkflowId(workflow.id()).orElse(null);
            ProjectionRefresh refreshed = reconcileWorkflowProjection(task, session, updated, latest.outputMessages());
            runtimeRepository.persistProjection(projectionPlan(updatedTask, refreshed, null));
        }
    }

    private void reconcilePendingResumeInterventions() {
        for (ResumeInterventionDto intervention : runtimeRepository.listPendingResumeInterventions()) {
            WorkflowInstanceDto workflow = runtimeRepository.findWorkflow(intervention.workflowInstanceId()).orElse(null);
            if (workflow == null) {
                continue;
            }
            WorkflowResult latest = workflowGateway.currentResult(workflow.id());
            if (!shouldApplyPendingIntervention(workflow, latest)) {
                continue;
            }
            ResumeInterventionDto appliedIntervention = markInterventionApplied(intervention);
            WorkflowInstanceDto updated = mergeWorkflowResult(workflow, latest, replaceIntervention(workflow.resumeInterventions(), appliedIntervention));
            TaskInstanceDto task = runtimeRepository.findTaskByWorkflowInstanceId(workflow.id()).orElseThrow();
            TaskInstanceDto updatedTask = withTaskStatus(task, toTaskStatus(updated.status()));
            ConversationSessionDto session = findSessionByWorkflowId(workflow.id()).orElse(null);
            ProjectionRefresh refreshed = reconcileWorkflowProjection(task, session, updated, latest.outputMessages());
            runtimeRepository.persistProjection(projectionPlan(updatedTask, refreshed, appliedIntervention));
        }
    }

    private ConversationSessionDto refreshSessionForWorkflow(ConversationSessionDto session, WorkflowInstanceDto updated) {
        if (!Objects.equals(session.latestWorkflowInstanceId(), updated.id())) {
            return session;
        }
        return new ConversationSessionDto(
            session.id(),
            session.scenarioId(),
            session.title(),
            session.customerId(),
            session.assistantId(),
            session.assistantName(),
            session.assistantReleaseVersion(),
            session.createdAt(),
            Instant.now(),
            session.messages(),
            session.latestTaskId(),
            session.latestWorkflowInstanceId(),
            updated.latestToolOutcome(),
            updated.resumeTask(),
            updated.pauseReason(),
            updated.loadedSkillResourceVersionIds(),
            updated.sharedState()
        );
    }

    private ProjectionRefresh reconcileWorkflowProjection(
        TaskInstanceDto task,
        ConversationSessionDto session,
        WorkflowInstanceDto workflow,
        List<WorkflowContracts.WorkflowOutputMessage> outputMessages
    ) {
        if (session == null) {
            return new ProjectionRefresh(workflow, null, List.of(), List.of(), List.of());
        }
        ConversationSessionDto refreshedSession = refreshSessionForWorkflow(session, workflow);
        if (!Objects.equals(refreshedSession.latestWorkflowInstanceId(), workflow.id())) {
            return new ProjectionRefresh(workflow, refreshedSession, List.of(), List.of(), List.of());
        }
        ConversationSessionDto projectedSession = refreshedSession;
        WorkflowInstanceDto projectedWorkflow = workflow;
        List<ConversationMessageDto> conversationMessages = new ArrayList<>();
        List<ExternalInteractionTaskDto> interactionTasks = new ArrayList<>();
        List<ExternalInteractionEventDto> interactionEvents = new ArrayList<>();
        List<WorkflowContracts.WorkflowOutputMessage> emittedMessages = outputMessages == null ? List.of() : outputMessages;
        for (WorkflowContracts.WorkflowOutputMessage message : emittedMessages) {
            int existingIndex = findMessageIndexByKey(projectedSession.messages(), projectedWorkflow.id(), message.messageKey());
            if (existingIndex >= 0) {
                validateProjectedMessage(projectedSession.messages().get(existingIndex), message);
                continue;
            }
            switch (message.payloadType()) {
                case TEXT -> {
                    ConversationMessageDto conversationMessage = workflowOutputConversationMessage(projectedSession, projectedWorkflow, message);
                    projectedSession = appendInteractionMessage(projectedSession, conversationMessage);
                    conversationMessages.add(conversationMessage);
                }
                case EXTERNAL_INTERACTION -> {
                    ProjectionRefresh interactionProjection = createExternalInteractionTask(task, projectedWorkflow, projectedSession, message);
                    projectedWorkflow = interactionProjection.workflow();
                    projectedSession = interactionProjection.session();
                    conversationMessages.addAll(interactionProjection.conversationMessages());
                    interactionTasks.addAll(interactionProjection.interactionTasks());
                    interactionEvents.addAll(interactionProjection.interactionEvents());
                }
            }
        }
        return new ProjectionRefresh(projectedWorkflow, projectedSession, conversationMessages, interactionTasks, interactionEvents);
    }

    private void validateProjectedMessage(ConversationMessageDto stored, WorkflowContracts.WorkflowOutputMessage emitted) {
        if (stored.payloadType() != emitted.payloadType()) {
            throw new IllegalStateException("workflow output message payloadType changed for key: " + emitted.messageKey());
        }
        Map<String, Object> emittedPayload = emitted.payload() == null ? Map.of() : emitted.payload();
        if (emitted.payloadType() == ConversationPayloadType.TEXT && !Objects.equals(stored.payload(), emittedPayload)) {
            throw new IllegalStateException("workflow text output payload changed for key: " + emitted.messageKey());
        }
        if (emitted.payloadType() == ConversationPayloadType.EXTERNAL_INTERACTION
            && !Objects.equals(objectMapValue(stored.payload(), "spec"), objectMapValue(emittedPayload, "spec"))) {
            throw new IllegalStateException("workflow interaction output spec changed for key: " + emitted.messageKey());
        }
    }

    private ConversationMessageDto workflowOutputConversationMessage(
        ConversationSessionDto session,
        WorkflowInstanceDto workflow,
        WorkflowContracts.WorkflowOutputMessage message
    ) {
        Map<String, Object> payload = message.payload() == null ? Map.of() : message.payload();
        return conversationMessage(
            stableId("msg", workflow.id(), message.messageKey()),
            message.messageKey(),
            session.id(),
            "ASSISTANT",
            "ASSISTANT",
            session.assistantId(),
            session.assistantName(),
            message.payloadType(),
            payload,
            payloadContent(message.payloadType(), payload),
            message.createdAt() == null ? Instant.now() : message.createdAt(),
            workflow.taskId(),
            workflow.id()
        );
    }

    private ProjectionRefresh createExternalInteractionTask(
        TaskInstanceDto task,
        WorkflowInstanceDto workflow,
        ConversationSessionDto session,
        WorkflowContracts.WorkflowOutputMessage outputMessage
    ) {
        if (workflow.status() != WorkflowStatus.WAITING_RESUME) {
            throw new IllegalArgumentException("interaction task can only be created for a waiting workflow");
        }
        if (resolveResumeSource(workflow) != ResumeSource.EXTERNAL_SYSTEM) {
            throw new IllegalArgumentException("interaction task requires EXTERNAL_SYSTEM resume source");
        }
        Map<String, Object> spec = objectMapValue(outputMessage.payload(), "spec");
        ExternalInteractionType interactionType = ExternalInteractionType.valueOf(stringValue(spec, "interactionType").trim());
        Instant createdAt = instantValue(spec, "createdAt");
        Instant now = createdAt == null ? (outputMessage.createdAt() == null ? Instant.now() : outputMessage.createdAt()) : createdAt;
        String interactionTaskId = stableId("interaction", workflow.id(), outputMessage.messageKey());
        String messageId = stableId("msg", workflow.id(), outputMessage.messageKey());
        CreateExternalInteractionTaskRequest request = new CreateExternalInteractionTaskRequest(
            session.id(),
            workflow.id(),
            interactionType,
            stringValue(spec, "title"),
            stringValue(spec, "instruction"),
            blankToNull(stringValue(spec, "provider")),
            blankToNull(stringValue(spec, "providerReference")),
            blankToNull(stringValue(spec, "launchUrl")),
            blankToNull(stringValue(spec, "returnPath")),
            instantValue(spec, "expiresAt"),
            blankToNull(stringValue(spec, "primaryActionLabel")),
            parseConversationActions(spec.get("secondaryActions")),
            objectMapValue(spec, "displayHints")
        );
        ExternalInteractionTask taskRecord = new ExternalInteractionTask(
            interactionTaskId,
            request.interactionType(),
            ExternalInteractionStatus.AWAITING_USER_ACTION,
            session.id(),
            task.id(),
            workflow.id(),
            messageId,
            request.title(),
            request.instruction(),
            blankToNull(request.provider()),
            blankToNull(request.providerReference()),
            blankToNull(request.launchUrl()),
            stableToken("return", workflow.id(), outputMessage.messageKey()),
            blankToNull(request.returnPath()),
            request.expiresAt(),
            null,
            ExternalInteractionEventSource.SYSTEM_CREATE,
            null,
            now,
            now
        );
        ExternalInteractionTaskDto interactionTask = new ExternalInteractionTaskDto(
            taskRecord.id(),
            taskRecord.type(),
            taskRecord.status(),
            taskRecord.sessionId(),
            taskRecord.taskId(),
            taskRecord.workflowInstanceId(),
            outputMessage.messageKey(),
            taskRecord.messageId(),
            taskRecord.title(),
            taskRecord.instruction(),
            taskRecord.provider(),
            taskRecord.providerReference(),
            taskRecord.launchUrl(),
            taskRecord.returnToken(),
            taskRecord.returnPath(),
            taskRecord.expiresAt(),
            ExternalInteractionResultDto.fromContract(taskRecord.latestResult()),
            taskRecord.lastEventSource(),
            taskRecord.resumedAt(),
            taskRecord.createdAt(),
            taskRecord.updatedAt(),
            List.of()
        );
        ExternalInteractionEventDto createdEvent = new ExternalInteractionEventDto(
            stableId("interaction-event", workflow.id(), outputMessage.messageKey(), "created"),
            interactionTask.id(),
            ExternalInteractionEventType.CREATED,
            ExternalInteractionEventSource.SYSTEM_CREATE,
            "create:" + workflow.id() + ":" + outputMessage.messageKey(),
            Map.of("status", ExternalInteractionStatus.AWAITING_USER_ACTION.name(), "messageKey", outputMessage.messageKey()),
            null,
            now
        );
        Map<String, Object> interactionPayload = interactionPayload(interactionTask, request);
        ConversationMessageDto interactionMessage = conversationMessage(
            messageId,
            outputMessage.messageKey(),
            session.id(),
            "ASSISTANT",
            "ASSISTANT",
            session.assistantId(),
            session.assistantName(),
            ConversationPayloadType.EXTERNAL_INTERACTION,
            interactionPayload,
            payloadContent(ConversationPayloadType.EXTERNAL_INTERACTION, interactionPayload),
            outputMessage.createdAt() == null ? now : outputMessage.createdAt(),
            task.id(),
            workflow.id()
        );
        ConversationSessionDto updatedSession = appendInteractionMessage(session, interactionMessage);
        WorkflowInstanceDto updatedWorkflow = withInteractionCheckpoint(workflow, interactionTask.id(), interactionTask.type());
        return new ProjectionRefresh(updatedWorkflow, updatedSession, List.of(interactionMessage), List.of(interactionTask), List.of(createdEvent));
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
                toKnowledgeBindingSnapshot(release.assistantKnowledgeBinding()),
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
            modelPolicy.defaultModelResourceId(),
            resolveReleasedVersionId(resources, modelPolicy.defaultModelResourceId()),
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
            toAgentExecutionPolicySnapshot(agent.executionPolicy(), resources, agent.knowledgeBinding(), agent.skillResourceVersionIds(), agent.toolResourceVersionIds())
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
            policy.knowledgeEnabled(),
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
        captureAdHocEffectiveResource(resolved, resourceViews, assistant.modelPolicy().defaultModelResourceId(), "ASSISTANT_DEFAULT_MODEL");

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
        if (!assistant.knowledgeAccessPolicy().enabled()
            || assistant.knowledgeAccessPolicy().knowledgeBaseId() == null
            || assistant.knowledgeAccessPolicy().knowledgeBaseId().isBlank()) {
            return null;
        }
        return resolveKnowledgeBinding(assistant.knowledgeAccessPolicy().knowledgeBaseId());
    }

    private void ensureAssistantReadyForRuntime(AssistantDto assistant) {
        if (assistant.currentRelease() != null) {
            return;
        }
        if (assistant.modelPolicy() == null
            || assistant.modelPolicy().defaultModelResourceId() == null
            || assistant.modelPolicy().defaultModelResourceId().isBlank()) {
            throw new IllegalStateException("assistant default model must be configured before running an unpublished draft");
        }
    }

    private KnowledgeBindingSnapshotDto resolveAgentKnowledgeBinding(AssistantDto assistant, AgentDto agent) {
        if (!agent.executionPolicy().knowledgeEnabled()) {
            return null;
        }
        if (agent.executionPolicy().inheritAssistantKnowledge()) {
            return resolveAssistantKnowledgeBinding(assistant);
        }
        String knowledgeBaseId = agent.executionPolicy().knowledgeBaseId();
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            throw new IllegalStateException("agent knowledgeBaseId must be configured when knowledgeEnabled=true and inheritAssistantKnowledge=false");
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
        String customerId,
        SessionMessageSnapshot latestMessage,
        List<ConversationMessageDto> currentMessages,
        List<String> loadedSkillResourceVersionIds,
        SharedSessionState sharedState
    ) {
        return new SessionContext(
            sessionId == null ? "adhoc-session" : sessionId,
            customerId,
            latestMessage,
            currentMessages.stream()
                .map(RuntimeService::toSessionMessageSnapshot)
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
            task.customerId(),
            status,
            task.createdAt(),
            task.workflowInstanceId()
        );
    }

    private static TaskStatus toTaskStatus(WorkflowStatus status) {
        return switch (status) {
            case WAITING_RESUME -> TaskStatus.WAITING_RESUME;
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

    private static ConversationMessageDto conversationMessage(
        String id,
        String messageKey,
        String sessionId,
        String role,
        String senderType,
        String senderId,
        String senderName,
        ConversationPayloadType payloadType,
        Map<String, Object> payload,
        String content,
        Instant createdAt,
        String taskId,
        String workflowInstanceId
    ) {
        return new ConversationMessageDto(
            id,
            messageKey,
            sessionId,
            role,
            senderType,
            senderId,
            senderName,
            payloadType,
            payload,
            content == null ? "" : content,
            createdAt,
            taskId,
            workflowInstanceId
        );
    }

    private static SessionMessageSnapshot toSessionMessageSnapshot(ConversationMessageDto message) {
        return new SessionMessageSnapshot(
            message.role(),
            message.senderName(),
            message.payloadType(),
            message.payload(),
            message.content(),
            message.createdAt()
        );
    }

    private static Map<String, Object> textPayload(String text) {
        return Map.of("text", text == null ? "" : text);
    }

    private static Map<String, Object> interactionPayload(
        ExternalInteractionTaskDto task,
        CreateExternalInteractionTaskRequest request
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("spec", interactionSpecPayload(request));
        payload.put("projection", interactionProjectionPayload(task, request.primaryActionLabel(), request.secondaryActions(), request.displayHints()));
        return immutableObjectMap(payload);
    }

    private static Map<String, Object> updatedInteractionPayload(
        ExternalInteractionTaskDto task,
        Map<String, Object> existingPayload
    ) {
        Map<String, Object> spec = objectMapValue(existingPayload, "spec");
        Map<String, Object> existingProjection = objectMapValue(existingPayload, "projection");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("spec", spec);
        payload.put("projection", updatedInteractionProjectionPayload(task, existingProjection));
        return immutableObjectMap(payload);
    }

    private static Map<String, Object> interactionSpecPayload(CreateExternalInteractionTaskRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("interactionType", request.interactionType().name());
        payload.put("title", request.title());
        payload.put("instruction", request.instruction());
        payload.put("provider", request.provider());
        payload.put("providerReference", request.providerReference());
        payload.put("launchUrl", request.launchUrl());
        payload.put("returnPath", request.returnPath());
        payload.put("expiresAt", request.expiresAt() == null ? null : request.expiresAt().toString());
        payload.put("primaryActionLabel", request.primaryActionLabel());
        payload.put("secondaryActions", request.secondaryActions().stream().map(RuntimeService::actionPayload).toList());
        payload.put("displayHints", request.displayHints());
        return immutableObjectMap(payload);
    }

    private static Map<String, Object> interactionProjectionPayload(
        ExternalInteractionTaskDto task,
        String primaryActionLabel,
        List<WorkflowContracts.ConversationAction> secondaryActions,
        Map<String, Object> displayHints
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("interactionTaskId", task.id());
        payload.put("status", task.status().name());
        payload.put("primaryAction", primaryInteractionAction(task, primaryActionLabel));
        payload.put("secondaryActions", secondaryActions == null ? List.of() : secondaryActions.stream().map(RuntimeService::actionPayload).toList());
        payload.put("displayHints", displayHints == null ? Map.of() : displayHints);
        return immutableObjectMap(payload);
    }

    private static Map<String, Object> primaryInteractionAction(ExternalInteractionTaskDto task, String label) {
        if (task.launchUrl() == null || task.launchUrl().isBlank()) {
            return null;
        }
        return Map.of(
            "label", firstNonBlank(label, "打开外部交互"),
            "actionType", "OPEN_URL",
            "url", task.launchUrl(),
            "target", "_blank",
            "parameters", Map.of(
                "interactionTaskId", task.id(),
                "returnToken", task.returnToken()
            ),
            "disabled", task.status() != ExternalInteractionStatus.AWAITING_USER_ACTION
        );
    }

    private static Map<String, Object> updatedInteractionProjectionPayload(
        ExternalInteractionTaskDto task,
        Map<String, Object> existingProjection
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("interactionTaskId", task.id());
        payload.put("status", task.status().name());
        payload.put("primaryAction", updatedPrimaryInteractionAction(task, existingProjection));
        payload.put("secondaryActions", existingProjection.getOrDefault("secondaryActions", List.of()));
        payload.put("displayHints", existingProjection.getOrDefault("displayHints", Map.of()));
        return immutableObjectMap(payload);
    }

    private static Object updatedPrimaryInteractionAction(ExternalInteractionTaskDto task, Map<String, Object> existingProjection) {
        if (existingProjection == null || !(existingProjection.get("primaryAction") instanceof Map<?, ?> action)) {
            return primaryInteractionAction(task, null);
        }
        Map<String, Object> updated = new LinkedHashMap<>();
        updated.putAll((Map<String, Object>) action);
        updated.put("disabled", task.status() != ExternalInteractionStatus.AWAITING_USER_ACTION);
        return immutableObjectMap(updated);
    }

    private static Map<String, Object> actionPayload(WorkflowContracts.ConversationAction action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("label", action.label());
        payload.put("actionType", action.actionType());
        payload.put("url", action.url());
        payload.put("target", action.target());
        payload.put("parameters", action.parameters());
        payload.put("disabled", action.disabled());
        return Map.copyOf(payload);
    }

    private static String payloadContent(ConversationPayloadType payloadType, Map<String, Object> payload) {
        if (payloadType == null) {
            return "";
        }
        return switch (payloadType) {
            case TEXT -> stringValue(payload, "text").trim();
            case EXTERNAL_INTERACTION -> summarizeExternalInteractionPayload(payload);
        };
    }

    private static String summarizeExternalInteractionPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        Map<String, Object> spec = objectMapValue(payload, "spec");
        Map<String, Object> projection = objectMapValue(payload, "projection");
        String title = stringValue(spec, "title").trim();
        String description = stringValue(spec, "instruction").trim();
        String status = stringValue(projection, "status").trim();
        String base = firstNonBlank(title, description);
        if (base == null) {
            return status;
        }
        return status == null || status.isBlank() ? base : base + " [" + status + "]";
    }

    private static Map<String, Object> objectMapValue(Map<String, Object> payload, String key) {
        if (payload == null || !(payload.get(key) instanceof Map<?, ?> value)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((nestedKey, nestedValue) -> result.put(String.valueOf(nestedKey), nestedValue));
        return immutableObjectMap(result);
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Instant instantValue(Map<String, Object> payload, String key) {
        String value = stringValue(payload, key).trim();
        if (value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }

    private static List<WorkflowContracts.ConversationAction> parseConversationActions(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<WorkflowContracts.ConversationAction> actions = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> actionPayload)) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            actionPayload.forEach((key, nestedValue) -> payload.put(String.valueOf(key), nestedValue));
            actions.add(new WorkflowContracts.ConversationAction(
                stringValue(payload, "label"),
                stringValue(payload, "actionType"),
                blankToNull(stringValue(payload, "url")),
                blankToNull(stringValue(payload, "target")),
                objectMapValue(payload, "parameters"),
                Boolean.TRUE.equals(payload.get("disabled"))
            ));
        }
        return List.copyOf(actions);
    }

    private static List<String> normalizeOutputMessageKeys(List<WorkflowContracts.WorkflowOutputMessage> outputMessages) {
        if (outputMessages == null || outputMessages.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (WorkflowContracts.WorkflowOutputMessage message : outputMessages) {
            String messageKey = message == null ? null : blankToNull(message.messageKey());
            if (messageKey == null) {
                throw new IllegalStateException("workflow output message key must not be blank");
            }
            if (keys.contains(messageKey)) {
                throw new IllegalStateException("duplicate workflow output message key: " + messageKey);
            }
            keys.add(messageKey);
        }
        return List.copyOf(keys);
    }

    private static String stringValue(Map<String, Object> payload, String key) {
        if (payload == null || !payload.containsKey(key) || payload.get(key) == null) {
            return "";
        }
        return String.valueOf(payload.get(key));
    }

    private static int findMessageIndexByKey(List<ConversationMessageDto> messages, String workflowId, String messageKey) {
        for (int index = 0; index < messages.size(); index++) {
            ConversationMessageDto message = messages.get(index);
            if (Objects.equals(message.workflowInstanceId(), workflowId) && Objects.equals(message.messageKey(), messageKey)) {
                return index;
            }
        }
        return -1;
    }

    private static int findLatestWorkflowMessageIndex(List<ConversationMessageDto> messages, String workflowId) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            ConversationMessageDto message = messages.get(index);
            if (Objects.equals(message.workflowInstanceId(), workflowId)
                && message.payloadType() == ConversationPayloadType.TEXT
                && "ASSISTANT".equals(message.senderType())) {
                return index;
            }
        }
        return -1;
    }

    private static int findMessageIndex(List<ConversationMessageDto> messages, String messageId) {
        for (int index = 0; index < messages.size(); index++) {
            if (Objects.equals(messages.get(index).id(), messageId)) {
                return index;
            }
        }
        return -1;
    }

    private ConversationSessionDto appendInteractionMessage(ConversationSessionDto session, ConversationMessageDto message) {
        List<ConversationMessageDto> messages = new ArrayList<>(session.messages());
        messages.add(message);
        return new ConversationSessionDto(
            session.id(),
            session.scenarioId(),
            session.title(),
            session.customerId(),
            session.assistantId(),
            session.assistantName(),
            session.assistantReleaseVersion(),
            session.createdAt(),
            Instant.now(),
            messages,
            session.latestTaskId(),
            session.latestWorkflowInstanceId(),
            session.latestToolOutcome(),
            session.latestResumeTask(),
            session.latestPauseReason(),
            session.loadedSkillResourceVersionIds(),
            session.sharedState()
        );
    }

    private ConversationSessionDto updateInteractionMessageProjection(ConversationSessionDto session, ExternalInteractionTaskDto task) {
        List<ConversationMessageDto> messages = new ArrayList<>(session.messages());
        int index = findMessageIndex(messages, task.messageId());
        if (index < 0) {
            return session;
        }
        ConversationMessageDto original = messages.get(index);
        Map<String, Object> payload = updatedInteractionPayload(task, original.payload());
        messages.set(index, conversationMessage(
            original.id(),
            original.messageKey(),
            original.sessionId(),
            original.role(),
            original.senderType(),
            original.senderId(),
            original.senderName(),
            ConversationPayloadType.EXTERNAL_INTERACTION,
            payload,
            payloadContent(ConversationPayloadType.EXTERNAL_INTERACTION, payload),
            original.createdAt(),
            original.taskId(),
            original.workflowInstanceId()
        ));
        return new ConversationSessionDto(
            session.id(),
            session.scenarioId(),
            session.title(),
            session.customerId(),
            session.assistantId(),
            session.assistantName(),
            session.assistantReleaseVersion(),
            session.createdAt(),
            Instant.now(),
            messages,
            session.latestTaskId(),
            session.latestWorkflowInstanceId(),
            session.latestToolOutcome(),
            session.latestResumeTask(),
            session.latestPauseReason(),
            session.loadedSkillResourceVersionIds(),
            session.sharedState()
        );
    }

    private WorkflowInstanceDto withInteractionCheckpoint(
        WorkflowInstanceDto workflow,
        String interactionTaskId,
        ExternalInteractionType interactionType
    ) {
        ExecutionCheckpoint checkpoint = workflow.checkpoint();
        if (checkpoint == null) {
            return workflow;
        }
        WorkflowContracts.ResumeContextSnapshot existingContext = checkpoint.resumeContext();
        WorkflowContracts.ResumeContextSnapshot updatedContext = new WorkflowContracts.ResumeContextSnapshot(
            existingContext == null ? ResumeSource.EXTERNAL_SYSTEM : existingContext.source(),
            existingContext == null ? "EXTERNAL_INTERACTION_REQUIRED" : existingContext.reasonCode(),
            interactionTaskId,
            interactionType.name(),
            existingContext == null ? null : existingContext.timeoutPolicyKey()
        );
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
            workflow.currentNodeKey(),
            workflow.escalationRequired(),
            new ExecutionCheckpoint(
                checkpoint.checkpointId(),
                checkpoint.currentNodeKey(),
                checkpoint.waitingNodeKey(),
                checkpoint.statePayload(),
                updatedContext,
                checkpoint.resumeCount()
            ),
            workflow.resumeTask(),
            workflow.pauseReason(),
            workflow.latestFailure(),
            workflow.latestToolOutcome(),
            workflow.resourceAnchors(),
            workflow.nodes(),
            workflow.toolCalls(),
            workflow.modelHits(),
            workflow.resumeInterventions(),
            workflow.emittedMessageKeys(),
            workflow.loadedSkillResourceVersionIds(),
            workflow.sharedState(),
            workflow.agentTurnState()
        );
    }

    private ExternalInteractionTaskDto handleInteractionEvent(
        ExternalInteractionTaskDto existing,
        ExternalInteractionEventSource source,
        ExternalInteractionEventType eventType,
        ExternalInteractionStatus nextStatus,
        String providerReference,
        String dedupeKey,
        Map<String, Object> payload,
        ExternalInteractionResult result
    ) {
        if (runtimeRepository.findExternalInteractionEventByDedupeKey(existing.id(), dedupeKey).isPresent()) {
            return getExternalInteractionTask(existing.id());
        }

        ConversationSessionDto session = getSession(existing.sessionId());
        WorkflowInstanceDto workflow = getWorkflow(existing.workflowInstanceId());
        TaskInstanceDto task = runtimeRepository.findTaskByWorkflowInstanceId(workflow.id()).orElseThrow();
        Instant now = Instant.now();
        ExternalInteractionTaskDto updatedTask = updateInteractionTask(
            existing,
            nextStatus,
            blankToNull(providerReference),
            result == null ? null : ExternalInteractionResultDto.fromContract(result),
            source,
            null,
            now
        );
        ExternalInteractionEventDto event = new ExternalInteractionEventDto(
            nextId("interaction-event"),
            existing.id(),
            existing.resumedAt() == null ? eventType : ExternalInteractionEventType.IGNORED,
            source,
            dedupeKey,
            payload == null ? Map.of() : payload,
            result == null ? null : ExternalInteractionResultDto.fromContract(result),
            now
        );
        ConversationSessionDto updatedSession = updateInteractionMessageProjection(session, updatedTask);
        runtimeRepository.persistProjection(new ProjectionPlanDto(
            null,
            null,
            updatedSession,
            updatedSession.messages(),
            List.of(updatedTask),
            List.of(event),
            null
        ));

        if (existing.resumedAt() != null) {
            return getExternalInteractionTask(existing.id());
        }

        return submitExternalInteractionResume(updatedTask, task, workflow, updatedSession, payload, result);
    }

    private ExternalInteractionTaskDto submitExternalInteractionResume(
        ExternalInteractionTaskDto interactionTask,
        TaskInstanceDto task,
        WorkflowInstanceDto workflow,
        ConversationSessionDto session,
        Map<String, Object> payload,
        ExternalInteractionResult result
    ) {
        Instant now = Instant.now();
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("interactionTaskId", interactionTask.id());
        attributes.put("callbackSource", interactionTask.lastEventSource().name());
        if (interactionTask.providerReference() != null) {
            attributes.put("providerReference", interactionTask.providerReference());
        }
        if (result != null) {
            attributes.put("resultPayload", writeJson(result));
        }
        if (payload != null && !payload.isEmpty()) {
            attributes.put("eventPayload", writeJson(payload));
        }
        ResumeInterventionDto intervention = new ResumeInterventionDto(
            nextId("resume"),
            workflow.id(),
            ResumeActionType.CONTINUE.name(),
            ResumeSource.EXTERNAL_SYSTEM.name(),
            "system-user",
            "External interaction resumed from " + interactionTask.lastEventSource().name(),
            Map.copyOf(attributes),
            ResumeInterventionStatus.PENDING,
            now,
            null,
            null
        );
        try {
            workflowGateway.submitResumeAction(
                workflow.id(),
                new ResumeAction(
                    ResumeActionType.CONTINUE,
                    ResumeSource.EXTERNAL_SYSTEM,
                    intervention.comment(),
                    intervention.userId(),
                    intervention.attributes()
                )
            );
            WorkflowInstanceDto updatedWorkflow = workflowResuming(workflow);
            TaskInstanceDto updatedTaskProjection = withTaskStatus(task, TaskStatus.RUNNING);
            ConversationSessionDto updatedSession = refreshSessionAfterResumeAction(session, updatedWorkflow);
            ExternalInteractionTaskDto resumedTask = updateInteractionTask(
                interactionTask,
                interactionTask.status(),
                interactionTask.providerReference(),
                interactionTask.latestResult(),
                interactionTask.lastEventSource(),
                now,
                now
            );
            ExternalInteractionEventDto resumeTriggeredEvent = new ExternalInteractionEventDto(
                stableId("interaction-event", workflow.id(), interactionTask.id(), "resume-triggered"),
                interactionTask.id(),
                ExternalInteractionEventType.RESUME_TRIGGERED,
                interactionTask.lastEventSource(),
                "resume:" + interactionTask.id(),
                Map.of("workflowId", workflow.id()),
                interactionTask.latestResult(),
                now
            );
            runtimeRepository.persistProjection(new ProjectionPlanDto(
                updatedTaskProjection,
                updatedWorkflow,
                updatedSession,
                List.of(),
                List.of(resumedTask),
                List.of(resumeTriggeredEvent),
                intervention
            ));
            return getExternalInteractionTask(interactionTask.id());
        } catch (RuntimeException error) {
            ResumeInterventionDto failedIntervention = markInterventionFailed(intervention, describeFailure(error));
            WorkflowInstanceDto failedWorkflow = withLatestFailure(
                workflow,
                new WorkflowFailureSnapshot(
                    WorkflowFailureCategory.RUNTIME_FAILURE,
                    "WORKFLOW_RESUME_SUBMISSION_FAILED",
                    describeFailure(error),
                    describeFailure(error),
                    workflow.currentNodeKey(),
                    null,
                    null,
                    null,
                    now
                )
            );
            runtimeRepository.persistProjection(projectionPlan(task, failedWorkflow, session, List.of(), failedIntervention));
            return getExternalInteractionTask(interactionTask.id());
        }
    }

    private ExternalInteractionTaskDto updateInteractionTask(
        ExternalInteractionTaskDto existing,
        ExternalInteractionStatus status,
        String providerReference,
        ExternalInteractionResultDto latestResult,
        ExternalInteractionEventSource lastEventSource,
        Instant resumedAt,
        Instant updatedAt
    ) {
        return new ExternalInteractionTaskDto(
            existing.id(),
            existing.type(),
            status,
            existing.sessionId(),
            existing.taskId(),
            existing.workflowInstanceId(),
            existing.sourceMessageKey(),
            existing.messageId(),
            existing.title(),
            existing.instruction(),
            existing.provider(),
            coalesce(providerReference, existing.providerReference()),
            existing.launchUrl(),
            existing.returnToken(),
            existing.returnPath(),
            existing.expiresAt(),
            latestResult == null ? existing.latestResult() : latestResult,
            lastEventSource,
            resumedAt == null ? existing.resumedAt() : resumedAt,
            existing.createdAt(),
            updatedAt,
            existing.events()
        );
    }

    private Optional<ConversationSessionDto> findSessionByWorkflowId(String workflowId) {
        return runtimeRepository.listSessions().stream()
            .filter(session -> Objects.equals(session.latestWorkflowInstanceId(), workflowId))
            .findFirst();
    }

    private ConversationSessionDto refreshSessionAfterResumeAction(ConversationSessionDto session, WorkflowInstanceDto workflow) {
        return new ConversationSessionDto(
            session.id(),
            session.scenarioId(),
            session.title(),
            session.customerId(),
            session.assistantId(),
            session.assistantName(),
            session.assistantReleaseVersion(),
            session.createdAt(),
            Instant.now(),
            session.messages(),
            session.latestTaskId(),
            session.latestWorkflowInstanceId(),
            workflow.latestToolOutcome(),
            workflow.resumeTask(),
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
            "已收到恢复动作，流程继续执行中。",
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
                node(workflow.id(), "workflow-resuming", "流程恢复", NodeStatus.RUNNING, "已收到恢复动作，流程继续执行中。")
            ),
            workflow.toolCalls(),
            workflow.modelHits(),
            workflow.resumeInterventions(),
            workflow.emittedMessageKeys(),
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
            workflow.currentNodeKey(),
            workflow.escalationRequired(),
            workflow.checkpoint(),
            workflow.resumeTask(),
            workflow.pauseReason(),
            latestFailure,
            workflow.latestToolOutcome(),
            workflow.resourceAnchors(),
            workflow.nodes(),
            workflow.toolCalls(),
            workflow.modelHits(),
            workflow.resumeInterventions(),
            workflow.emittedMessageKeys(),
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
        return latest.status() != WorkflowStatus.WAITING_RESUME
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
            List.of(),
            prepared.workflow().emittedMessageKeys(),
            prepared.workflow().sharedState(),
            AgentTurnState.empty()
        );
    }

    private ResumeInterventionDto markInterventionApplied(ResumeInterventionDto intervention) {
        return new ResumeInterventionDto(
            intervention.id(),
            intervention.workflowInstanceId(),
            intervention.type(),
            intervention.source(),
            intervention.userId(),
            intervention.comment(),
            intervention.attributes(),
            ResumeInterventionStatus.APPLIED,
            intervention.createdAt(),
            Instant.now(),
            null
        );
    }

    private ResumeInterventionDto markInterventionFailed(ResumeInterventionDto intervention, String failureReason) {
        return new ResumeInterventionDto(
            intervention.id(),
            intervention.workflowInstanceId(),
            intervention.type(),
            intervention.source(),
            intervention.userId(),
            intervention.comment(),
            intervention.attributes(),
            ResumeInterventionStatus.FAILED,
            intervention.createdAt(),
            null,
            failureReason
        );
    }

    private static List<ResumeInterventionDto> replaceIntervention(List<ResumeInterventionDto> items, ResumeInterventionDto updated) {
        List<ResumeInterventionDto> next = new ArrayList<>();
        boolean replaced = false;
        for (ResumeInterventionDto item : items) {
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String coalesce(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private static String dedupeKey(
        ExternalInteractionEventSource source,
        String requestedKey,
        String interactionTaskId,
        String fallbackSeed
    ) {
        String normalizedRequested = blankToNull(requestedKey);
        if (normalizedRequested != null) {
            return normalizedRequested;
        }
        return source.name() + ":" + interactionTaskId + ":" + (fallbackSeed == null ? "default" : fallbackSeed);
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to serialize runtime interaction payload", error);
        }
    }

    private static ResumeActionType parseResumeActionType(String value) {
        return ResumeActionType.valueOf(firstNonBlank(value, "CONTINUE").trim().toUpperCase());
    }

    private static ResumeSource resolveResumeSource(WorkflowInstanceDto workflow) {
        return Optional.ofNullable(workflow.checkpoint())
            .map(ExecutionCheckpoint::resumeContext)
            .map(WorkflowContracts.ResumeContextSnapshot::source)
            .orElseThrow(() -> new IllegalStateException("workflow checkpoint is missing resume context source: " + workflow.id()));
    }

    private static boolean matchesWorkflowResult(WorkflowInstanceDto existing, WorkflowResult result) {
        WorkflowFailureSnapshot latestFailure = result.latestFailure() == null ? existing.latestFailure() : result.latestFailure();
        ExecutionCheckpoint checkpoint = mergeCheckpoint(existing.checkpoint(), result.checkpoint());
        return existing.status() == result.status()
            && Objects.equals(existing.summary(), result.summary())
            && Objects.equals(existing.currentNodeKey(), result.currentNodeKey())
            && existing.escalationRequired() == result.escalationRequired()
            && Objects.equals(existing.checkpoint(), checkpoint)
            && Objects.equals(existing.resumeTask(), result.resumeTask())
            && Objects.equals(existing.pauseReason(), result.pauseReason())
            && Objects.equals(existing.latestFailure(), latestFailure)
            && Objects.equals(existing.latestToolOutcome(), result.latestToolOutcome())
            && Objects.equals(existing.modelHits(), normalizeModelHits(result.modelHits()))
            && Objects.equals(existing.emittedMessageKeys(), normalizeOutputMessageKeys(result.outputMessages()))
            && Objects.equals(existing.loadedSkillResourceVersionIds(), result.loadedSkillResourceVersionIds())
            && Objects.equals(existing.sharedState(), result.sharedState())
            && Objects.equals(existing.agentTurnState(), result.agentTurnState())
            && existing.toolCalls().equals(result.toolCalls())
            && sameNodes(existing.nodes(), result.nodes());
    }

    private static List<ModelHitSnapshot> normalizeModelHits(List<ModelHitSnapshot> modelHits) {
        return modelHits == null ? List.of() : modelHits;
    }

    private static boolean isWorkflowActive(WorkflowStatus status) {
        return switch (status) {
            case DRAFT, RUNNING, WAITING_RESUME -> true;
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }

    private static ExecutionCheckpoint mergeCheckpoint(ExecutionCheckpoint existing, ExecutionCheckpoint latest) {
        if (latest == null) {
            return null;
        }
        if (existing == null || existing.resumeContext() == null || latest.resumeContext() == null) {
            return latest;
        }
        WorkflowContracts.ResumeContextSnapshot latestContext = latest.resumeContext();
        if (latestContext.interactionTaskId() != null || latestContext.interactionType() != null) {
            return latest;
        }
        WorkflowContracts.ResumeContextSnapshot existingContext = existing.resumeContext();
        if (existingContext.interactionTaskId() == null && existingContext.interactionType() == null) {
            return latest;
        }
        return new ExecutionCheckpoint(
            latest.checkpointId(),
            latest.currentNodeKey(),
            latest.waitingNodeKey(),
            latest.statePayload(),
            new WorkflowContracts.ResumeContextSnapshot(
                latestContext.source(),
                latestContext.reasonCode(),
                existingContext.interactionTaskId(),
                existingContext.interactionType(),
                latestContext.timeoutPolicyKey()
            ),
            latest.resumeCount()
        );
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

    private static ProjectionPlanDto projectionPlan(
        TaskInstanceDto task,
        WorkflowInstanceDto workflow,
        ConversationSessionDto session,
        List<ConversationMessageDto> conversationMessages,
        ResumeInterventionDto intervention
    ) {
        return new ProjectionPlanDto(task, workflow, session, conversationMessages, List.of(), List.of(), intervention);
    }

    private static ProjectionPlanDto projectionPlan(
        TaskInstanceDto task,
        ProjectionRefresh refresh,
        ResumeInterventionDto intervention
    ) {
        return new ProjectionPlanDto(
            task,
            refresh.workflow(),
            refresh.session(),
            refresh.conversationMessages(),
            refresh.interactionTasks(),
            refresh.interactionEvents(),
            intervention
        );
    }

    private static String stableId(String prefix, String... parts) {
        return prefix + "-" + UUID.nameUUIDFromBytes(String.join("::", parts).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String stableToken(String prefix, String... parts) {
        return stableId(prefix, parts);
    }

    private record PreparedTurn(TaskInstanceDto task, WorkflowInstanceDto workflow, AssistantRunSnapshot assistantSnapshot) {
    }

    private record ProjectionRefresh(
        WorkflowInstanceDto workflow,
        ConversationSessionDto session,
        List<ConversationMessageDto> conversationMessages,
        List<ExternalInteractionTaskDto> interactionTasks,
        List<ExternalInteractionEventDto> interactionEvents
    ) {
    }

    private record TurnExecutionResult(TaskInstanceDto task, WorkflowInstanceDto workflow, String reply) {
    }
}
