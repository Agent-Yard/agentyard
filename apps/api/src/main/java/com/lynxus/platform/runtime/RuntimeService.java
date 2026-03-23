package com.lynxus.platform.runtime;

import static com.lynxus.platform.runtime.RuntimeDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynxus.platform.catalog.CatalogDtos.AgentDto;
import com.lynxus.platform.catalog.CatalogDtos.AssistantOrchestrationDto;
import com.lynxus.platform.catalog.CatalogDtos.AssistantDto;
import com.lynxus.platform.catalog.CatalogDtos.AssistantReleaseDto;
import com.lynxus.platform.catalog.CatalogDtos.ResourceDto;
import com.lynxus.platform.catalog.CatalogDtos.ScenarioDto;
import com.lynxus.platform.catalog.CatalogService;
import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.contracts.runtime.WorkflowContracts.NodeStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.TaskStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RuntimeService {
    private final KnowledgeQaWorkflowGateway workflowGateway;
    private final CatalogService catalogService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final List<TaskInstanceDto> tasks = new ArrayList<>();
    private final List<WorkflowInstanceDto> workflows = new ArrayList<>();
    private final List<ConversationSessionDto> sessions = new ArrayList<>();

    public RuntimeService(
        KnowledgeQaWorkflowGateway workflowGateway,
        CatalogService catalogService
    ) {
        this.workflowGateway = workflowGateway;
        this.catalogService = catalogService;
        seed();
    }

    public List<TaskInstanceDto> listTasks() {
        return tasks.stream()
            .sorted(Comparator.comparing(TaskInstanceDto::createdAt).reversed())
            .toList();
    }

    public List<WorkflowInstanceDto> listWorkflows() {
        return workflows.stream()
            .sorted(Comparator.comparing(WorkflowInstanceDto::id).reversed())
            .toList();
    }

    public List<ConversationSessionDto> listSessions() {
        return sessions.stream()
            .sorted(Comparator.comparing(ConversationSessionDto::updatedAt).reversed())
            .toList();
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
        messages.add(new ConversationMessageDto(
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
        ));

        TurnExecutionResult turn = executeTurn(scenario.id(), assistant, request.requester(), request.message());
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
            turn.workflow().mcpSummary()
        );
        replaceSession(updated);
        return updated;
    }

    public TaskInstanceDto launchTask(TaskLaunchRequest request) {
        ScenarioDto scenario = catalogService.getScenario(request.scenarioId());
        AssistantDto assistant = scenario.assistants().get(0);
        return executeTurn(request.scenarioId(), assistant, request.requester(), request.question()).task();
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
            "u-demo-operator",
            request.comment(),
            Instant.now()
        );
        WorkflowInstanceDto updated = new WorkflowInstanceDto(
            existing.id(),
            existing.taskId(),
            existing.assistantId(),
            existing.assistantName(),
            existing.assistantReleaseVersion(),
            "TERMINATE".equalsIgnoreCase(request.action()) ? WorkflowStatus.CANCELLED : WorkflowStatus.COMPLETED,
            existing.summary(),
            false,
            existing.mcpSummary(),
            existing.resourceAnchors(),
            completeNodes(existing.nodes(), request),
            append(existing.interventions(), intervention)
        );
        replaceWorkflow(updated);

        TaskInstanceDto task = tasks.stream().filter(item -> item.workflowInstanceId().equals(workflowId)).findFirst().orElseThrow();
        TaskInstanceDto updatedTask = new TaskInstanceDto(
            task.id(),
            task.scenarioId(),
            task.assistantId(),
            task.assistantName(),
            task.assistantReleaseVersion(),
            task.question(),
            task.requester(),
            "TERMINATE".equalsIgnoreCase(request.action()) ? TaskStatus.CANCELLED : TaskStatus.COMPLETED,
            task.createdAt(),
            task.workflowInstanceId()
        );
        replaceTask(updatedTask);
        return updated;
    }

    private TurnExecutionResult executeTurn(String scenarioId, AssistantDto assistant, String requester, String message) {
        String taskId = nextId("task");
        String workflowId = nextId("wf");
        String assistantReleaseVersion = runtimeReleaseVersion(assistant);
        List<String> resourceAnchors = runtimeResourceAnchors(assistant);
        String assistantConfigJson = serializeAssistantConfig(assistant);
        String graphSpecJson = serializeGraphSpec(assistant);
        String sessionContextJson = serializeSessionContext(requester, message);
        TaskInstanceDto task = new TaskInstanceDto(
            taskId,
            scenarioId,
            assistant.id(),
            assistant.name(),
            assistantReleaseVersion,
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
            assistantReleaseVersion,
            WorkflowStatus.RUNNING,
            "流程已提交到 Temporal，等待执行结果。",
            false,
            null,
            resourceAnchors,
            List.of(node(workflowId, "workflow-submitted", "流程提交", NodeStatus.RUNNING, "已提交到 Temporal 工作流队列")),
            List.of()
        );
        workflows.add(initialWorkflow);

        WorkflowContracts.WorkflowResult result;
        try {
            result = workflowGateway.execute(new WorkflowContracts.WorkflowStartRequest(
                taskId,
                workflowId,
                scenarioId,
                assistant.id(),
                assistant.name(),
                assistantReleaseVersion,
                resourceAnchors,
                message,
                requester,
                requester,
                assistantConfigJson,
                graphSpecJson,
                sessionContextJson
            ));
        } catch (RuntimeException error) {
            WorkflowInstanceDto failedWorkflow = new WorkflowInstanceDto(
                workflowId,
                taskId,
                assistant.id(),
                assistant.name(),
                assistantReleaseVersion,
                WorkflowStatus.FAILED,
                "流程执行失败：" + error.getMessage(),
                false,
                null,
                resourceAnchors,
                List.of(node(workflowId, "workflow-failed", "流程执行失败", NodeStatus.FAILED, error.getMessage())),
                List.of()
            );
            replaceWorkflow(failedWorkflow);
            TaskInstanceDto failedTask = new TaskInstanceDto(
                task.id(),
                task.scenarioId(),
                task.assistantId(),
                task.assistantName(),
                task.assistantReleaseVersion(),
                task.question(),
                task.requester(),
                TaskStatus.FAILED,
                task.createdAt(),
                task.workflowInstanceId()
            );
            replaceTask(failedTask);
            return new TurnExecutionResult(failedTask, failedWorkflow, failedWorkflow.summary());
        }

        WorkflowStatus workflowStatus = result.status();
        boolean escalationRequired = result.escalationRequired();
        WorkflowInstanceDto workflow = new WorkflowInstanceDto(
            workflowId,
            taskId,
            assistant.id(),
            assistant.name(),
            assistantReleaseVersion,
            workflowStatus,
            result.summary(),
            escalationRequired,
            result.mcpSummary(),
            resourceAnchors,
            result.nodes().stream()
                .map(node -> new NodeExecutionDto(nextId("node"), workflowId, node.nodeKey(), node.nodeName(), node.status(), node.detail(), node.updatedAt()))
                .toList(),
            escalationRequired
                ? List.of(new HumanInterventionDto(nextId("human"), workflowId, "WAIT_CONFIRM", assistant.id(), "等待人工确认", Instant.now()))
                : List.of()
        );
        replaceWorkflow(workflow);

        TaskStatus taskStatus = switch (workflowStatus) {
            case WAITING_HUMAN -> TaskStatus.WAITING_HUMAN;
            case COMPLETED -> TaskStatus.COMPLETED;
            case FAILED -> TaskStatus.FAILED;
            case CANCELLED -> TaskStatus.CANCELLED;
            default -> TaskStatus.RUNNING;
        };
        TaskInstanceDto updatedTask = new TaskInstanceDto(
            task.id(),
            task.scenarioId(),
            task.assistantId(),
            task.assistantName(),
            task.assistantReleaseVersion(),
            task.question(),
            task.requester(),
            taskStatus,
            task.createdAt(),
            task.workflowInstanceId()
        );
        replaceTask(updatedTask);

        return new TurnExecutionResult(updatedTask, workflow, result.summary());
    }

    private AssistantDto resolveAssistant(ScenarioDto scenario, String assistantId) {
        return scenario.assistants().stream()
            .filter(item -> item.id().equals(assistantId))
            .findFirst()
            .orElseThrow();
    }

    private static String runtimeReleaseVersion(AssistantDto assistant) {
        AssistantReleaseDto release = assistant.currentRelease();
        return release == null ? assistant.version().version() : release.releaseVersion();
    }

    private static List<String> runtimeResourceAnchors(AssistantDto assistant) {
        AssistantReleaseDto release = assistant.currentRelease();
        if (release == null) {
            return List.of("未找到已发布快照，回退到当前助手版本");
        }
        return release.resources().stream()
            .map(item -> item.resourceName() + "@" + item.resourceVersion())
            .toList();
    }

    private static String summarizeTitle(String openingMessage) {
        if (openingMessage == null || openingMessage.isBlank()) {
            return "新会话";
        }
        return openingMessage.length() > 18 ? openingMessage.substring(0, 18) + "..." : openingMessage;
    }

    private String serializeAssistantConfig(AssistantDto assistant) {
        try {
            List<ResourceDto> resources = catalogService.summary().resources();
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("assistantId", assistant.id());
            payload.put("assistantName", assistant.name());
            payload.put("modelPolicy", assistant.modelPolicy());
            payload.put("ragPolicy", assistant.ragPolicy());
            payload.put("memoryPolicy", assistant.memoryPolicy());
            payload.put("defaultModelResource", findResourceView(resources, assistant.modelPolicy().providerResourceId()));
            payload.put("defaultPromptResource", findResourceView(resources, assistant.modelPolicy().promptTemplateResourceId()));
            payload.put("defaultKnowledgeBaseResource", findResourceView(resources, assistant.ragPolicy().knowledgeBaseResourceId()));
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize assistant config", error);
        }
    }

    private String serializeGraphSpec(AssistantDto assistant) {
        try {
            AssistantOrchestrationDto orchestration = catalogService.getOrchestration(assistant.id());
            List<ResourceDto> resources = catalogService.summary().resources();
            List<java.util.Map<String, Object>> nodes = assistant.agents().stream()
                .map(agent -> {
                    java.util.Map<String, Object> node = new java.util.LinkedHashMap<>();
                    node.put("agentId", agent.id());
                    node.put("name", agent.name());
                    node.put("role", agent.role());
                    node.put("instructions", agent.instructions());
                    node.put("executionPolicy", agent.executionPolicy());
                    node.put("modelResource", findResourceView(resources, agent.executionPolicy().modelResourceId()));
                    node.put("promptTemplateResource", findResourceView(resources, agent.executionPolicy().promptTemplateResourceId()));
                    node.put("knowledgeBaseResource", findResourceView(resources, agent.executionPolicy().knowledgeBaseResourceId()));
                    node.put("toolResources", resources.stream()
                        .filter(resource -> agent.executionPolicy().toolResourceIds().contains(resource.id()))
                        .toList());
                    return node;
                })
                .toList();
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("executionMode", orchestration.executionMode());
            payload.put("nodes", nodes);
            payload.put("edges", orchestration.edges());
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize graph spec", error);
        }
    }

    private String serializeSessionContext(String requester, String latestMessage) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                "requester", requester,
                "latestMessage", latestMessage
            ));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize session context", error);
        }
    }

    private static Object findResourceView(List<ResourceDto> resources, String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return null;
        }
        return resources.stream()
            .filter(item -> item.id().equals(resourceId))
            .findFirst()
            .orElse(null);
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

    private void seed() {
        createSession(new CreateConversationSessionRequest(
            "scenario-knowledge-escalation",
            "assistant-knowledge-escalation",
            "业务用户A",
            "怎么重置密码？"
        ));
        createSession(new CreateConversationSessionRequest(
            "scenario-knowledge-escalation",
            "assistant-after-sales",
            "业务用户B",
            "客户申请退款，想了解售后规则"
        ));
    }

    private static NodeExecutionDto node(String workflowId, String key, String name, NodeStatus status, String detail) {
        return new NodeExecutionDto(nextId("node"), workflowId, key, name, status, detail, Instant.now());
    }

    private static List<NodeExecutionDto> completeNodes(List<NodeExecutionDto> nodes, HumanActionRequest request) {
        return nodes.stream()
            .map(node -> node.status() == NodeStatus.WAITING_HUMAN
                ? new NodeExecutionDto(node.id(), node.workflowInstanceId(), node.nodeKey(), node.nodeName(), NodeStatus.COMPLETED, request.comment(), Instant.now())
                : node)
            .toList();
    }

    private static List<HumanInterventionDto> append(List<HumanInterventionDto> items, HumanInterventionDto item) {
        List<HumanInterventionDto> updated = new ArrayList<>(items);
        updated.add(item);
        return updated;
    }

    private static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private record TurnExecutionResult(TaskInstanceDto task, WorkflowInstanceDto workflow, String reply) {
    }
}
