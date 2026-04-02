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
    private static final Comparator<ResumeInterventionDto> INTERVENTION_ORDER =
        Comparator.comparing(ResumeInterventionDto::createdAt).thenComparing(ResumeInterventionDto::id);
    private static final Comparator<ExternalInteractionEventDto> INTERACTION_EVENT_ORDER =
        Comparator.comparing(ExternalInteractionEventDto::createdAt).thenComparing(ExternalInteractionEventDto::id);

    private final Map<String, TaskInstanceDto> tasks = new LinkedHashMap<>();
    private final Map<String, String> taskSessions = new LinkedHashMap<>();
    private final Map<String, WorkflowInstanceDto> workflows = new LinkedHashMap<>();
    private final Map<String, ConversationSessionDto> sessions = new LinkedHashMap<>();
    private final Map<String, Map<String, ConversationMessageDto>> sessionMessages = new LinkedHashMap<>();
    private final Map<String, ExternalInteractionTaskDto> interactionTasks = new LinkedHashMap<>();
    private final Map<String, Map<String, ExternalInteractionEventDto>> interactionEvents = new LinkedHashMap<>();
    private final Map<String, Map<String, ResumeInterventionDto>> workflowResumeInterventions = new LinkedHashMap<>();

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
        session.messages().forEach(message -> sessionMessages.get(session.id()).put(messageStorageKey(message), message));
    }

    @Override
    public Optional<ExternalInteractionTaskDto> findExternalInteractionTask(String interactionTaskId) {
        ExternalInteractionTaskDto task = interactionTasks.get(interactionTaskId);
        return task == null ? Optional.empty() : Optional.of(hydrateInteractionTask(task));
    }

    @Override
    public Optional<ExternalInteractionTaskDto> findExternalInteractionTaskByProviderReference(String provider, String providerReference) {
        return interactionTasks.values().stream()
            .filter(task -> java.util.Objects.equals(task.provider(), provider)
                && java.util.Objects.equals(task.providerReference(), providerReference))
            .map(this::hydrateInteractionTask)
            .findFirst();
    }

    @Override
    public List<ExternalInteractionEventDto> listExternalInteractionEvents(String interactionTaskId) {
        return interactionEvents.getOrDefault(interactionTaskId, Map.of()).values().stream()
            .sorted(INTERACTION_EVENT_ORDER)
            .toList();
    }

    @Override
    public Optional<ExternalInteractionEventDto> findExternalInteractionEventByDedupeKey(String interactionTaskId, String dedupeKey) {
        if (dedupeKey == null || dedupeKey.isBlank()) {
            return Optional.empty();
        }
        return interactionEvents.getOrDefault(interactionTaskId, Map.of()).values().stream()
            .filter(event -> dedupeKey.equals(event.dedupeKey()))
            .findFirst();
    }

    @Override
    public Optional<ResumeInterventionDto> findPendingResumeIntervention(String workflowInstanceId) {
        return workflowResumeInterventions.getOrDefault(workflowInstanceId, Map.of()).values().stream()
            .filter(intervention -> intervention.status() == ResumeInterventionStatus.PENDING)
            .sorted(INTERVENTION_ORDER.reversed())
            .findFirst();
    }

    @Override
    public List<ResumeInterventionDto> listPendingResumeInterventions() {
        return workflowResumeInterventions.values().stream()
            .flatMap(items -> items.values().stream())
            .filter(intervention -> intervention.status() == ResumeInterventionStatus.PENDING)
            .sorted(INTERVENTION_ORDER)
            .toList();
    }

    @Override
    public void persistProjection(ProjectionPlanDto plan) {
        if (plan.task() != null) {
            tasks.put(plan.task().id(), plan.task());
            if (plan.session() != null) {
                taskSessions.put(plan.task().id(), plan.session().id());
            } else if (!taskSessions.containsKey(plan.task().id())) {
                taskSessions.put(plan.task().id(), null);
            }
        }
        if (plan.workflow() != null) {
            workflows.put(plan.workflow().id(), stripInterventions(plan.workflow()));
        }
        if (plan.session() != null) {
            sessions.put(plan.session().id(), stripMessages(plan.session()));
            sessionMessages.computeIfAbsent(plan.session().id(), ignored -> new LinkedHashMap<>());
        }
        if (plan.session() != null) {
            for (ConversationMessageDto message : plan.conversationMessages()) {
                sessionMessages.get(plan.session().id()).put(messageStorageKey(message), message);
            }
        }
        for (ExternalInteractionTaskDto task : plan.interactionTasks()) {
            interactionTasks.put(task.id(), stripInteractionEvents(task));
        }
        for (ExternalInteractionEventDto event : plan.interactionEvents()) {
            interactionEvents.computeIfAbsent(event.interactionTaskId(), ignored -> new LinkedHashMap<>())
                .put(event.id(), event);
        }
        if (plan.intervention() != null) {
            saveResumeIntervention(plan.intervention());
        }
    }

    @Override
    public void saveResumeIntervention(ResumeInterventionDto intervention) {
        workflowResumeInterventions
            .computeIfAbsent(intervention.workflowInstanceId(), ignored -> new LinkedHashMap<>())
            .put(intervention.id(), intervention);
    }

    private ExternalInteractionTaskDto hydrateInteractionTask(ExternalInteractionTaskDto task) {
        return new ExternalInteractionTaskDto(
            task.id(),
            task.type(),
            task.status(),
            task.sessionId(),
            task.taskId(),
            task.workflowInstanceId(),
            task.sourceMessageKey(),
            task.messageId(),
            task.title(),
            task.instruction(),
            task.provider(),
            task.providerReference(),
            task.launchUrl(),
            task.returnToken(),
            task.returnPath(),
            task.expiresAt(),
            task.latestResult(),
            task.lastEventSource(),
            task.resumedAt(),
            task.createdAt(),
            task.updatedAt(),
            interactionEvents.getOrDefault(task.id(), Map.of()).values().stream().sorted(INTERACTION_EVENT_ORDER).toList()
        );
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
            session.latestResumeTask(),
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
            workflow.currentNodeKey(),
            workflow.escalationRequired(),
            workflow.checkpoint(),
            workflow.resumeTask(),
            workflow.pauseReason(),
            workflow.latestFailure(),
            workflow.latestToolOutcome(),
            workflow.resourceAnchors(),
            workflow.nodes(),
            workflow.toolCalls(),
            workflow.modelHits(),
            workflowResumeInterventions.getOrDefault(workflow.id(), Map.of()).values().stream().sorted(INTERVENTION_ORDER).toList(),
            workflow.emittedMessageKeys(),
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
            session.latestResumeTask(),
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
            workflow.currentNodeKey(),
            workflow.escalationRequired(),
            workflow.checkpoint(),
            workflow.resumeTask(),
            workflow.pauseReason(),
            workflow.latestFailure(),
            workflow.latestToolOutcome(),
            List.copyOf(workflow.resourceAnchors()),
            List.copyOf(workflow.nodes()),
            List.copyOf(workflow.toolCalls()),
            List.copyOf(workflow.modelHits()),
            List.of(),
            List.copyOf(workflow.emittedMessageKeys()),
            List.copyOf(workflow.loadedSkillResourceVersionIds()),
            workflow.sharedState(),
            workflow.agentTurnState() == null ? AgentTurnState.empty() : workflow.agentTurnState()
        );
    }

    private ExternalInteractionTaskDto stripInteractionEvents(ExternalInteractionTaskDto task) {
        return new ExternalInteractionTaskDto(
            task.id(),
            task.type(),
            task.status(),
            task.sessionId(),
            task.taskId(),
            task.workflowInstanceId(),
            task.sourceMessageKey(),
            task.messageId(),
            task.title(),
            task.instruction(),
            task.provider(),
            task.providerReference(),
            task.launchUrl(),
            task.returnToken(),
            task.returnPath(),
            task.expiresAt(),
            task.latestResult(),
            task.lastEventSource(),
            task.resumedAt(),
            task.createdAt(),
            task.updatedAt(),
            List.of()
        );
    }

    private String messageStorageKey(ConversationMessageDto message) {
        if (message.workflowInstanceId() != null && message.messageKey() != null) {
            return message.workflowInstanceId() + "::" + message.messageKey();
        }
        return message.id();
    }
}
