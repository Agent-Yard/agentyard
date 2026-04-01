package com.lynxus.platform.runtime;

import static com.lynxus.platform.runtime.RuntimeDtos.*;

import com.lynxus.contracts.runtime.WorkflowContracts.AgentTurnState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryRuntimeRepository implements RuntimeRepository {
    private static final Comparator<ConversationMessageDto> MESSAGE_ORDER =
        Comparator.comparing(ConversationMessageDto::createdAt).thenComparing(ConversationMessageDto::id);
    private static final Comparator<HumanInterventionDto> INTERVENTION_ORDER =
        Comparator.comparing(HumanInterventionDto::createdAt).thenComparing(HumanInterventionDto::id);

    private final Map<String, TaskInstanceDto> tasks = new LinkedHashMap<>();
    private final Map<String, String> taskSessions = new LinkedHashMap<>();
    private final Map<String, WorkflowInstanceDto> workflows = new LinkedHashMap<>();
    private final Map<String, ConversationSessionDto> sessions = new LinkedHashMap<>();
    private final Map<String, Map<String, ConversationMessageDto>> sessionMessages = new LinkedHashMap<>();
    private final Map<String, Map<String, HumanInterventionDto>> workflowInterventions = new LinkedHashMap<>();

    @Override
    public List<TaskInstanceDto> listTasks() {
        return new ArrayList<>(tasks.values());
    }

    @Override
    public Optional<TaskInstanceDto> findTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public Optional<String> findTaskSessionId(String taskId) {
        return Optional.ofNullable(taskSessions.get(taskId));
    }

    @Override
    public Optional<TaskInstanceDto> findTaskByWorkflowInstanceId(String workflowInstanceId) {
        return tasks.values().stream()
            .filter(task -> task.workflowInstanceId().equals(workflowInstanceId))
            .findFirst();
    }

    public List<WorkflowInstanceDto> listWorkflows() {
        return workflows.values().stream().map(this::hydrateWorkflow).toList();
    }

    @Override
    public List<WorkflowInstanceDto> listActiveWorkflows() {
        return workflows.values().stream()
            .filter(workflow -> switch (workflow.status()) {
                case COMPLETED, FAILED, CANCELLED -> false;
                default -> true;
            })
            .map(this::hydrateWorkflow)
            .toList();
    }

    @Override
    public Optional<WorkflowInstanceDto> findWorkflow(String workflowId) {
        WorkflowInstanceDto workflow = workflows.get(workflowId);
        return workflow == null ? Optional.empty() : Optional.of(hydrateWorkflow(workflow));
    }

    @Override
    public List<ConversationSessionDto> listSessions() {
        return sessions.values().stream().map(this::hydrateSession).toList();
    }

    @Override
    public Optional<ConversationSessionDto> findSession(String sessionId) {
        ConversationSessionDto session = sessions.get(sessionId);
        return session == null ? Optional.empty() : Optional.of(hydrateSession(session));
    }

    @Override
    public void saveSession(ConversationSessionDto session) {
        sessions.put(session.id(), stripMessages(session));
        sessionMessages.computeIfAbsent(session.id(), ignored -> new LinkedHashMap<>());
        session.messages().forEach(message -> sessionMessages.get(session.id()).put(message.id(), message));
    }

    @Override
    public Optional<HumanInterventionDto> findPendingIntervention(String workflowInstanceId) {
        return workflowInterventions.getOrDefault(workflowInstanceId, Map.of()).values().stream()
            .filter(intervention -> intervention.status() == HumanInterventionStatus.PENDING)
            .sorted(INTERVENTION_ORDER.reversed())
            .findFirst();
    }

    @Override
    public List<HumanInterventionDto> listPendingInterventions() {
        return workflowInterventions.values().stream()
            .flatMap(items -> items.values().stream())
            .filter(intervention -> intervention.status() == HumanInterventionStatus.PENDING)
            .sorted(INTERVENTION_ORDER)
            .toList();
    }

    @Override
    public void persistProjection(
        TaskInstanceDto task,
        WorkflowInstanceDto workflow,
        ConversationSessionDto session,
        HumanInterventionDto intervention
    ) {
        tasks.put(task.id(), task);
        if (session != null) {
            taskSessions.put(task.id(), session.id());
            saveSession(session);
        } else if (!taskSessions.containsKey(task.id())) {
            taskSessions.put(task.id(), null);
        }
        workflows.put(workflow.id(), stripInterventions(workflow));
        if (intervention != null) {
            saveHumanIntervention(intervention);
        }
    }

    @Override
    public void saveHumanIntervention(HumanInterventionDto intervention) {
        workflowInterventions
            .computeIfAbsent(intervention.workflowInstanceId(), ignored -> new LinkedHashMap<>())
            .put(intervention.id(), intervention);
    }

    private ConversationSessionDto hydrateSession(ConversationSessionDto session) {
        return new ConversationSessionDto(
            session.id(),
            session.scenarioId(),
            session.title(),
            session.customerId(),
            session.assistantId(),
            session.assistantName(),
            session.assistantReleaseVersion(),
            session.createdAt(),
            session.updatedAt(),
            sessionMessages.getOrDefault(session.id(), Map.of()).values().stream().sorted(MESSAGE_ORDER).toList(),
            session.latestTaskId(),
            session.latestWorkflowInstanceId(),
            session.latestToolOutcome(),
            session.latestHumanTask(),
            session.latestPauseReason(),
            session.loadedSkillResourceVersionIds(),
            session.sharedState()
        );
    }

    private WorkflowInstanceDto hydrateWorkflow(WorkflowInstanceDto workflow) {
        return new WorkflowInstanceDto(
            workflow.id(),
            workflow.taskId(),
            workflow.assistantId(),
            workflow.assistantName(),
            workflow.assistantReleaseVersion(),
            workflow.createdAt(),
            workflow.updatedAt(),
            workflow.status(),
            workflow.summary(),
            workflow.finalReply(),
            workflow.currentNodeKey(),
            workflow.escalationRequired(),
            workflow.checkpoint(),
            workflow.humanTask(),
            workflow.pauseReason(),
            workflow.latestFailure(),
            workflow.latestToolOutcome(),
            workflow.resourceAnchors(),
            workflow.nodes(),
            workflow.toolCalls(),
            workflowInterventions.getOrDefault(workflow.id(), Map.of()).values().stream().sorted(INTERVENTION_ORDER).toList(),
            workflow.loadedSkillResourceVersionIds(),
            workflow.sharedState(),
            workflow.agentTurnState() == null ? AgentTurnState.empty() : workflow.agentTurnState()
        );
    }

    private ConversationSessionDto stripMessages(ConversationSessionDto session) {
        return new ConversationSessionDto(
            session.id(),
            session.scenarioId(),
            session.title(),
            session.customerId(),
            session.assistantId(),
            session.assistantName(),
            session.assistantReleaseVersion(),
            session.createdAt(),
            session.updatedAt(),
            List.of(),
            session.latestTaskId(),
            session.latestWorkflowInstanceId(),
            session.latestToolOutcome(),
            session.latestHumanTask(),
            session.latestPauseReason(),
            session.loadedSkillResourceVersionIds(),
            session.sharedState()
        );
    }

    private WorkflowInstanceDto stripInterventions(WorkflowInstanceDto workflow) {
        return new WorkflowInstanceDto(
            workflow.id(),
            workflow.taskId(),
            workflow.assistantId(),
            workflow.assistantName(),
            workflow.assistantReleaseVersion(),
            workflow.createdAt(),
            workflow.updatedAt(),
            workflow.status(),
            workflow.summary(),
            workflow.finalReply(),
            workflow.currentNodeKey(),
            workflow.escalationRequired(),
            workflow.checkpoint(),
            workflow.humanTask(),
            workflow.pauseReason(),
            workflow.latestFailure(),
            workflow.latestToolOutcome(),
            List.copyOf(workflow.resourceAnchors()),
            List.copyOf(workflow.nodes()),
            List.copyOf(workflow.toolCalls()),
            List.of(),
            List.copyOf(workflow.loadedSkillResourceVersionIds()),
            workflow.sharedState(),
            workflow.agentTurnState() == null ? AgentTurnState.empty() : workflow.agentTurnState()
        );
    }
}
