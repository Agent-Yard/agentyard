package com.lynxus.platform.runtime;

import static com.lynxus.platform.runtime.RuntimeDtos.*;

import com.lynxus.contracts.runtime.WorkflowContracts.AgentTurnState;
import com.lynxus.contracts.runtime.WorkflowContracts.ConversationPayloadType;
import com.lynxus.contracts.runtime.WorkflowContracts.ExecutionCheckpoint;
import com.lynxus.contracts.runtime.WorkflowContracts.ModelHitSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.ResumeTaskSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.PauseReasonSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.SharedSessionState;
import com.lynxus.contracts.runtime.WorkflowContracts.TaskStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolInvocationSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolOutcomeSummary;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowFailureSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionEventSource;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionEventType;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.ExternalInteractionType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcRuntimeRepository implements RuntimeRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<NodeExecutionDto>> NODE_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<ToolInvocationSnapshot>> TOOL_CALL_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<ModelHitSnapshot>> MODEL_HIT_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcRuntimeRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper.rebuild()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();
    }

    @Override
    public List<TaskInstanceDto> listTasks() {
        return jdbcTemplate.query(
            """
                select id, scenario_id, assistant_id, assistant_name, assistant_release_version, question, customer_id, status, created_at, workflow_instance_id
                from task_instance
                order by created_at desc, id desc
                """,
            (rs, rowNum) -> mapTask(rs)
        );
    }

    @Override
    public Optional<TaskInstanceDto> findTask(String taskId) {
        return jdbcTemplate.query(
            """
                select id, scenario_id, assistant_id, assistant_name, assistant_release_version, question, customer_id, status, created_at, workflow_instance_id
                from task_instance
                where id = ?
                """,
            (rs, rowNum) -> mapTask(rs),
            taskId
        ).stream().findFirst();
    }

    @Override
    public Optional<String> findTaskSessionId(String taskId) {
        return jdbcTemplate.query(
            "select session_id from task_instance where id = ?",
            (rs, rowNum) -> rs.getString("session_id"),
            taskId
        ).stream().findFirst();
    }

    @Override
    public Optional<TaskInstanceDto> findTaskByWorkflowInstanceId(String workflowInstanceId) {
        return jdbcTemplate.query(
            """
                select id, scenario_id, assistant_id, assistant_name, assistant_release_version, question, customer_id, status, created_at, workflow_instance_id
                from task_instance
                where workflow_instance_id = ?
                """,
            (rs, rowNum) -> mapTask(rs),
            workflowInstanceId
        ).stream().findFirst();
    }

    @Override
    public List<WorkflowInstanceDto> listWorkflows() {
        return hydrateWorkflows(jdbcTemplate.query(
            """
                select id, task_id, assistant_id, assistant_name, assistant_release_version, created_at, updated_at, status, summary, final_reply,
                       current_node_key, escalation_required, checkpoint, resume_task, pause_reason, latest_failure, latest_tool_outcome,
                       resource_anchors, nodes, tool_calls, model_hits, loaded_skill_resource_version_ids, shared_state, agent_turn_state
                from workflow_instance
                order by updated_at desc, created_at desc, id desc
                """,
            (rs, rowNum) -> mapWorkflowRow(rs)
        ));
    }

    @Override
    public List<WorkflowInstanceDto> listActiveWorkflows() {
        return hydrateWorkflows(jdbcTemplate.query(
            """
                select id, task_id, assistant_id, assistant_name, assistant_release_version, created_at, updated_at, status, summary, final_reply,
                       current_node_key, escalation_required, checkpoint, resume_task, pause_reason, latest_failure, latest_tool_outcome,
                       resource_anchors, nodes, tool_calls, model_hits, loaded_skill_resource_version_ids, shared_state, agent_turn_state
                from workflow_instance
                where status not in ('COMPLETED', 'FAILED', 'CANCELLED')
                order by updated_at desc, created_at desc, id desc
                """,
            (rs, rowNum) -> mapWorkflowRow(rs)
        ));
    }

    @Override
    public Optional<WorkflowInstanceDto> findWorkflow(String workflowId) {
        return hydrateWorkflows(jdbcTemplate.query(
            """
                select id, task_id, assistant_id, assistant_name, assistant_release_version, created_at, updated_at, status, summary, final_reply,
                       current_node_key, escalation_required, checkpoint, resume_task, pause_reason, latest_failure, latest_tool_outcome,
                       resource_anchors, nodes, tool_calls, model_hits, loaded_skill_resource_version_ids, shared_state, agent_turn_state
                from workflow_instance
                where id = ?
                """,
            (rs, rowNum) -> mapWorkflowRow(rs),
            workflowId
        )).stream().findFirst();
    }

    @Override
    @Transactional
    public void saveSession(ConversationSessionDto session) {
        upsertSession(session);
        upsertMessages(session.messages());
    }

    @Override
    public Optional<ExternalInteractionTaskDto> findExternalInteractionTask(String interactionTaskId) {
        return hydrateInteractionTasks(jdbcTemplate.query(
            """
                select id, interaction_type, status, session_id, task_id, workflow_instance_id, message_id, title, instruction,
                       provider, provider_reference, launch_url, return_token, return_path, expires_at, latest_result,
                       last_event_source, resumed_at, created_at, updated_at
                from external_interaction_task
                where id = ?
                """,
            (rs, rowNum) -> mapInteractionTaskRow(rs),
            interactionTaskId
        )).stream().findFirst();
    }

    @Override
    public Optional<ExternalInteractionTaskDto> findExternalInteractionTaskByProviderReference(String provider, String providerReference) {
        return hydrateInteractionTasks(jdbcTemplate.query(
            """
                select id, interaction_type, status, session_id, task_id, workflow_instance_id, message_id, title, instruction,
                       provider, provider_reference, launch_url, return_token, return_path, expires_at, latest_result,
                       last_event_source, resumed_at, created_at, updated_at
                from external_interaction_task
                where provider = ? and provider_reference = ?
                """,
            (rs, rowNum) -> mapInteractionTaskRow(rs),
            provider,
            providerReference
        )).stream().findFirst();
    }

    @Override
    public List<ExternalInteractionEventDto> listExternalInteractionEvents(String interactionTaskId) {
        return jdbcTemplate.query(
            """
                select id, interaction_task_id, event_type, event_source, dedupe_key, payload, result, created_at
                from external_interaction_event
                where interaction_task_id = ?
                order by created_at asc, id asc
                """,
            (rs, rowNum) -> mapInteractionEvent(rs),
            interactionTaskId
        );
    }

    @Override
    public Optional<ExternalInteractionEventDto> findExternalInteractionEventByDedupeKey(String interactionTaskId, String dedupeKey) {
        if (dedupeKey == null || dedupeKey.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select id, interaction_task_id, event_type, event_source, dedupe_key, payload, result, created_at
                from external_interaction_event
                where interaction_task_id = ? and dedupe_key = ?
                order by created_at desc, id desc
                limit 1
                """,
            (rs, rowNum) -> mapInteractionEvent(rs),
            interactionTaskId,
            dedupeKey
        ).stream().findFirst();
    }

    @Override
    @Transactional
    public void saveExternalInteractionTask(ExternalInteractionTaskDto task) {
        upsertExternalInteractionTask(task);
    }

    @Override
    @Transactional
    public void saveExternalInteractionEvent(ExternalInteractionEventDto event) {
        upsertExternalInteractionEvent(event);
    }

    @Override
    public List<ConversationSessionDto> listSessions() {
        return hydrateSessions(jdbcTemplate.query(
            """
                select id, scenario_id, title, customer_id, assistant_id, assistant_name, assistant_release_version, created_at, updated_at,
                       latest_task_id, latest_workflow_instance_id, latest_tool_outcome, latest_resume_task, latest_pause_reason,
                       loaded_skill_resource_version_ids, shared_state
                from conversation_session
                order by updated_at desc, id desc
                """,
            (rs, rowNum) -> mapSessionRow(rs)
        ));
    }

    @Override
    public Optional<ConversationSessionDto> findSession(String sessionId) {
        return hydrateSessions(jdbcTemplate.query(
            """
                select id, scenario_id, title, customer_id, assistant_id, assistant_name, assistant_release_version, created_at, updated_at,
                       latest_task_id, latest_workflow_instance_id, latest_tool_outcome, latest_resume_task, latest_pause_reason,
                       loaded_skill_resource_version_ids, shared_state
                from conversation_session
                where id = ?
                """,
            (rs, rowNum) -> mapSessionRow(rs),
            sessionId
        )).stream().findFirst();
    }

    @Override
    public Optional<ResumeInterventionDto> findPendingResumeIntervention(String workflowInstanceId) {
        return jdbcTemplate.query(
            """
                select id, workflow_instance_id, action_type, action_source, user_id, comment, attributes, status, created_at, applied_at, failure_reason
                from resume_intervention
                where workflow_instance_id = ? and status = 'PENDING'
                order by created_at desc, id desc
                limit 1
                """,
            (rs, rowNum) -> mapResumeIntervention(rs),
            workflowInstanceId
        ).stream().findFirst();
    }

    @Override
    public List<ResumeInterventionDto> listPendingResumeInterventions() {
        return jdbcTemplate.query(
            """
                select id, workflow_instance_id, action_type, action_source, user_id, comment, attributes, status, created_at, applied_at, failure_reason
                from resume_intervention
                where status = 'PENDING'
                order by created_at asc, id asc
                """,
            (rs, rowNum) -> mapResumeIntervention(rs)
        );
    }

    @Override
    @Transactional
    public void persistProjection(
        TaskInstanceDto task,
        WorkflowInstanceDto workflow,
        ConversationSessionDto session,
        ResumeInterventionDto intervention
    ) {
        upsertTask(task, session == null ? findTaskSessionId(task.id()).orElse(null) : session.id());
        upsertWorkflow(workflow);
        if (session != null) {
            upsertSession(session);
            upsertMessages(session.messages());
        }
        if (intervention != null) {
            upsertResumeIntervention(intervention);
        }
    }

    @Override
    @Transactional
    public void saveResumeIntervention(ResumeInterventionDto intervention) {
        upsertResumeIntervention(intervention);
    }

    String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to serialize runtime payload", error);
        }
    }

    <T> T readJson(String payload, Class<T> type) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, type);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to deserialize runtime payload", error);
        }
    }

    <T> T readJson(String payload, TypeReference<T> type) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, type);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to deserialize runtime payload", error);
        }
    }

    private TaskInstanceDto mapTask(ResultSet rs) throws SQLException {
        return new TaskInstanceDto(
            rs.getString("id"),
            rs.getString("scenario_id"),
            rs.getString("assistant_id"),
            rs.getString("assistant_name"),
            rs.getString("assistant_release_version"),
            rs.getString("question"),
            rs.getString("customer_id"),
            TaskStatus.valueOf(rs.getString("status")),
            readInstant(rs, "created_at"),
            rs.getString("workflow_instance_id")
        );
    }

    private ResumeInterventionDto mapResumeIntervention(ResultSet rs) throws SQLException {
        return new ResumeInterventionDto(
            rs.getString("id"),
            rs.getString("workflow_instance_id"),
            rs.getString("action_type"),
            rs.getString("action_source"),
            rs.getString("user_id"),
            rs.getString("comment"),
            readJson(rs.getString("attributes"), STRING_MAP),
            ResumeInterventionStatus.valueOf(rs.getString("status")),
            readInstant(rs, "created_at"),
            readNullableInstant(rs, "applied_at"),
            rs.getString("failure_reason")
        );
    }

    private StoredInteractionTaskRow mapInteractionTaskRow(ResultSet rs) throws SQLException {
        return new StoredInteractionTaskRow(
            rs.getString("id"),
            ExternalInteractionType.valueOf(rs.getString("interaction_type")),
            ExternalInteractionStatus.valueOf(rs.getString("status")),
            rs.getString("session_id"),
            rs.getString("task_id"),
            rs.getString("workflow_instance_id"),
            rs.getString("message_id"),
            rs.getString("title"),
            rs.getString("instruction"),
            rs.getString("provider"),
            rs.getString("provider_reference"),
            rs.getString("launch_url"),
            rs.getString("return_token"),
            rs.getString("return_path"),
            readNullableInstant(rs, "expires_at"),
            readJson(rs.getString("latest_result"), ExternalInteractionResultDto.class),
            ExternalInteractionEventSource.valueOf(rs.getString("last_event_source")),
            readNullableInstant(rs, "resumed_at"),
            readInstant(rs, "created_at"),
            readInstant(rs, "updated_at")
        );
    }

    private ExternalInteractionEventDto mapInteractionEvent(ResultSet rs) throws SQLException {
        return new ExternalInteractionEventDto(
            rs.getString("id"),
            rs.getString("interaction_task_id"),
            ExternalInteractionEventType.valueOf(rs.getString("event_type")),
            ExternalInteractionEventSource.valueOf(rs.getString("event_source")),
            rs.getString("dedupe_key"),
            readJson(rs.getString("payload"), OBJECT_MAP),
            readJson(rs.getString("result"), ExternalInteractionResultDto.class),
            readInstant(rs, "created_at")
        );
    }

    private StoredWorkflowRow mapWorkflowRow(ResultSet rs) throws SQLException {
        return new StoredWorkflowRow(
            rs.getString("id"),
            rs.getString("task_id"),
            rs.getString("assistant_id"),
            rs.getString("assistant_name"),
            rs.getString("assistant_release_version"),
            readInstant(rs, "created_at"),
            readInstant(rs, "updated_at"),
            WorkflowStatus.valueOf(rs.getString("status")),
            rs.getString("summary"),
            rs.getString("final_reply"),
            rs.getString("current_node_key"),
            rs.getBoolean("escalation_required"),
            readJson(rs.getString("checkpoint"), ExecutionCheckpoint.class),
            readJson(rs.getString("resume_task"), ResumeTaskSnapshot.class),
            readJson(rs.getString("pause_reason"), PauseReasonSnapshot.class),
            readJson(rs.getString("latest_failure"), WorkflowFailureSnapshot.class),
            readJson(rs.getString("latest_tool_outcome"), ToolOutcomeSummary.class),
            readJson(rs.getString("resource_anchors"), STRING_LIST),
            readJson(rs.getString("nodes"), NODE_LIST),
            readJson(rs.getString("tool_calls"), TOOL_CALL_LIST),
            readJson(rs.getString("model_hits"), MODEL_HIT_LIST),
            readJson(rs.getString("loaded_skill_resource_version_ids"), STRING_LIST),
            readJson(rs.getString("shared_state"), SharedSessionState.class),
            readJson(rs.getString("agent_turn_state"), AgentTurnState.class)
        );
    }

    private StoredSessionRow mapSessionRow(ResultSet rs) throws SQLException {
        return new StoredSessionRow(
            rs.getString("id"),
            rs.getString("scenario_id"),
            rs.getString("title"),
            rs.getString("customer_id"),
            rs.getString("assistant_id"),
            rs.getString("assistant_name"),
            rs.getString("assistant_release_version"),
            readInstant(rs, "created_at"),
            readInstant(rs, "updated_at"),
            rs.getString("latest_task_id"),
            rs.getString("latest_workflow_instance_id"),
            readJson(rs.getString("latest_tool_outcome"), ToolOutcomeSummary.class),
            readJson(rs.getString("latest_resume_task"), ResumeTaskSnapshot.class),
            readJson(rs.getString("latest_pause_reason"), PauseReasonSnapshot.class),
            readJson(rs.getString("loaded_skill_resource_version_ids"), STRING_LIST),
            readJson(rs.getString("shared_state"), SharedSessionState.class)
        );
    }

    private List<ConversationSessionDto> hydrateSessions(List<StoredSessionRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> sessionIds = rows.stream().map(StoredSessionRow::id).toList();
        Map<String, List<ConversationMessageDto>> messagesBySession = loadMessagesBySession(sessionIds);
        return rows.stream()
            .map(row -> new ConversationSessionDto(
                row.id(),
                row.scenarioId(),
                row.title(),
                row.customerId(),
                row.assistantId(),
                row.assistantName(),
                row.assistantReleaseVersion(),
                row.createdAt(),
                row.updatedAt(),
                messagesBySession.getOrDefault(row.id(), List.of()),
                row.latestTaskId(),
                row.latestWorkflowInstanceId(),
                row.latestToolOutcome(),
                row.latestResumeTask(),
                row.latestPauseReason(),
                normalizeStringList(row.loadedSkillResourceVersionIds()),
                row.sharedState() == null ? SharedSessionState.empty() : row.sharedState()
            ))
            .toList();
    }

    private List<WorkflowInstanceDto> hydrateWorkflows(List<StoredWorkflowRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> workflowIds = rows.stream().map(StoredWorkflowRow::id).toList();
        Map<String, List<ResumeInterventionDto>> interventionsByWorkflow = loadInterventionsByWorkflow(workflowIds);
        return rows.stream()
            .map(row -> new WorkflowInstanceDto(
                row.id(),
                row.taskId(),
                row.assistantId(),
                row.assistantName(),
                row.assistantReleaseVersion(),
                row.createdAt(),
                row.updatedAt(),
                row.status(),
                row.summary(),
                row.finalReply(),
                row.currentNodeKey(),
                row.escalationRequired(),
                row.checkpoint(),
                row.resumeTask(),
                row.pauseReason(),
                row.latestFailure(),
                row.latestToolOutcome(),
                normalizeStringList(row.resourceAnchors()),
                row.nodes() == null ? List.of() : row.nodes(),
                row.toolCalls() == null ? List.of() : row.toolCalls(),
                row.modelHits() == null ? List.of() : row.modelHits(),
                interventionsByWorkflow.getOrDefault(row.id(), List.of()),
                normalizeStringList(row.loadedSkillResourceVersionIds()),
                row.sharedState() == null ? SharedSessionState.empty() : row.sharedState(),
                row.agentTurnState() == null ? AgentTurnState.empty() : row.agentTurnState()
            ))
            .toList();
    }

    private Map<String, List<ConversationMessageDto>> loadMessagesBySession(List<String> sessionIds) {
        Map<String, List<ConversationMessageDto>> messagesBySession = new LinkedHashMap<>();
        jdbcTemplate.query(
            """
                select id, session_id, role, sender_type, sender_id, sender_name, payload_type, payload_json, content, created_at, task_id, workflow_instance_id
                from conversation_message
                where session_id in (%s)
                order by session_id, created_at asc, id asc
                """.formatted(placeholders(sessionIds.size())),
            rs -> {
                String sessionId = rs.getString("session_id");
                messagesBySession.computeIfAbsent(sessionId, ignored -> new ArrayList<>()).add(new ConversationMessageDto(
                    rs.getString("id"),
                    sessionId,
                    rs.getString("role"),
                    rs.getString("sender_type"),
                    rs.getString("sender_id"),
                    rs.getString("sender_name"),
                    ConversationPayloadType.valueOf(rs.getString("payload_type")),
                    readJson(rs.getString("payload_json"), OBJECT_MAP),
                    rs.getString("content"),
                    readInstant(rs, "created_at"),
                    rs.getString("task_id"),
                    rs.getString("workflow_instance_id")
                ));
            },
            sessionIds.toArray()
        );
        return messagesBySession;
    }

    private Map<String, List<ResumeInterventionDto>> loadInterventionsByWorkflow(List<String> workflowIds) {
        Map<String, List<ResumeInterventionDto>> interventionsByWorkflow = new LinkedHashMap<>();
        jdbcTemplate.query(
            """
                select id, workflow_instance_id, action_type, action_source, user_id, comment, attributes, status, created_at, applied_at, failure_reason
                from resume_intervention
                where workflow_instance_id in (%s)
                order by workflow_instance_id, created_at asc, id asc
                """.formatted(placeholders(workflowIds.size())),
            rs -> {
                String workflowId = rs.getString("workflow_instance_id");
                interventionsByWorkflow.computeIfAbsent(workflowId, ignored -> new ArrayList<>()).add(mapResumeIntervention(rs));
            },
            workflowIds.toArray()
        );
        return interventionsByWorkflow;
    }

    private List<ExternalInteractionTaskDto> hydrateInteractionTasks(List<StoredInteractionTaskRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, List<ExternalInteractionEventDto>> eventsByTask = loadInteractionEvents(rows.stream().map(StoredInteractionTaskRow::id).toList());
        return rows.stream()
            .map(row -> new ExternalInteractionTaskDto(
                row.id(),
                row.type(),
                row.status(),
                row.sessionId(),
                row.taskId(),
                row.workflowInstanceId(),
                row.messageId(),
                row.title(),
                row.instruction(),
                row.provider(),
                row.providerReference(),
                row.launchUrl(),
                row.returnToken(),
                row.returnPath(),
                row.expiresAt(),
                row.latestResult(),
                row.lastEventSource(),
                row.resumedAt(),
                row.createdAt(),
                row.updatedAt(),
                eventsByTask.getOrDefault(row.id(), List.of())
            ))
            .toList();
    }

    private Map<String, List<ExternalInteractionEventDto>> loadInteractionEvents(List<String> interactionTaskIds) {
        Map<String, List<ExternalInteractionEventDto>> eventsByTask = new LinkedHashMap<>();
        jdbcTemplate.query(
            """
                select id, interaction_task_id, event_type, event_source, dedupe_key, payload, result, created_at
                from external_interaction_event
                where interaction_task_id in (%s)
                order by interaction_task_id, created_at asc, id asc
                """.formatted(placeholders(interactionTaskIds.size())),
            rs -> {
                String interactionTaskId = rs.getString("interaction_task_id");
                eventsByTask.computeIfAbsent(interactionTaskId, ignored -> new ArrayList<>()).add(mapInteractionEvent(rs));
            },
            interactionTaskIds.toArray()
        );
        return eventsByTask;
    }

    private void upsertTask(TaskInstanceDto task, String sessionId) {
        jdbcTemplate.update(
            """
                insert into task_instance (
                    id, session_id, scenario_id, assistant_id, assistant_name, assistant_release_version, question, customer_id, status, workflow_instance_id, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (id) do update set
                    session_id = excluded.session_id,
                    scenario_id = excluded.scenario_id,
                    assistant_id = excluded.assistant_id,
                    assistant_name = excluded.assistant_name,
                    assistant_release_version = excluded.assistant_release_version,
                    question = excluded.question,
                    customer_id = excluded.customer_id,
                    status = excluded.status,
                    workflow_instance_id = excluded.workflow_instance_id,
                    created_at = excluded.created_at
                """,
            task.id(),
            sessionId,
            task.scenarioId(),
            task.assistantId(),
            task.assistantName(),
            task.assistantReleaseVersion(),
            task.question(),
            task.customerId(),
            task.status().name(),
            task.workflowInstanceId(),
            writeTimestamp(task.createdAt())
        );
    }

    private void upsertWorkflow(WorkflowInstanceDto workflow) {
        jdbcTemplate.update(
            """
                insert into workflow_instance (
                    id, task_id, assistant_id, assistant_name, assistant_release_version, created_at, updated_at, status, summary, final_reply,
                    current_node_key, escalation_required, checkpoint, resume_task, pause_reason, latest_failure, latest_tool_outcome,
                    resource_anchors, nodes, tool_calls, model_hits, loaded_skill_resource_version_ids, shared_state, agent_turn_state
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb),
                          cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb),
                          cast(? as jsonb))
                on conflict (id) do update set
                    task_id = excluded.task_id,
                    assistant_id = excluded.assistant_id,
                    assistant_name = excluded.assistant_name,
                    assistant_release_version = excluded.assistant_release_version,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at,
                    status = excluded.status,
                    summary = excluded.summary,
                    final_reply = excluded.final_reply,
                    current_node_key = excluded.current_node_key,
                    escalation_required = excluded.escalation_required,
                    checkpoint = excluded.checkpoint,
                    resume_task = excluded.resume_task,
                    pause_reason = excluded.pause_reason,
                    latest_failure = excluded.latest_failure,
                    latest_tool_outcome = excluded.latest_tool_outcome,
                    resource_anchors = excluded.resource_anchors,
                    nodes = excluded.nodes,
                    tool_calls = excluded.tool_calls,
                    model_hits = excluded.model_hits,
                    loaded_skill_resource_version_ids = excluded.loaded_skill_resource_version_ids,
                    shared_state = excluded.shared_state,
                    agent_turn_state = excluded.agent_turn_state
                """,
            workflow.id(),
            workflow.taskId(),
            workflow.assistantId(),
            workflow.assistantName(),
            workflow.assistantReleaseVersion(),
            writeTimestamp(workflow.createdAt()),
            writeTimestamp(workflow.updatedAt()),
            workflow.status().name(),
            workflow.summary(),
            workflow.finalReply(),
            workflow.currentNodeKey(),
            workflow.escalationRequired(),
            writeJson(workflow.checkpoint()),
            writeJson(workflow.resumeTask()),
            writeJson(workflow.pauseReason()),
            writeJson(workflow.latestFailure()),
            writeJson(workflow.latestToolOutcome()),
            writeJson(workflow.resourceAnchors()),
            writeJson(workflow.nodes()),
            writeJson(workflow.toolCalls()),
            writeJson(workflow.modelHits()),
            writeJson(workflow.loadedSkillResourceVersionIds()),
            writeJson(workflow.sharedState()),
            writeJson(workflow.agentTurnState())
        );
    }

    private void upsertSession(ConversationSessionDto session) {
        jdbcTemplate.update(
            """
                insert into conversation_session (
                    id, scenario_id, title, customer_id, assistant_id, assistant_name, assistant_release_version, created_at, updated_at,
                    latest_task_id, latest_workflow_instance_id, latest_tool_outcome, latest_resume_task, latest_pause_reason,
                    loaded_skill_resource_version_ids, shared_state
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb))
                on conflict (id) do update set
                    scenario_id = excluded.scenario_id,
                    title = excluded.title,
                    customer_id = excluded.customer_id,
                    assistant_id = excluded.assistant_id,
                    assistant_name = excluded.assistant_name,
                    assistant_release_version = excluded.assistant_release_version,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at,
                    latest_task_id = excluded.latest_task_id,
                    latest_workflow_instance_id = excluded.latest_workflow_instance_id,
                    latest_tool_outcome = excluded.latest_tool_outcome,
                    latest_resume_task = excluded.latest_resume_task,
                    latest_pause_reason = excluded.latest_pause_reason,
                    loaded_skill_resource_version_ids = excluded.loaded_skill_resource_version_ids,
                    shared_state = excluded.shared_state
                """,
            session.id(),
            session.scenarioId(),
            session.title(),
            session.customerId(),
            session.assistantId(),
            session.assistantName(),
            session.assistantReleaseVersion(),
            writeTimestamp(session.createdAt()),
            writeTimestamp(session.updatedAt()),
            session.latestTaskId(),
            session.latestWorkflowInstanceId(),
            writeJson(session.latestToolOutcome()),
            writeJson(session.latestResumeTask()),
            writeJson(session.latestPauseReason()),
            writeJson(session.loadedSkillResourceVersionIds()),
            writeJson(session.sharedState())
        );
    }

    private void upsertMessages(List<ConversationMessageDto> messages) {
        for (ConversationMessageDto message : messages) {
            jdbcTemplate.update(
                """
                    insert into conversation_message (
                        id, session_id, role, sender_type, sender_id, sender_name, payload_type, payload_json, content, created_at, task_id, workflow_instance_id
                    ) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?)
                    on conflict (id) do update set
                        session_id = excluded.session_id,
                        role = excluded.role,
                        sender_type = excluded.sender_type,
                        sender_id = excluded.sender_id,
                        sender_name = excluded.sender_name,
                        payload_type = excluded.payload_type,
                        payload_json = excluded.payload_json,
                        content = excluded.content,
                        created_at = excluded.created_at,
                        task_id = excluded.task_id,
                        workflow_instance_id = excluded.workflow_instance_id
                    """,
                message.id(),
                message.sessionId(),
                message.role(),
                message.senderType(),
                message.senderId(),
                message.senderName(),
                message.payloadType().name(),
                writeJson(message.payload()),
                message.content(),
                writeTimestamp(message.createdAt()),
                message.taskId(),
                message.workflowInstanceId()
            );
        }
    }

    private void upsertResumeIntervention(ResumeInterventionDto intervention) {
        jdbcTemplate.update(
            """
                insert into resume_intervention (
                    id, workflow_instance_id, action_type, action_source, user_id, comment, attributes, status, created_at, applied_at, failure_reason
                ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?)
                on conflict (id) do update set
                    workflow_instance_id = excluded.workflow_instance_id,
                    action_type = excluded.action_type,
                    action_source = excluded.action_source,
                    user_id = excluded.user_id,
                    comment = excluded.comment,
                    attributes = excluded.attributes,
                    status = excluded.status,
                    created_at = excluded.created_at,
                    applied_at = excluded.applied_at,
                    failure_reason = excluded.failure_reason
                """,
            intervention.id(),
            intervention.workflowInstanceId(),
            intervention.type(),
            intervention.source(),
            intervention.userId(),
            intervention.comment(),
            writeJson(intervention.attributes() == null ? Map.of() : intervention.attributes()),
            intervention.status().name(),
            writeTimestamp(intervention.createdAt()),
            writeTimestamp(intervention.appliedAt()),
            intervention.failureReason()
        );
    }

    private void upsertExternalInteractionTask(ExternalInteractionTaskDto task) {
        jdbcTemplate.update(
            """
                insert into external_interaction_task (
                    id, interaction_type, status, session_id, task_id, workflow_instance_id, message_id, title, instruction,
                    provider, provider_reference, launch_url, return_token, return_path, expires_at, latest_result,
                    last_event_source, resumed_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?)
                on conflict (id) do update set
                    interaction_type = excluded.interaction_type,
                    status = excluded.status,
                    session_id = excluded.session_id,
                    task_id = excluded.task_id,
                    workflow_instance_id = excluded.workflow_instance_id,
                    message_id = excluded.message_id,
                    title = excluded.title,
                    instruction = excluded.instruction,
                    provider = excluded.provider,
                    provider_reference = excluded.provider_reference,
                    launch_url = excluded.launch_url,
                    return_token = excluded.return_token,
                    return_path = excluded.return_path,
                    expires_at = excluded.expires_at,
                    latest_result = excluded.latest_result,
                    last_event_source = excluded.last_event_source,
                    resumed_at = excluded.resumed_at,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at
                """,
            task.id(),
            task.type().name(),
            task.status().name(),
            task.sessionId(),
            task.taskId(),
            task.workflowInstanceId(),
            task.messageId(),
            task.title(),
            task.instruction(),
            task.provider(),
            task.providerReference(),
            task.launchUrl(),
            task.returnToken(),
            task.returnPath(),
            writeTimestamp(task.expiresAt()),
            writeJson(task.latestResult()),
            task.lastEventSource().name(),
            writeTimestamp(task.resumedAt()),
            writeTimestamp(task.createdAt()),
            writeTimestamp(task.updatedAt())
        );
    }

    private void upsertExternalInteractionEvent(ExternalInteractionEventDto event) {
        jdbcTemplate.update(
            """
                insert into external_interaction_event (
                    id, interaction_task_id, event_type, event_source, dedupe_key, payload, result, created_at
                ) values (?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?)
                on conflict (id) do update set
                    interaction_task_id = excluded.interaction_task_id,
                    event_type = excluded.event_type,
                    event_source = excluded.event_source,
                    dedupe_key = excluded.dedupe_key,
                    payload = excluded.payload,
                    result = excluded.result,
                    created_at = excluded.created_at
                """,
            event.id(),
            event.interactionTaskId(),
            event.eventType().name(),
            event.eventSource().name(),
            event.dedupeKey(),
            writeJson(event.payload()),
            writeJson(event.result()),
            writeTimestamp(event.createdAt())
        );
    }

    private Timestamp writeTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private Instant readNullableInstant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private List<String> normalizeStringList(List<String> value) {
        return value == null ? List.of() : value;
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private record StoredWorkflowRow(
        String id,
        String taskId,
        String assistantId,
        String assistantName,
        String assistantReleaseVersion,
        Instant createdAt,
        Instant updatedAt,
        WorkflowStatus status,
        String summary,
        String finalReply,
        String currentNodeKey,
        boolean escalationRequired,
        ExecutionCheckpoint checkpoint,
        ResumeTaskSnapshot resumeTask,
        PauseReasonSnapshot pauseReason,
        WorkflowFailureSnapshot latestFailure,
        ToolOutcomeSummary latestToolOutcome,
        List<String> resourceAnchors,
        List<NodeExecutionDto> nodes,
        List<ToolInvocationSnapshot> toolCalls,
        List<ModelHitSnapshot> modelHits,
        List<String> loadedSkillResourceVersionIds,
        SharedSessionState sharedState,
        AgentTurnState agentTurnState
    ) {
    }

    private record StoredSessionRow(
        String id,
        String scenarioId,
        String title,
        String customerId,
        String assistantId,
        String assistantName,
        String assistantReleaseVersion,
        Instant createdAt,
        Instant updatedAt,
        String latestTaskId,
        String latestWorkflowInstanceId,
        ToolOutcomeSummary latestToolOutcome,
        ResumeTaskSnapshot latestResumeTask,
        PauseReasonSnapshot latestPauseReason,
        List<String> loadedSkillResourceVersionIds,
        SharedSessionState sharedState
    ) {
    }

    private record StoredInteractionTaskRow(
        String id,
        ExternalInteractionType type,
        ExternalInteractionStatus status,
        String sessionId,
        String taskId,
        String workflowInstanceId,
        String messageId,
        String title,
        String instruction,
        String provider,
        String providerReference,
        String launchUrl,
        String returnToken,
        String returnPath,
        Instant expiresAt,
        ExternalInteractionResultDto latestResult,
        ExternalInteractionEventSource lastEventSource,
        Instant resumedAt,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}
