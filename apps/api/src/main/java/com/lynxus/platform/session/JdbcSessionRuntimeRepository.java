package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.PlaybookRunStatus;
import com.lynxus.contracts.session.SessionContracts.SessionActorType;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import com.lynxus.contracts.session.SessionContracts.SessionEventType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static com.lynxus.platform.session.SessionRuntimeDtos.*;

@Repository
public class JdbcSessionRuntimeRepository implements SessionRuntimeRepository {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcSessionRuntimeRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SessionRuntimeSessionDto> listSessions() {
        return jdbcTemplate.query(
            """
                select id, scenario_id, title, customer_id, assistant_id, assistant_name, assistant_release_version, status,
                       primary_agent_id, current_owner_agent_id, active_playbook_run_id, agent_turn_active,
                       session_human_handoff_active, pending_owner_reevaluation, draining, shared_state, idle_deadline,
                       created_at, updated_at, ended_at,
                       coalesce((select max(sequence) from session_runtime_event e where e.session_id = s.id), 0) as latest_event_sequence
                from session_runtime_session s
                order by updated_at desc, id desc
                """,
            (rs, rowNum) -> mapSession(rs)
        );
    }

    @Override
    public Optional<SessionRuntimeSessionDto> findSession(String sessionId) {
        return jdbcTemplate.query(
            """
                select id, scenario_id, title, customer_id, assistant_id, assistant_name, assistant_release_version, status,
                       primary_agent_id, current_owner_agent_id, active_playbook_run_id, agent_turn_active,
                       session_human_handoff_active, pending_owner_reevaluation, draining, shared_state, idle_deadline,
                       created_at, updated_at, ended_at,
                       coalesce((select max(sequence) from session_runtime_event e where e.session_id = s.id), 0) as latest_event_sequence
                from session_runtime_session s
                where id = ?
                """,
            (rs, rowNum) -> mapSession(rs),
            sessionId
        ).stream().findFirst();
    }

    @Override
    public Optional<SessionRuntimeSessionDto> findActiveSession(String customerId, String assistantId) {
        return jdbcTemplate.query(
            """
                select id, scenario_id, title, customer_id, assistant_id, assistant_name, assistant_release_version, status,
                       primary_agent_id, current_owner_agent_id, active_playbook_run_id, agent_turn_active,
                       session_human_handoff_active, pending_owner_reevaluation, draining, shared_state, idle_deadline,
                       created_at, updated_at, ended_at,
                       coalesce((select max(sequence) from session_runtime_event e where e.session_id = s.id), 0) as latest_event_sequence
                from session_runtime_session s
                where customer_id = ? and assistant_id = ? and status in ('ACTIVE', 'IDLE', 'DRAINING')
                order by updated_at desc, id desc
                limit 1
                """,
            (rs, rowNum) -> mapSession(rs),
            customerId,
            assistantId
        ).stream().findFirst();
    }

    @Override
    public Optional<SessionRuntimeChangeStamp> findSessionChangeStamp(String sessionId) {
        return jdbcTemplate.query(
            """
                select s.id,
                       s.updated_at,
                       coalesce((select max(sequence) from session_runtime_event e where e.session_id = s.id), 0) as latest_event_sequence,
                       (select max(updated_at) from session_runtime_playbook_run p where p.session_id = s.id) as latest_playbook_run_updated_at
                from session_runtime_session s
                where s.id = ?
                """,
            (rs, rowNum) -> new SessionRuntimeChangeStamp(
                rs.getString("id"),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getLong("latest_event_sequence"),
                toInstant(rs.getTimestamp("latest_playbook_run_updated_at"))
            ),
            sessionId
        ).stream().findFirst();
    }

    @Override
    public void saveSession(SessionRuntimeSessionDto session) {
        jdbcTemplate.update(
            """
                insert into session_runtime_session (
                    id, scenario_id, title, customer_id, assistant_id, assistant_name, assistant_release_version, status,
                    primary_agent_id, current_owner_agent_id, active_playbook_run_id, agent_turn_active,
                    session_human_handoff_active, pending_owner_reevaluation, draining, shared_state, idle_deadline,
                    created_at, updated_at, ended_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?)
                on conflict (id) do update set
                    scenario_id = excluded.scenario_id,
                    title = excluded.title,
                    customer_id = excluded.customer_id,
                    assistant_id = excluded.assistant_id,
                    assistant_name = excluded.assistant_name,
                    assistant_release_version = excluded.assistant_release_version,
                    status = excluded.status,
                    primary_agent_id = excluded.primary_agent_id,
                    current_owner_agent_id = excluded.current_owner_agent_id,
                    active_playbook_run_id = excluded.active_playbook_run_id,
                    agent_turn_active = excluded.agent_turn_active,
                    session_human_handoff_active = excluded.session_human_handoff_active,
                    pending_owner_reevaluation = excluded.pending_owner_reevaluation,
                    draining = excluded.draining,
                    shared_state = excluded.shared_state,
                    idle_deadline = excluded.idle_deadline,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at,
                    ended_at = excluded.ended_at
                """,
            session.id(),
            session.scenarioId(),
            session.title(),
            session.customerId(),
            session.assistantId(),
            session.assistantName(),
            session.assistantReleaseVersion(),
            session.status(),
            session.primaryAgentId(),
            session.currentOwnerAgentId(),
            session.activePlaybookRunId(),
            session.agentTurnActive(),
            session.sessionHumanHandoffActive(),
            session.pendingOwnerReevaluation(),
            session.draining(),
            writeJson(session.sharedState()),
            toTimestamp(session.idleDeadline()),
            toTimestamp(session.createdAt()),
            toTimestamp(session.updatedAt()),
            toTimestamp(session.endedAt())
        );
    }

    @Override
    public List<SessionEvent> listEvents(String sessionId) {
        return jdbcTemplate.query(
            """
                select event_id, session_id, sequence, event_type, created_at, actor_type, actor_id, payload,
                       related_playbook_run_id, related_owner_agent_id
                from session_runtime_event
                where session_id = ?
                order by sequence asc, created_at asc
                """,
            (rs, rowNum) -> mapEvent(rs),
            sessionId
        );
    }

    @Override
    public long nextEventSequence(String sessionId) {
        Long next = jdbcTemplate.queryForObject(
            "select coalesce(max(sequence), 0) + 1 from session_runtime_event where session_id = ?",
            Long.class,
            sessionId
        );
        return next == null ? 1 : next;
    }

    @Override
    public void appendEvent(SessionEvent event) {
        jdbcTemplate.update(
            """
                insert into session_runtime_event (
                    event_id, session_id, sequence, event_type, created_at, actor_type, actor_id, payload,
                    related_playbook_run_id, related_owner_agent_id
                ) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
                on conflict (event_id) do nothing
                """,
            event.eventId(),
            event.sessionId(),
            event.sequence(),
            event.eventType().name(),
            toTimestamp(event.createdAt()),
            event.actorType().name(),
            event.actorId(),
            writeJson(event.payload()),
            event.relatedPlaybookRunId(),
            event.relatedOwnerAgentId()
        );
    }

    @Override
    public List<PlaybookRun> listPlaybookRuns(String sessionId) {
        return jdbcTemplate.query(
            """
                select run_id, session_id, parent_session_event_id, playbook_id, owner_agent_id, status, input, result,
                       failure_reason, created_at, updated_at, waiting_reason
                from session_runtime_playbook_run
                where session_id = ?
                order by updated_at desc, run_id desc
                """,
            (rs, rowNum) -> mapPlaybookRun(rs),
            sessionId
        );
    }

    @Override
    public void savePlaybookRun(PlaybookRun playbookRun) {
        jdbcTemplate.update(
            """
                insert into session_runtime_playbook_run (
                    run_id, session_id, parent_session_event_id, playbook_id, owner_agent_id, status, input, result,
                    failure_reason, created_at, updated_at, waiting_reason
                ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?, ?)
                on conflict (run_id) do update set
                    session_id = excluded.session_id,
                    parent_session_event_id = excluded.parent_session_event_id,
                    playbook_id = excluded.playbook_id,
                    owner_agent_id = excluded.owner_agent_id,
                    status = excluded.status,
                    input = excluded.input,
                    result = excluded.result,
                    failure_reason = excluded.failure_reason,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at,
                    waiting_reason = excluded.waiting_reason
                """,
            playbookRun.runId(),
            playbookRun.sessionId(),
            playbookRun.parentSessionEventId(),
            playbookRun.playbookId(),
            playbookRun.ownerAgentId(),
            playbookRun.status().name(),
            writeJson(playbookRun.input()),
            writeJson(playbookRun.result()),
            playbookRun.failureReason(),
            toTimestamp(playbookRun.createdAt()),
            toTimestamp(playbookRun.updatedAt()),
            playbookRun.waitingReason()
        );
    }

    private SessionRuntimeSessionDto mapSession(ResultSet rs) throws SQLException {
        return new SessionRuntimeSessionDto(
            rs.getString("id"),
            rs.getString("scenario_id"),
            rs.getString("title"),
            rs.getString("customer_id"),
            rs.getString("assistant_id"),
            rs.getString("assistant_name"),
            rs.getString("assistant_release_version"),
            rs.getString("status"),
            rs.getString("primary_agent_id"),
            rs.getString("current_owner_agent_id"),
            rs.getString("active_playbook_run_id"),
            rs.getBoolean("agent_turn_active"),
            rs.getBoolean("session_human_handoff_active"),
            rs.getBoolean("pending_owner_reevaluation"),
            rs.getBoolean("draining"),
            readObjectMap(rs.getString("shared_state")),
            toInstant(rs.getTimestamp("idle_deadline")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            toInstant(rs.getTimestamp("ended_at")),
            rs.getLong("latest_event_sequence")
        );
    }

    private SessionEvent mapEvent(ResultSet rs) throws SQLException {
        return new SessionEvent(
            rs.getString("event_id"),
            rs.getString("session_id"),
            rs.getLong("sequence"),
            SessionEventType.valueOf(rs.getString("event_type")),
            toInstant(rs.getTimestamp("created_at")),
            SessionActorType.valueOf(rs.getString("actor_type")),
            rs.getString("actor_id"),
            readObjectMap(rs.getString("payload")),
            rs.getString("related_playbook_run_id"),
            rs.getString("related_owner_agent_id")
        );
    }

    private PlaybookRun mapPlaybookRun(ResultSet rs) throws SQLException {
        return new PlaybookRun(
            rs.getString("run_id"),
            rs.getString("session_id"),
            rs.getString("parent_session_event_id"),
            rs.getString("playbook_id"),
            rs.getString("owner_agent_id"),
            PlaybookRunStatus.valueOf(rs.getString("status")),
            readObjectMap(rs.getString("input")),
            readObjectMap(rs.getString("result")),
            rs.getString("failure_reason"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            rs.getString("waiting_reason")
        );
    }

    private Map<String, Object> readObjectMap(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payload, OBJECT_MAP);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to deserialize session runtime json payload", error);
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to serialize session runtime json payload", error);
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
