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
import com.lynxus.platform.catalog.CatalogDtos.MemoryPolicyDto;
import com.lynxus.platform.catalog.CatalogDtos.OrchestrationEdgeDto;
import com.lynxus.platform.catalog.CatalogDtos.OrchestrationNodeDto;
import com.lynxus.platform.catalog.CatalogDtos.RagPolicyDto;
import com.lynxus.platform.catalog.CatalogDtos.ResourceDto;
import com.lynxus.platform.catalog.CatalogDtos.ResourceVersionConfigurationDto;
import com.lynxus.platform.catalog.CatalogDtos.ScenarioDto;
import com.lynxus.platform.catalog.CatalogService;
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
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeBaseConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.LlmModelConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.McpConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.NodeStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.PromptTemplateConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.ResourceConfigurationSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.ResourceVersionSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.SessionContext;
import com.lynxus.contracts.runtime.WorkflowContracts.SessionMessageSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.SkillConfig;
import com.lynxus.contracts.runtime.WorkflowContracts.TaskStatus;
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
import org.springframework.stereotype.Service;

@Service
public class RuntimeService {
    private final AssistantRunWorkflowGateway workflowGateway;
    private final CatalogService catalogService;
    private final List<TaskInstanceDto> tasks = new ArrayList<>();
    private final List<WorkflowInstanceDto> workflows = new ArrayList<>();
    private final List<ConversationSessionDto> sessions = new ArrayList<>();

    public RuntimeService(AssistantRunWorkflowGateway workflowGateway, CatalogService catalogService) {
        this.workflowGateway = workflowGateway;
        this.catalogService = catalogService;
    }

    public List<TaskInstanceDto> listTasks() {
        return tasks.stream().sorted(Comparator.comparing(TaskInstanceDto::createdAt).reversed()).toList();
    }

    public List<WorkflowInstanceDto> listWorkflows() {
        return workflows.stream().sorted(Comparator.comparing(WorkflowInstanceDto::id).reversed()).toList();
    }

    public List<ConversationSessionDto> listSessions() {
        return sessions.stream().sorted(Comparator.comparing(ConversationSessionDto::updatedAt).reversed()).toList();
    }

    public ConversationSessionDto getSession(String sessionId) {
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
            null
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

        TurnExecutionResult turn = executeTurn(sessionId, scenario.id(), assistant, request.requester(), request.message(), messages);
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
            turn.workflow().mcpSummary(),
            turn.workflow().humanTask()
        );
        replaceSession(updated);
        return updated;
    }

    public TaskInstanceDto launchTask(TaskLaunchRequest request) {
        ScenarioDto scenario = catalogService.getScenario(request.scenarioId());
        AssistantDto assistant = resolveAssistant(scenario, request.assistantId());
        return executeTurn(null, request.scenarioId(), assistant, request.requester(), request.question(), List.of()).task();
    }

    public WorkflowInstanceDto getWorkflow(String workflowId) {
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
        List<ConversationMessageDto> currentMessages
    ) {
        String taskId = nextId("task");
        String workflowId = nextId("wf");
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
            WorkflowStatus.RUNNING,
            "流程已提交到 Temporal，等待首个运行结果。",
            null,
            null,
            false,
            null,
            null,
            null,
            resourceAnchors,
            List.of(node(workflowId, "workflow-submitted", "流程提交", NodeStatus.RUNNING, "已提交到 Temporal 工作流队列")),
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
                buildSessionContext(sessionId, requester, message, currentMessages),
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
                WorkflowStatus.FAILED,
                "流程执行失败：" + failureDetail,
                null,
                null,
                false,
                null,
                null,
                null,
                resourceAnchors,
                List.of(node(workflowId, "workflow-failed", "流程执行失败", NodeStatus.FAILED, failureDetail)),
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
            result.status(),
            result.summary(),
            result.finalReply(),
            result.currentNodeKey(),
            result.escalationRequired(),
            result.checkpoint(),
            result.humanTask(),
            result.mcpSummary(),
            existing.resourceAnchors(),
            result.nodes().stream()
                .map(node -> new NodeExecutionDto(nextId("node"), existing.id(), node.nodeKey(), node.nodeName(), node.status(), node.detail(), node.updatedAt()))
                .toList(),
            result.toolCalls(),
            interventions
        );
    }

    private void syncTaskWithWorkflow(WorkflowInstanceDto workflow) {
        TaskInstanceDto task = tasks.stream().filter(item -> item.workflowInstanceId().equals(workflow.id())).findFirst().orElseThrow();
        updateTaskStatus(task, toTaskStatus(workflow.status()));
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
                workflow.mcpSummary(),
                workflow.humanTask()
            );
        });
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
                toAssistantPolicySnapshot(release.modelPolicy(), release.ragPolicy(), release.memoryPolicy()),
                release.agents().stream().map(this::toAgentSnapshot).toList(),
                release.resources().stream().map(this::toResourceSnapshot).toList(),
                toGraphSnapshot(release.orchestration())
            );
        }

        AssistantOrchestrationDto orchestration = catalogService.getOrchestration(assistant.id());
        List<ResourceDto> resourceViews = catalogService.summary().resources();
        List<ResourceVersionSnapshot> resourceSnapshots = resourceViews.stream()
            .filter(resource -> isReferencedByAssistant(resource, assistant))
            .map(resource -> toResourceSnapshot(
                new AssistantReleaseResourceDto(
                    resource.id(),
                    resource.name(),
                    resource.type(),
                    resource.effectiveVersion() == null ? resource.latestVersion().id() : resource.effectiveVersion().id(),
                    resource.effectiveVersion() == null ? resource.latestVersion().version() : resource.effectiveVersion().version(),
                    List.of("AD_HOC"),
                    (resource.effectiveVersion() == null ? resource.latestVersion() : resource.effectiveVersion()).configuration()
                )
            ))
            .toList();

        return new AssistantRunSnapshot(
            assistant.id(),
            assistant.name(),
            assistant.version().version(),
            toAssistantPolicySnapshot(assistant.modelPolicy(), assistant.ragPolicy(), assistant.memoryPolicy()),
            assistant.agents().stream()
                .map(agent -> new AgentSnapshot(
                    agent.id(),
                    agent.name(),
                    agent.role(),
                    agent.instructions(),
                    toAgentExecutionPolicySnapshot(agent.executionPolicy()),
                    agent.bindings().stream().map(binding -> binding.resourceVersionId()).toList()
                ))
                .toList(),
            resourceSnapshots,
            toGraphSnapshot(orchestration)
        );
    }

    private boolean isReferencedByAssistant(ResourceDto resource, AssistantDto assistant) {
        if (Objects.equals(assistant.modelPolicy().providerResourceId(), resource.id())
            || Objects.equals(assistant.modelPolicy().promptTemplateResourceId(), resource.id())
            || Objects.equals(assistant.ragPolicy().knowledgeBaseResourceId(), resource.id())) {
            return true;
        }
        return assistant.agents().stream().anyMatch(agent ->
            Objects.equals(agent.executionPolicy().modelResourceId(), resource.id())
                || Objects.equals(agent.executionPolicy().promptTemplateResourceId(), resource.id())
                || Objects.equals(agent.executionPolicy().knowledgeBaseResourceId(), resource.id())
                || agent.executionPolicy().toolResourceIds().contains(resource.id())
                || agent.bindings().stream().anyMatch(binding -> binding.resourceId().equals(resource.id()))
        );
    }

    private AssistantPolicySnapshot toAssistantPolicySnapshot(
        AssistantModelPolicyDto modelPolicy,
        RagPolicyDto ragPolicy,
        MemoryPolicyDto memoryPolicy
    ) {
        return new AssistantPolicySnapshot(
            modelPolicy.providerResourceId(),
            modelPolicy.promptTemplateResourceId(),
            modelPolicy.temperature(),
            modelPolicy.maxTokens(),
            ragPolicy.enabled(),
            ragPolicy.knowledgeBaseResourceId(),
            ragPolicy.topK(),
            memoryPolicy.enabled(),
            memoryPolicy.windowSize()
        );
    }

    private AgentSnapshot toAgentSnapshot(AssistantReleaseAgentDto agent) {
        return new AgentSnapshot(
            agent.agentId(),
            agent.name(),
            agent.role(),
            agent.instructions(),
            toAgentExecutionPolicySnapshot(agent.executionPolicy()),
            agent.bindingResourceVersionIds()
        );
    }

    private AgentExecutionPolicySnapshot toAgentExecutionPolicySnapshot(AgentExecutionPolicyDto policy) {
        return new AgentExecutionPolicySnapshot(
            policy.inheritAssistantDefaults(),
            policy.modelResourceId(),
            policy.promptTemplateResourceId(),
            policy.inlinePrompt(),
            policy.ragEnabled(),
            policy.knowledgeBaseResourceId(),
            policy.memoryWindowSize(),
            policy.toolResourceIds()
        );
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
            configuration.knowledgeBase() == null ? null : new KnowledgeBaseConfig(
                configuration.knowledgeBase().sourceType(),
                configuration.knowledgeBase().sourceLocation(),
                configuration.knowledgeBase().syncMode(),
                configuration.knowledgeBase().retrievalMode(),
                configuration.knowledgeBase().embeddingModel(),
                configuration.knowledgeBase().chunkStrategy(),
                configuration.knowledgeBase().defaultTopK(),
                configuration.knowledgeBase().documentCount()
            ),
            configuration.skill() == null ? null : new SkillConfig(
                configuration.skill().runtime(),
                configuration.skill().endpoint(),
                configuration.skill().method(),
                configuration.skill().authType(),
                configuration.skill().timeoutSeconds(),
                configuration.skill().retryPolicy(),
                configuration.skill().inputSchema(),
                configuration.skill().outputSchema()
            ),
            configuration.mcp() == null ? null : new McpConfig(
                configuration.mcp().serverName(),
                configuration.mcp().transport(),
                configuration.mcp().connectionUri(),
                configuration.mcp().namespace(),
                configuration.mcp().authType(),
                configuration.mcp().heartbeatSeconds(),
                configuration.mcp().exposedTools()
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
            configuration.promptTemplate() == null ? null : new PromptTemplateConfig(
                configuration.promptTemplate().templateType(),
                configuration.promptTemplate().systemPrompt(),
                configuration.promptTemplate().userPromptTemplate(),
                configuration.promptTemplate().responseFormat()
            )
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
        List<ConversationMessageDto> currentMessages
    ) {
        return new SessionContext(
            sessionId == null ? "adhoc-session" : sessionId,
            requester,
            latestMessage,
            currentMessages.stream()
                .map(message -> new SessionMessageSnapshot(message.role(), message.senderName(), message.content(), message.createdAt()))
                .toList()
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

    private static List<HumanInterventionDto> append(List<HumanInterventionDto> items, HumanInterventionDto item) {
        List<HumanInterventionDto> updated = new ArrayList<>(items);
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

    private record TurnExecutionResult(TaskInstanceDto task, WorkflowInstanceDto workflow, String reply) {
    }
}
