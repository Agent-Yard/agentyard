package com.lynxus.platform.runtime;

import static com.lynxus.platform.runtime.RuntimeDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynxus.contracts.runtime.WorkflowContracts.ExecutionCheckpoint;
import com.lynxus.contracts.runtime.WorkflowContracts.HumanTaskSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.PauseReasonSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.SharedSessionState;
import com.lynxus.contracts.runtime.WorkflowContracts.TaskStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolInvocationSnapshot;
import com.lynxus.contracts.runtime.WorkflowContracts.ToolOutcomeSummary;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcRuntimeRepository implements RuntimeRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<NodeExecutionDto>> NODE_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<ToolInvocationSnapshot>> TOOL_CALL_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcRuntimeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public List<TaskInstanceDto> listTasks() {
        return jdbcTemplate.query(
            """
                select id, scenario_id, assistant_id, assistant_name, assistant_release_version, question, requester, status, created_at, workflow_instance_id
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
                select id, scenario_id, assistant_id, assistant_name, assistant_release_version, question, requester, status, created_at, workflow_instance_id
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
                select id, scenario_id, assistant_id, assistant_name, assistant_release_version, question, requester, status, created_at, workflow_instance_id
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
                       current_node_key, escalation_required, checkpoint, human_task, pause_reason, latest_tool_outcome, resource_anchors, nodes,
                       tool_calls, loaded_skill_resource_version_ids, shared_state
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
                       current_node_key, escalation_required, checkpoint, human_task, pause_reason, latest_tool_outcome, resource_anchors, nodes,
                       tool_calls, loaded_skill_resource_version_ids, shared_state
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
                       current_node_key, escalation_required, checkpoint, human_task, pause_reason, latest_tool_outcome, resource_anchors, nodes,
                       tool_calls, loaded_skill_resource_version_ids, shared_state
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
    public List<ConversationSessionDto> listSessions() {
        return hydrateSessions(jdbcTemplate.query(
            """
                select id, scenario_id, title, requester, assistant_id, assistant_name, assistant_release_version, created_at, updated_at,
                       latest_task_id, latest_workflow_instance_id, latest_tool_outcome, latest_human_task, latest_pause_reason,
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
                select id, scenario_id, title, requester, assistant_id, assistant_name, assistant_release_version, created_at, updated_at,
                       latest_task_id, latest_workflow_instance_id, latest_tool_outcome, latest_human_task, latest_pause_reason,
                       loaded_skill_resource_version_ids, shared_state
                from conversation_session
                where id = ?
                """,
            (rs, rowNum) -> mapSessionRow(rs),
            sessionId
        )).stream().findFirst();
    }

    @Override
    public Optional<HumanInterventionDto> findPendingIntervention(String workflowInstanceId) {
        return jdbcTemplate.query(
            """
                select id, workflow_instance_id, action, operator_id, comment, attributes, status, created_at, applied_at, failure_reason
                from human_intervention
                where workflow_instance_id = ? and status = 'PENDING'
                order by created_at desc, id desc
                limit 1
                """,
            (rs, rowNum) -> mapHumanIntervention(rs),
            workflowInstanceId
        ).stream().findFirst();
    }

    @Override
    public List<HumanInterventionDto> listPendingInterventions() {
        return jdbcTemplate.query(
            """
                select id, workflow_instance_id, action, operator_id, comment, attributes, status, created_at, applied_at, failure_reason
                from human_intervention
                where status = 'PENDING'
                order by created_at asc, id asc
                """,
            (rs, rowNum) -> mapHumanIntervention(rs)
        );
    }

    @Override
    @Transactional
    public void persistProjection(
        TaskInstanceDto task,
        WorkflowInstanceDto workflow,
        ConversationSessionDto session,
        HumanInterventionDto intervention
    ) {
        upsertTask(task, session == null ? findTaskSessionId(task.id()).orElse(null) : session.id());
        upsertWorkflow(workflow);
        if (session != null) {
            upsertSession(session);
            upsertMessages(session.messages());
        }
        if (intervention != null) {
            upsertHumanIntervention(intervention);
        }
    }

    @Override
    @Transactional
    public void saveHumanIntervention(HumanInterventionDto intervention) {
        upsertHumanIntervention(intervention);
    }

    String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize runtime payload", error);
        }
    }

    <T> T readJson(String payload, Class<T> type) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to deserialize runtime payload", error);
        }
    }

    <T> T readJson(String payload, TypeReference<T> type) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException error) {
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
            rs.getString("requester"),
            TaskStatus.valueOf(rs.getString("status")),
            readInstant(rs, "created_at"),
            rs.getString("workflow_instance_id")
        );
    }

    private HumanInterventionDto mapHumanIntervention(ResultSet rs) throws SQLException {
        return new HumanInterventionDto(
            rs.getString("id"),
            rs.getString("workflow_instance_id"),
            rs.getString("action"),
            rs.getString("operator_id"),
            rs.getString("comment"),
            readJson(rs.getString("attributes"), STRING_MAP),
            HumanInterventionStatus.valueOf(rs.getString("status")),
            readInstant(rs, "created_at"),
            readNullableInstant(rs, "applied_at"),
            rs.getString("failure_reason")
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
            readJson(rs.getString("human_task"), HumanTaskSnapshot.class),
            readJson(rs.getString("pause_reason"), PauseReasonSnapshot.class),
            readJson(rs.getString("latest_tool_outcome"), ToolOutcomeSummary.class),
            readJson(rs.getString("resource_anchors"), STRING_LIST),
            readJson(rs.getString("nodes"), NODE_LIST),
            readJson(rs.getString("tool_calls"), TOOL_CALL_LIST),
            readJson(rs.getString("loaded_skill_resource_version_ids"), STRING_LIST),
            readJson(rs.getString("shared_state"), SharedSessionState.class)
        );
    }

    private StoredSessionRow mapSessionRow(ResultSet rs) throws SQLException {
        return new StoredSessionRow(
            rs.getString("id"),
            rs.getString("scenario_id"),
            rs.getString("title"),
            rs.getString("requester"),
            rs.getString("assistant_id"),
            rs.getString("assistant_name"),
            rs.getString("assistant_release_version"),
            readInstant(rs, "created_at"),
            readInstant(rs, "updated_at"),
            rs.getString("latest_task_id"),
            rs.getString("latest_workflow_instance_id"),
            readJson(rs.getString("latest_tool_outcome"), ToolOutcomeSummary.class),
            readJson(rs.getString("latest_human_task"), HumanTaskSnapshot.class),
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
                row.requester(),
                row.assistantId(),
                row.assistantName(),
                row.assistantReleaseVersion(),
                row.createdAt(),
                row.updatedAt(),
                messagesBySession.getOrDefault(row.id(), List.of()),
                row.latestTaskId(),
                row.latestWorkflowInstanceId(),
                row.latestToolOutcome(),
                row.latestHumanTask(),
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
        Map<String, List<HumanInterventionDto>> interventionsByWorkflow = loadInterventionsByWorkflow(workflowIds);
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
                row.humanTask(),
                row.pauseReason(),
                row.latestToolOutcome(),
                normalizeStringList(row.resourceAnchors()),
                row.nodes() == null ? List.of() : row.nodes(),
                row.toolCalls() == null ? List.of() : row.toolCalls(),
                interventionsByWorkflow.getOrDefault(row.id(), List.of()),
                normalizeStringList(row.loadedSkillResourceVersionIds()),
                row.sharedState() == null ? SharedSessionState.empty() : row.sharedState()
            ))
            .toList();
    }

    private Map<String, List<ConversationMessageDto>> loadMessagesBySession(List<String> sessionIds) {
        Map<String, List<ConversationMessageDto>> messagesBySession = new LinkedHashMap<>();
        jdbcTemplate.query(
            """
                select id, session_id, role, sender_type, sender_id, sender_name, content, created_at, task_id, workflow_instance_id
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

    private Map<String, List<HumanInterventionDto>> loadInterventionsByWorkflow(List<String> workflowIds) {
        Map<String, List<HumanInterventionDto>> interventionsByWorkflow = new LinkedHashMap<>();
        jdbcTemplate.query(
            """
                select id, workflow_instance_id, action, operator_id, comment, attributes, status, created_at, applied_at, failure_reason
                from human_intervention
                where workflow_instance_id in (%s)
                order by workflow_instance_id, created_at asc, id asc
                """.formatted(placeholders(workflowIds.size())),
            rs -> {
                String workflowId = rs.getString("workflow_instance_id");
                interventionsByWorkflow.computeIfAbsent(workflowId, ignored -> new ArrayList<>()).add(mapHumanIntervention(rs));
            },
            workflowIds.toArray()
        );
        return interventionsByWorkflow;
    }

    private void upsertTask(TaskInstanceDto task, String sessionId) {
        jdbcTemplate.update(
            """
                insert into task_instance (
                    id, session_id, scenario_id, assistant_id, assistant_name, assistant_release_version, question, requester, status, workflow_instance_id, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (id) do update set
                    session_id = excluded.session_id,
                    scenario_id = excluded.scenario_id,
                    assistant_id = excluded.assistant_id,
                    assistant_name = excluded.assistant_name,
                    assistant_release_version = excluded.assistant_release_version,
                    question = excluded.question,
                    requester = excluded.requester,
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
            task.requester(),
            task.status().name(),
            task.workflowInstanceId(),
            task.createdAt()
        );
    }

    private void upsertWorkflow(WorkflowInstanceDto workflow) {
        jdbcTemplate.update(
            """
                insert into workflow_instance (
                    id, task_id, assistant_id, assistant_name, assistant_release_version, created_at, updated_at, status, summary, final_reply,
                    current_node_key, escalation_required, checkpoint, human_task, pause_reason, latest_tool_outcome, resource_anchors, nodes,
                    tool_calls, loaded_skill_resource_version_ids, shared_state
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb),
                          cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb))
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
                    human_task = excluded.human_task,
                    pause_reason = excluded.pause_reason,
                    latest_tool_outcome = excluded.latest_tool_outcome,
                    resource_anchors = excluded.resource_anchors,
                    nodes = excluded.nodes,
                    tool_calls = excluded.tool_calls,
                    loaded_skill_resource_version_ids = excluded.loaded_skill_resource_version_ids,
                    shared_state = excluded.shared_state
                """,
            workflow.id(),
            workflow.taskId(),
            workflow.assistantId(),
            workflow.assistantName(),
            workflow.assistantReleaseVersion(),
            workflow.createdAt(),
            workflow.updatedAt(),
            workflow.status().name(),
            workflow.summary(),
            workflow.finalReply(),
            workflow.currentNodeKey(),
            workflow.escalationRequired(),
            writeJson(workflow.checkpoint()),
            writeJson(workflow.humanTask()),
            writeJson(workflow.pauseReason()),
            writeJson(workflow.latestToolOutcome()),
            writeJson(workflow.resourceAnchors()),
            writeJson(workflow.nodes()),
            writeJson(workflow.toolCalls()),
            writeJson(workflow.loadedSkillResourceVersionIds()),
            writeJson(workflow.sharedState())
        );
    }

    private void upsertSession(ConversationSessionDto session) {
        jdbcTemplate.update(
            """
                insert into conversation_session (
                    id, scenario_id, title, requester, assistant_id, assistant_name, assistant_release_version, created_at, updated_at,
                    latest_task_id, latest_workflow_instance_id, latest_tool_outcome, latest_human_task, latest_pause_reason,
                    loaded_skill_resource_version_ids, shared_state
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb))
                on conflict (id) do update set
                    scenario_id = excluded.scenario_id,
                    title = excluded.title,
                    requester = excluded.requester,
                    assistant_id = excluded.assistant_id,
                    assistant_name = excluded.assistant_name,
                    assistant_release_version = excluded.assistant_release_version,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at,
                    latest_task_id = excluded.latest_task_id,
                    latest_workflow_instance_id = excluded.latest_workflow_instance_id,
                    latest_tool_outcome = excluded.latest_tool_outcome,
                    latest_human_task = excluded.latest_human_task,
                    latest_pause_reason = excluded.latest_pause_reason,
                    loaded_skill_resource_version_ids = excluded.loaded_skill_resource_version_ids,
                    shared_state = excluded.shared_state
                """,
            session.id(),
            session.scenarioId(),
            session.title(),
            session.requester(),
            session.assistantId(),
            session.assistantName(),
            session.assistantReleaseVersion(),
            session.createdAt(),
            session.updatedAt(),
            session.latestTaskId(),
            session.latestWorkflowInstanceId(),
            writeJson(session.latestToolOutcome()),
            writeJson(session.latestHumanTask()),
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
                        id, session_id, role, sender_type, sender_id, sender_name, content, created_at, task_id, workflow_instance_id
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (id) do update set
                        session_id = excluded.session_id,
                        role = excluded.role,
                        sender_type = excluded.sender_type,
                        sender_id = excluded.sender_id,
                        sender_name = excluded.sender_name,
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
                message.content(),
                message.createdAt(),
                message.taskId(),
                message.workflowInstanceId()
            );
        }
    }

    private void upsertHumanIntervention(HumanInterventionDto intervention) {
        jdbcTemplate.update(
            """
                insert into human_intervention (
                    id, workflow_instance_id, action, operator_id, comment, attributes, status, created_at, applied_at, failure_reason
                ) values (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?)
                on conflict (id) do update set
                    workflow_instance_id = excluded.workflow_instance_id,
                    action = excluded.action,
                    operator_id = excluded.operator_id,
                    comment = excluded.comment,
                    attributes = excluded.attributes,
                    status = excluded.status,
                    created_at = excluded.created_at,
                    applied_at = excluded.applied_at,
                    failure_reason = excluded.failure_reason
                """,
            intervention.id(),
            intervention.workflowInstanceId(),
            intervention.action(),
            intervention.operator(),
            intervention.comment(),
            writeJson(intervention.attributes() == null ? Map.of() : intervention.attributes()),
            intervention.status().name(),
            intervention.createdAt(),
            intervention.appliedAt(),
            intervention.failureReason()
        );
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
        HumanTaskSnapshot humanTask,
        PauseReasonSnapshot pauseReason,
        ToolOutcomeSummary latestToolOutcome,
        List<String> resourceAnchors,
        List<NodeExecutionDto> nodes,
        List<ToolInvocationSnapshot> toolCalls,
        List<String> loadedSkillResourceVersionIds,
        SharedSessionState sharedState
    ) {
    }

    private record StoredSessionRow(
        String id,
        String scenarioId,
        String title,
        String requester,
        String assistantId,
        String assistantName,
        String assistantReleaseVersion,
        Instant createdAt,
        Instant updatedAt,
        String latestTaskId,
        String latestWorkflowInstanceId,
        ToolOutcomeSummary latestToolOutcome,
        HumanTaskSnapshot latestHumanTask,
        PauseReasonSnapshot latestPauseReason,
        List<String> loadedSkillResourceVersionIds,
        SharedSessionState sharedState
    ) {
    }
}
