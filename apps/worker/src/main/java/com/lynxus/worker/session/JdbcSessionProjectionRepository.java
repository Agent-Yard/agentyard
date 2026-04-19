package com.lynxus.worker.session;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcSessionProjectionRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcSessionProjectionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void saveSession(SessionPersistenceActivities.SessionRecord session) {
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? java.util.Map.of() : value);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to serialize session runtime json payload", error);
        }
    }

    private static Timestamp toTimestamp(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
