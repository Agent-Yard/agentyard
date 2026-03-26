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
import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.contracts.runtime.WorkflowContracts.AgentExecutionPolicySnapshot;
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
import com.lynxus.contracts.runtime.WorkflowContracts.SkillConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.TaskStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolOperationConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolOutcomeSummary;
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
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RuntimeService {
    private final AssistantRunWorkflowGateway workflowGateway;
    private final CatalogService catalogService;
    private final KnowledgeService knowledgeService;
    private final List<TaskInstanceDto> tasks = new ArrayList<>();
    private final List<WorkflowInstanceDto> workflows = new ArrayList<>();
    private final List<ConversationSessionDto> sessions = new ArrayList<>();

    public RuntimeService(AssistantRunWorkflowGateway workflowGateway, CatalogService catalogService) {
        this(workflowGateway, catalogService, catalogService.knowledgeService());
    }

    @Autowired
    RuntimeService(AssistantRunWorkflowGateway workflowGateway, CatalogService catalogService, KnowledgeService knowledgeService) {
        this.workflowGateway = workflowGateway;
        this.catalogService = catalogService;
        this.knowledgeService = knowledgeService;
    }

    public List<TaskInstanceDto> listTasks() {
        refreshRunningWorkflows();
        return tasks.stream().sorted(Comparator.comparing(TaskInstanceDto::createdAt).reversed()).toList();
    }

    public List<WorkflowInstanceDto> listWorkflows() {
        refreshRunningWorkflows();
        return workflows.stream()
            .sorted(Comparator.comparing(WorkflowInstanceDto::updatedAt, Comparator.reverseOrder())
                .thenComparing(WorkflowInstanceDto::createdAt, Comparator.reverseOrder()))
            .toList();
    }

    public List<ConversationSessionDto> listSessions() {
        refreshRunningWorkflows();
        return sessions.stream().sorted(Comparator.comparing(ConversationSessionDto::updatedAt).reversed()).toList();
    }

    public ConversationSessionDto getSession(String sessionId) {
        refreshRunningWorkflows();
        return sessions.stream().filter(item -> item.id().equals(sessionId)).findFirst().orElseThrow();
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
            List.of()
        );
        sessions.add(session);

        if (request.openingMessage() != null && !request.openingMessage().isBlank()) {
            return sendMessage(session.id(), new ConversationMessageRequest(request.requester(), request.openingMessage()));
        }

        return session;
    }

    public ConversationSessionDto sendMessage(String sessionId, ConversationMessageRequest request) {
        ConversationSessionDto existing = getSession(sessionId);
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

        TurnExecutionResult turn = executeTurn(
            sessionId,
            scenario.id(),
            assistant,
            request.requester(),
            request.message(),
            messages,
            existing.loadedSkillResourceVersionIds()
        );
        messages.add(new ConversationMessageDto(
            nextId("msg"),
            sessionId,
            "ASSISTANT",
            "ASSISTANT",
            assistant.id(),
            assistant.name(),
            turn.reply(),
            Instant.now(),
            turn.task().id(),
            turn.workflow().id()
        ));

        ConversationSessionDto updated = new ConversationSessionDto(
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
            turn.task().id(),
            turn.workflow().id(),
            turn.workflow().latestToolOutcome(),
            turn.workflow().humanTask(),
            turn.workflow().pauseReason(),
            turn.workflow().loadedSkillResourceVersionIds()
        );
        replaceSession(updated);
        return updated;
    }

    public TaskInstanceDto launchTask(TaskLaunchRequest request) {
        ScenarioDto scenario = catalogService.getScenario(request.scenarioId());
        AssistantDto assistant = resolveAssistant(scenario, request.assistantId());
        return executeTurn(null, request.scenarioId(), assistant, request.requester(), request.question(), List.of(), List.of()).task();
    }

    public WorkflowInstanceDto getWorkflow(String workflowId) {
        refreshRunningWorkflows();
        return workflows.stream().filter(item -> item.id().equals(workflowId)).findFirst().orElseThrow();
    }

    public WorkflowInstanceDto handleHumanAction(String workflowId, HumanActionRequest request) {
        WorkflowInstanceDto existing = getWorkflow(workflowId);
        HumanInterventionDto intervention = new HumanInterventionDto(
            nextId("human"),
            workflowId,
            request.action(),
            request.operatorId() == null || request.operatorId().isBlank() ? "u-demo-operator" : request.operatorId(),
            request.comment(),
            Instant.now()
        );
        WorkflowResult result = workflowGateway.submitHumanActionAndAwaitResult(
            workflowId,
            new HumanAction(
                request.action(),
                request.comment(),
                intervention.operator(),
                request.attributes() == null ? Map.of() : Map.copyOf(request.attributes())
            )
        );
        WorkflowInstanceDto updated = mergeWorkflowResult(existing, result, append(existing.interventions(), intervention));
        replaceWorkflow(updated);
        syncTaskWithWorkflow(updated);
        syncSessionsForWorkflow(updated, intervention);
        return updated;
    }

    private TurnExecutionResult executeTurn(
        String sessionId,
        String scenarioId,
        AssistantDto assistant,
        String requester,
        String message,
        List<ConversationMessageDto> currentMessages,
        List<String> loadedSkillResourceVersionIds
    ) {
        String taskId = nextId("task");
        String workflowId = nextId("wf");
        Instant now = Instant.now();
        AssistantRunSnapshot assistantSnapshot = buildAssistantSnapshot(assistant);
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
        tasks.add(task);

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
            resourceAnchors,
            List.of(node(workflowId, "workflow-submitted", "流程提交", NodeStatus.RUNNING, "已提交到 Temporal 工作流队列")),
            List.of(),
            List.of(),
            List.of()
        );
        workflows.add(initialWorkflow);

        WorkflowResult result;
        try {
            result = workflowGateway.startAndAwaitFirstResult(new WorkflowStartRequest(
                taskId,
                workflowId,
                scenarioId,
                message,
                requester,
                buildSessionContext(sessionId, requester, message, currentMessages, loadedSkillResourceVersionIds),
                assistantSnapshot
            ));
        } catch (RuntimeException error) {
            String failureDetail = describeFailure(error);
            WorkflowInstanceDto failedWorkflow = new WorkflowInstanceDto(
                workflowId,
                taskId,
                assistant.id(),
                assistant.name(),
                assistantSnapshot.assistantReleaseVersion(),
                now,
                Instant.now(),
                WorkflowStatus.FAILED,
                "流程执行失败：" + failureDetail,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                resourceAnchors,
                List.of(node(workflowId, "workflow-failed", "流程执行失败", NodeStatus.FAILED, failureDetail)),
                List.of(),
                List.of(),
                List.of()
            );
            replaceWorkflow(failedWorkflow);
            TaskInstanceDto failedTask = updateTaskStatus(task, TaskStatus.FAILED);
            return new TurnExecutionResult(failedTask, failedWorkflow, failedWorkflow.summary());
        }

        WorkflowInstanceDto workflow = mergeWorkflowResult(initialWorkflow, result, List.of());
        replaceWorkflow(workflow);
        TaskInstanceDto updatedTask = updateTaskStatus(task, toTaskStatus(result.status()));
        return new TurnExecutionResult(updatedTask, workflow, firstNonBlank(result.finalReply(), result.summary()));
    }

    private WorkflowInstanceDto mergeWorkflowResult(
        WorkflowInstanceDto existing,
        WorkflowResult result,
        List<HumanInterventionDto> interventions
    ) {
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
            result.latestToolOutcome(),
            existing.resourceAnchors(),
            result.nodes().stream()
                .map(node -> new NodeExecutionDto(nextId("node"), existing.id(), node.nodeKey(), node.nodeName(), node.status(), node.detail(), node.updatedAt()))
                .toList(),
            result.toolCalls(),
            interventions,
            result.loadedSkillResourceVersionIds()
        );
    }

    private void refreshRunningWorkflows() {
        List<WorkflowInstanceDto> runningWorkflows = workflows.stream()
            .filter(workflow -> workflow.status() == WorkflowStatus.RUNNING)
            .toList();
        for (WorkflowInstanceDto workflow : runningWorkflows) {
            WorkflowResult latest = workflowGateway.currentResult(workflow.id());
            if (latest == null || matchesWorkflowResult(workflow, latest)) {
                continue;
            }
            WorkflowInstanceDto updated = mergeWorkflowResult(workflow, latest, workflow.interventions());
            replaceWorkflow(updated);
            syncTaskWithWorkflow(updated);
            syncSessionsForWorkflowRefresh(workflow, updated);
        }
    }

    private void syncTaskWithWorkflow(WorkflowInstanceDto workflow) {
        TaskInstanceDto task = tasks.stream().filter(item -> item.workflowInstanceId().equals(workflow.id())).findFirst().orElseThrow();
        updateTaskStatus(task, toTaskStatus(workflow.status()));
    }

    private void syncSessionsForWorkflowRefresh(WorkflowInstanceDto previous, WorkflowInstanceDto updated) {
        sessions.replaceAll(session -> refreshSessionForWorkflow(session, previous, updated));
    }

    private void syncSessionsForWorkflow(WorkflowInstanceDto workflow, HumanInterventionDto intervention) {
        sessions.replaceAll(session -> {
            if (!Objects.equals(session.latestWorkflowInstanceId(), workflow.id())) {
                return session;
            }
            List<ConversationMessageDto> messages = new ArrayList<>(session.messages());
            messages.add(new ConversationMessageDto(
                nextId("msg"),
                session.id(),
                "SYSTEM",
                "SYSTEM",
                intervention.operator(),
                "人工处理",
                firstNonBlank(workflow.finalReply(), workflow.summary()),
                Instant.now(),
                workflow.taskId(),
                workflow.id()
            ));
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
                workflow.latestToolOutcome(),
                workflow.humanTask(),
                workflow.pauseReason(),
                workflow.loadedSkillResourceVersionIds()
            );
        });
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
        }
        boolean changed = previous.status() != updated.status()
            || !Objects.equals(previous.summary(), updated.summary())
            || !Objects.equals(previous.finalReply(), updated.finalReply())
            || !Objects.equals(session.latestHumanTask(), updated.humanTask())
            || !Objects.equals(session.latestPauseReason(), updated.pauseReason())
            || !Objects.equals(session.latestToolOutcome(), updated.latestToolOutcome());
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
            updated.loadedSkillResourceVersionIds()
        );
    }

    private AssistantDto resolveAssistant(ScenarioDto scenario, String assistantId) {
        return scenario.assistants().stream()
            .filter(item -> item.id().equals(assistantId))
            .findFirst()
            .orElseThrow();
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
            agent.instructions(),
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
            agent.instructions(),
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
        List<String> loadedSkillResourceVersionIds
    ) {
        return new SessionContext(
            sessionId == null ? "adhoc-session" : sessionId,
            requester,
            latestMessage,
            currentMessages.stream()
                .map(message -> new SessionMessageSnapshot(message.role(), message.senderName(), message.content(), message.createdAt()))
                .toList(),
            loadedSkillResourceVersionIds == null ? List.of() : List.copyOf(loadedSkillResourceVersionIds)
        );
    }

    private TaskInstanceDto updateTaskStatus(TaskInstanceDto task, TaskStatus status) {
        TaskInstanceDto updated = new TaskInstanceDto(
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
        replaceTask(updated);
        return updated;
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

    private void replaceSession(ConversationSessionDto updated) {
        sessions.removeIf(item -> item.id().equals(updated.id()));
        sessions.add(updated);
    }

    private void replaceTask(TaskInstanceDto updated) {
        tasks.removeIf(item -> item.id().equals(updated.id()));
        tasks.add(updated);
    }

    private void replaceWorkflow(WorkflowInstanceDto updated) {
        workflows.removeIf(item -> item.id().equals(updated.id()));
        workflows.add(updated);
    }

    void seedDemoData(boolean executeOpeningMessages) {
        if (!sessions.isEmpty() || !tasks.isEmpty() || !workflows.isEmpty()) {
            return;
        }
        seedSession("scenario-customer-ops", "assistant-customer-ops", "业务用户A", "怎么重置密码？", executeOpeningMessages);
        seedSession("scenario-customer-ops", "assistant-customer-ops", "业务用户B", "客户投诉并要求退款，需要人工处理", executeOpeningMessages);
    }

    private void seedSession(
        String scenarioId,
        String assistantId,
        String requester,
        String openingMessage,
        boolean executeOpeningMessages
    ) {
        createSession(new CreateConversationSessionRequest(
            scenarioId,
            assistantId,
            requester,
            executeOpeningMessages ? openingMessage : null
        ));
    }

    private static NodeExecutionDto node(String workflowId, String key, String name, NodeStatus status, String detail) {
        return new NodeExecutionDto(nextId("node"), workflowId, key, name, status, detail, Instant.now());
    }

    private static int findLatestWorkflowMessageIndex(List<ConversationMessageDto> messages, String workflowId) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (Objects.equals(messages.get(index).workflowInstanceId(), workflowId)) {
                return index;
            }
        }
        return -1;
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
        return existing.status() == result.status()
            && Objects.equals(existing.summary(), result.summary())
            && Objects.equals(existing.finalReply(), result.finalReply())
            && Objects.equals(existing.currentNodeKey(), result.currentNodeKey())
            && existing.escalationRequired() == result.escalationRequired()
            && Objects.equals(existing.checkpoint(), result.checkpoint())
            && Objects.equals(existing.humanTask(), result.humanTask())
            && Objects.equals(existing.pauseReason(), result.pauseReason())
            && Objects.equals(existing.latestToolOutcome(), result.latestToolOutcome())
            && Objects.equals(existing.loadedSkillResourceVersionIds(), result.loadedSkillResourceVersionIds())
            && existing.toolCalls().equals(result.toolCalls())
            && sameNodes(existing.nodes(), result.nodes());
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

    private record TurnExecutionResult(TaskInstanceDto task, WorkflowInstanceDto workflow, String reply) {
    }
}
