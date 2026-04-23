package com.lynxus.persistence.session;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.PlaybookRunStatus;
import com.lynxus.contracts.session.SessionContracts.SessionActorType;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import com.lynxus.contracts.session.SessionContracts.SessionEventType;
import com.lynxus.contracts.session.SessionContracts.SessionMessage;
import com.lynxus.contracts.session.SessionContracts.SessionMessageRole;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSender;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSenderType;
import com.lynxus.contracts.session.SessionContracts.SessionMessageStatus;
import com.lynxus.persistence.jooqsupport.JooqJsonbSupport;
import com.lynxus.persistence.jooqsupport.JooqTimeSupport;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;

import static com.lynxus.persistence.jooq.Tables.SESSION_RUNTIME_EVENT;
import static com.lynxus.persistence.jooq.Tables.SESSION_RUNTIME_MESSAGE;
import static com.lynxus.persistence.jooq.Tables.SESSION_RUNTIME_PLAYBOOK_RUN;
import static com.lynxus.persistence.jooq.Tables.SESSION_RUNTIME_SESSION;

public final class SessionRuntimeStore {
    private static final List<String> ACTIVE_STATUSES = List.of("ACTIVE", "IDLE", "DRAINING");

    private final DSLContext dsl;
    private final JooqJsonbSupport jsonbSupport;

    public SessionRuntimeStore(DSLContext dsl, JooqJsonbSupport jsonbSupport) {
        this.dsl = dsl;
        this.jsonbSupport = jsonbSupport;
    }

    public List<SessionRuntimeSessionData> listSessions() {
        Field<Long> latestMessageSequence = latestMessageSequenceField();
        Field<Long> latestEventSequence = latestEventSequenceField();
        return dsl.select(SESSION_RUNTIME_SESSION.fields())
            .select(latestMessageSequence)
            .select(latestEventSequence)
            .from(SESSION_RUNTIME_SESSION)
            .orderBy(SESSION_RUNTIME_SESSION.UPDATED_AT.desc(), SESSION_RUNTIME_SESSION.ID.desc())
            .fetch(record -> mapSession(record, latestMessageSequence, latestEventSequence));
    }

    public Optional<SessionRuntimeSessionData> findSession(String sessionId) {
        Field<Long> latestMessageSequence = latestMessageSequenceField();
        Field<Long> latestEventSequence = latestEventSequenceField();
        return dsl.select(SESSION_RUNTIME_SESSION.fields())
            .select(latestMessageSequence)
            .select(latestEventSequence)
            .from(SESSION_RUNTIME_SESSION)
            .where(SESSION_RUNTIME_SESSION.ID.eq(sessionId))
            .fetchOptional(record -> mapSession(record, latestMessageSequence, latestEventSequence));
    }

    public Optional<SessionRuntimeSessionData> findActiveSession(String customerId, String assistantId) {
        Field<Long> latestMessageSequence = latestMessageSequenceField();
        Field<Long> latestEventSequence = latestEventSequenceField();
        return dsl.select(SESSION_RUNTIME_SESSION.fields())
            .select(latestMessageSequence)
            .select(latestEventSequence)
            .from(SESSION_RUNTIME_SESSION)
            .where(SESSION_RUNTIME_SESSION.CUSTOMER_ID.eq(customerId))
            .and(SESSION_RUNTIME_SESSION.ASSISTANT_ID.eq(assistantId))
            .and(SESSION_RUNTIME_SESSION.STATUS.in(ACTIVE_STATUSES))
            .orderBy(SESSION_RUNTIME_SESSION.UPDATED_AT.desc(), SESSION_RUNTIME_SESSION.ID.desc())
            .limit(1)
            .fetchOptional(record -> mapSession(record, latestMessageSequence, latestEventSequence));
    }

    public Optional<SessionRuntimeChangeStamp> findSessionChangeStamp(String sessionId) {
        Field<Long> latestMessageSequence = latestMessageSequenceField();
        Field<Long> latestEventSequence = latestEventSequenceField();
        Field<OffsetDateTime> latestPlaybookRunUpdatedAt = latestPlaybookRunUpdatedAtField();
        return dsl.select(
                SESSION_RUNTIME_SESSION.ID,
                SESSION_RUNTIME_SESSION.UPDATED_AT,
                latestMessageSequence,
                latestEventSequence,
                latestPlaybookRunUpdatedAt
            )
            .from(SESSION_RUNTIME_SESSION)
            .where(SESSION_RUNTIME_SESSION.ID.eq(sessionId))
            .fetchOptional(record -> new SessionRuntimeChangeStamp(
                record.get(SESSION_RUNTIME_SESSION.ID),
                JooqTimeSupport.toInstant(record.get(SESSION_RUNTIME_SESSION.UPDATED_AT)),
                record.get(latestMessageSequence),
                record.get(latestEventSequence),
                JooqTimeSupport.toInstant(record.get(latestPlaybookRunUpdatedAt))
            ));
    }

    public void saveSession(SessionRuntimeSessionData session) {
        dsl.insertInto(SESSION_RUNTIME_SESSION)
            .set(SESSION_RUNTIME_SESSION.ID, session.id())
            .set(SESSION_RUNTIME_SESSION.SCENARIO_ID, session.scenarioId())
            .set(SESSION_RUNTIME_SESSION.TITLE, session.title())
            .set(SESSION_RUNTIME_SESSION.CUSTOMER_ID, session.customerId())
            .set(SESSION_RUNTIME_SESSION.ASSISTANT_ID, session.assistantId())
            .set(SESSION_RUNTIME_SESSION.ASSISTANT_NAME, session.assistantName())
            .set(SESSION_RUNTIME_SESSION.ASSISTANT_RELEASE_VERSION, session.assistantReleaseVersion())
            .set(SESSION_RUNTIME_SESSION.STATUS, session.status())
            .set(SESSION_RUNTIME_SESSION.PRIMARY_AGENT_ID, session.primaryAgentId())
            .set(SESSION_RUNTIME_SESSION.CURRENT_OWNER_AGENT_ID, session.currentOwnerAgentId())
            .set(SESSION_RUNTIME_SESSION.ACTIVE_PLAYBOOK_RUN_ID, session.activePlaybookRunId())
            .set(SESSION_RUNTIME_SESSION.AGENT_TURN_ACTIVE, session.agentTurnActive())
            .set(SESSION_RUNTIME_SESSION.SESSION_HUMAN_HANDOFF_ACTIVE, session.sessionHumanHandoffActive())
            .set(SESSION_RUNTIME_SESSION.PENDING_OWNER_REEVALUATION, session.pendingOwnerReevaluation())
            .set(SESSION_RUNTIME_SESSION.DRAINING, session.draining())
            .set(SESSION_RUNTIME_SESSION.SHARED_STATE, jsonbSupport.toJsonb(session.sharedState() == null ? Map.of() : session.sharedState()))
            .set(SESSION_RUNTIME_SESSION.IDLE_DEADLINE, JooqTimeSupport.toOffsetDateTime(session.idleDeadline()))
            .set(SESSION_RUNTIME_SESSION.CREATED_AT, JooqTimeSupport.toOffsetDateTime(session.createdAt()))
            .set(SESSION_RUNTIME_SESSION.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(session.updatedAt()))
            .set(SESSION_RUNTIME_SESSION.ENDED_AT, JooqTimeSupport.toOffsetDateTime(session.endedAt()))
            .onConflict(SESSION_RUNTIME_SESSION.ID)
            .doUpdate()
            .set(SESSION_RUNTIME_SESSION.SCENARIO_ID, session.scenarioId())
            .set(SESSION_RUNTIME_SESSION.TITLE, session.title())
            .set(SESSION_RUNTIME_SESSION.CUSTOMER_ID, session.customerId())
            .set(SESSION_RUNTIME_SESSION.ASSISTANT_ID, session.assistantId())
            .set(SESSION_RUNTIME_SESSION.ASSISTANT_NAME, session.assistantName())
            .set(SESSION_RUNTIME_SESSION.ASSISTANT_RELEASE_VERSION, session.assistantReleaseVersion())
            .set(SESSION_RUNTIME_SESSION.STATUS, session.status())
            .set(SESSION_RUNTIME_SESSION.PRIMARY_AGENT_ID, session.primaryAgentId())
            .set(SESSION_RUNTIME_SESSION.CURRENT_OWNER_AGENT_ID, session.currentOwnerAgentId())
            .set(SESSION_RUNTIME_SESSION.ACTIVE_PLAYBOOK_RUN_ID, session.activePlaybookRunId())
            .set(SESSION_RUNTIME_SESSION.AGENT_TURN_ACTIVE, session.agentTurnActive())
            .set(SESSION_RUNTIME_SESSION.SESSION_HUMAN_HANDOFF_ACTIVE, session.sessionHumanHandoffActive())
            .set(SESSION_RUNTIME_SESSION.PENDING_OWNER_REEVALUATION, session.pendingOwnerReevaluation())
            .set(SESSION_RUNTIME_SESSION.DRAINING, session.draining())
            .set(SESSION_RUNTIME_SESSION.SHARED_STATE, jsonbSupport.toJsonb(session.sharedState() == null ? Map.of() : session.sharedState()))
            .set(SESSION_RUNTIME_SESSION.IDLE_DEADLINE, JooqTimeSupport.toOffsetDateTime(session.idleDeadline()))
            .set(SESSION_RUNTIME_SESSION.CREATED_AT, JooqTimeSupport.toOffsetDateTime(session.createdAt()))
            .set(SESSION_RUNTIME_SESSION.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(session.updatedAt()))
            .set(SESSION_RUNTIME_SESSION.ENDED_AT, JooqTimeSupport.toOffsetDateTime(session.endedAt()))
            .execute();
    }

    public List<SessionEvent> listEvents(String sessionId) {
        return dsl.selectFrom(SESSION_RUNTIME_EVENT)
            .where(SESSION_RUNTIME_EVENT.SESSION_ID.eq(sessionId))
            .orderBy(SESSION_RUNTIME_EVENT.SEQUENCE.asc(), SESSION_RUNTIME_EVENT.CREATED_AT.asc())
            .fetch(this::mapEvent);
    }

    public List<SessionMessage> listMessages(String sessionId) {
        return dsl.selectFrom(SESSION_RUNTIME_MESSAGE)
            .where(SESSION_RUNTIME_MESSAGE.SESSION_ID.eq(sessionId))
            .orderBy(SESSION_RUNTIME_MESSAGE.SEQUENCE.asc(), SESSION_RUNTIME_MESSAGE.CREATED_AT.asc())
            .fetch(this::mapMessage);
    }

    public long nextEventSequence(String sessionId) {
        Long value = dsl.select(DSL.coalesce(DSL.max(SESSION_RUNTIME_EVENT.SEQUENCE), 0L).add(1L))
            .from(SESSION_RUNTIME_EVENT)
            .where(SESSION_RUNTIME_EVENT.SESSION_ID.eq(sessionId))
            .fetchOne(0, Long.class);
        return value == null ? 1L : value;
    }

    public long nextMessageSequence(String sessionId) {
        Long value = dsl.select(DSL.coalesce(DSL.max(SESSION_RUNTIME_MESSAGE.SEQUENCE), 0L).add(1L))
            .from(SESSION_RUNTIME_MESSAGE)
            .where(SESSION_RUNTIME_MESSAGE.SESSION_ID.eq(sessionId))
            .fetchOne(0, Long.class);
        return value == null ? 1L : value;
    }

    public void appendEvent(SessionEvent event) {
        dsl.insertInto(SESSION_RUNTIME_EVENT)
            .set(SESSION_RUNTIME_EVENT.EVENT_ID, event.eventId())
            .set(SESSION_RUNTIME_EVENT.SESSION_ID, event.sessionId())
            .set(SESSION_RUNTIME_EVENT.SEQUENCE, event.sequence())
            .set(SESSION_RUNTIME_EVENT.EVENT_TYPE, event.eventType().name())
            .set(SESSION_RUNTIME_EVENT.CREATED_AT, JooqTimeSupport.toOffsetDateTime(event.createdAt()))
            .set(SESSION_RUNTIME_EVENT.ACTOR_TYPE, event.actorType().name())
            .set(SESSION_RUNTIME_EVENT.ACTOR_ID, event.actorId())
            .set(SESSION_RUNTIME_EVENT.PAYLOAD, jsonbSupport.toJsonb(event.payload() == null ? Map.of() : event.payload()))
            .set(SESSION_RUNTIME_EVENT.RELATED_MESSAGE_ID, event.relatedMessageId())
            .set(SESSION_RUNTIME_EVENT.RELATED_PLAYBOOK_RUN_ID, event.relatedPlaybookRunId())
            .set(SESSION_RUNTIME_EVENT.RELATED_OWNER_AGENT_ID, event.relatedOwnerAgentId())
            .onConflict(SESSION_RUNTIME_EVENT.EVENT_ID)
            .doNothing()
            .execute();
    }

    public void appendMessage(SessionMessage message) {
        dsl.insertInto(SESSION_RUNTIME_MESSAGE)
            .set(SESSION_RUNTIME_MESSAGE.MESSAGE_ID, message.messageId())
            .set(SESSION_RUNTIME_MESSAGE.SESSION_ID, message.sessionId())
            .set(SESSION_RUNTIME_MESSAGE.SEQUENCE, message.sequence())
            .set(SESSION_RUNTIME_MESSAGE.ROLE, message.role().name())
            .set(SESSION_RUNTIME_MESSAGE.SENDER_TYPE, message.sender().senderType().name())
            .set(SESSION_RUNTIME_MESSAGE.SENDER_ID, message.sender().senderId())
            .set(SESSION_RUNTIME_MESSAGE.SENDER_NAME, message.sender().senderName())
            .set(SESSION_RUNTIME_MESSAGE.STATUS, message.status().name())
            .set(SESSION_RUNTIME_MESSAGE.BLOCKS, jsonbSupport.toJsonb(message.blocks() == null ? List.of() : message.blocks()))
            .set(SESSION_RUNTIME_MESSAGE.METADATA, jsonbSupport.toJsonb(message.metadata() == null ? Map.of() : message.metadata()))
            .set(SESSION_RUNTIME_MESSAGE.RELATED_PLAYBOOK_RUN_ID, message.relatedPlaybookRunId())
            .set(SESSION_RUNTIME_MESSAGE.RELATED_OWNER_AGENT_ID, message.relatedOwnerAgentId())
            .set(SESSION_RUNTIME_MESSAGE.SOURCE_EVENT_ID, message.sourceEventId())
            .set(SESSION_RUNTIME_MESSAGE.CREATED_AT, JooqTimeSupport.toOffsetDateTime(message.createdAt()))
            .set(SESSION_RUNTIME_MESSAGE.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(message.updatedAt()))
            .onConflict(SESSION_RUNTIME_MESSAGE.MESSAGE_ID)
            .doNothing()
            .execute();
    }

    public List<PlaybookRun> listPlaybookRuns(String sessionId) {
        return dsl.selectFrom(SESSION_RUNTIME_PLAYBOOK_RUN)
            .where(SESSION_RUNTIME_PLAYBOOK_RUN.SESSION_ID.eq(sessionId))
            .orderBy(SESSION_RUNTIME_PLAYBOOK_RUN.UPDATED_AT.desc(), SESSION_RUNTIME_PLAYBOOK_RUN.RUN_ID.desc())
            .fetch(this::mapPlaybookRun);
    }

    public void savePlaybookRun(PlaybookRun playbookRun) {
        dsl.insertInto(SESSION_RUNTIME_PLAYBOOK_RUN)
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.RUN_ID, playbookRun.runId())
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.SESSION_ID, playbookRun.sessionId())
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.PARENT_SESSION_EVENT_ID, playbookRun.parentSessionEventId())
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.PLAYBOOK_ID, playbookRun.playbookId())
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.OWNER_AGENT_ID, playbookRun.ownerAgentId())
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.STATUS, playbookRun.status().name())
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.INPUT, jsonbSupport.toJsonb(playbookRun.input() == null ? Map.of() : playbookRun.input()))
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.RESULT, jsonbSupport.toJsonb(playbookRun.result() == null ? Map.of() : playbookRun.result()))
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.FAILURE_REASON, playbookRun.failureReason())
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.CREATED_AT, JooqTimeSupport.toOffsetDateTime(playbookRun.createdAt()))
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(playbookRun.updatedAt()))
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.WAITING_REASON, playbookRun.waitingReason())
            .onConflict(SESSION_RUNTIME_PLAYBOOK_RUN.RUN_ID)
            .doUpdate()
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.SESSION_ID, playbookRun.sessionId())
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.PARENT_SESSION_EVENT_ID, playbookRun.parentSessionEventId())
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.PLAYBOOK_ID, playbookRun.playbookId())
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.OWNER_AGENT_ID, playbookRun.ownerAgentId())
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.STATUS, playbookRun.status().name())
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.INPUT, jsonbSupport.toJsonb(playbookRun.input() == null ? Map.of() : playbookRun.input()))
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.RESULT, jsonbSupport.toJsonb(playbookRun.result() == null ? Map.of() : playbookRun.result()))
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.FAILURE_REASON, playbookRun.failureReason())
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.CREATED_AT, JooqTimeSupport.toOffsetDateTime(playbookRun.createdAt()))
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(playbookRun.updatedAt()))
            .set(SESSION_RUNTIME_PLAYBOOK_RUN.WAITING_REASON, playbookRun.waitingReason())
            .execute();
    }

    private Field<Long> latestEventSequenceField() {
        Field<Long> maxSequence = DSL.select(DSL.max(SESSION_RUNTIME_EVENT.SEQUENCE))
            .from(SESSION_RUNTIME_EVENT)
            .where(SESSION_RUNTIME_EVENT.SESSION_ID.eq(SESSION_RUNTIME_SESSION.ID))
            .asField();
        return DSL.coalesce(maxSequence, DSL.inline(0L)).as("latest_event_sequence");
    }

    private Field<Long> latestMessageSequenceField() {
        Field<Long> maxSequence = DSL.select(DSL.max(SESSION_RUNTIME_MESSAGE.SEQUENCE))
            .from(SESSION_RUNTIME_MESSAGE)
            .where(SESSION_RUNTIME_MESSAGE.SESSION_ID.eq(SESSION_RUNTIME_SESSION.ID))
            .asField();
        return DSL.coalesce(maxSequence, DSL.inline(0L)).as("latest_message_sequence");
    }

    private Field<OffsetDateTime> latestPlaybookRunUpdatedAtField() {
        return DSL.select(DSL.max(SESSION_RUNTIME_PLAYBOOK_RUN.UPDATED_AT))
            .from(SESSION_RUNTIME_PLAYBOOK_RUN)
            .where(SESSION_RUNTIME_PLAYBOOK_RUN.SESSION_ID.eq(SESSION_RUNTIME_SESSION.ID))
            .asField("latest_playbook_run_updated_at");
    }

    private SessionRuntimeSessionData mapSession(Record record, Field<Long> latestMessageSequence, Field<Long> latestEventSequence) {
        return new SessionRuntimeSessionData(
            record.get(SESSION_RUNTIME_SESSION.ID),
            record.get(SESSION_RUNTIME_SESSION.SCENARIO_ID),
            record.get(SESSION_RUNTIME_SESSION.TITLE),
            record.get(SESSION_RUNTIME_SESSION.CUSTOMER_ID),
            record.get(SESSION_RUNTIME_SESSION.ASSISTANT_ID),
            record.get(SESSION_RUNTIME_SESSION.ASSISTANT_NAME),
            record.get(SESSION_RUNTIME_SESSION.ASSISTANT_RELEASE_VERSION),
            record.get(SESSION_RUNTIME_SESSION.STATUS),
            record.get(SESSION_RUNTIME_SESSION.PRIMARY_AGENT_ID),
            record.get(SESSION_RUNTIME_SESSION.CURRENT_OWNER_AGENT_ID),
            record.get(SESSION_RUNTIME_SESSION.ACTIVE_PLAYBOOK_RUN_ID),
            record.get(SESSION_RUNTIME_SESSION.AGENT_TURN_ACTIVE),
            record.get(SESSION_RUNTIME_SESSION.SESSION_HUMAN_HANDOFF_ACTIVE),
            record.get(SESSION_RUNTIME_SESSION.PENDING_OWNER_REEVALUATION),
            record.get(SESSION_RUNTIME_SESSION.DRAINING),
            jsonbSupport.readObjectMap(record.get(SESSION_RUNTIME_SESSION.SHARED_STATE)),
            JooqTimeSupport.toInstant(record.get(SESSION_RUNTIME_SESSION.IDLE_DEADLINE)),
            JooqTimeSupport.toInstant(record.get(SESSION_RUNTIME_SESSION.CREATED_AT)),
            JooqTimeSupport.toInstant(record.get(SESSION_RUNTIME_SESSION.UPDATED_AT)),
            JooqTimeSupport.toInstant(record.get(SESSION_RUNTIME_SESSION.ENDED_AT)),
            record.get(latestMessageSequence),
            record.get(latestEventSequence)
        );
    }

    private SessionEvent mapEvent(Record record) {
        return new SessionEvent(
            record.get(SESSION_RUNTIME_EVENT.EVENT_ID),
            record.get(SESSION_RUNTIME_EVENT.SESSION_ID),
            record.get(SESSION_RUNTIME_EVENT.SEQUENCE),
            SessionEventType.valueOf(record.get(SESSION_RUNTIME_EVENT.EVENT_TYPE)),
            JooqTimeSupport.toInstant(record.get(SESSION_RUNTIME_EVENT.CREATED_AT)),
            SessionActorType.valueOf(record.get(SESSION_RUNTIME_EVENT.ACTOR_TYPE)),
            record.get(SESSION_RUNTIME_EVENT.ACTOR_ID),
            jsonbSupport.readObjectMap(record.get(SESSION_RUNTIME_EVENT.PAYLOAD)),
            record.get(SESSION_RUNTIME_EVENT.RELATED_MESSAGE_ID),
            record.get(SESSION_RUNTIME_EVENT.RELATED_PLAYBOOK_RUN_ID),
            record.get(SESSION_RUNTIME_EVENT.RELATED_OWNER_AGENT_ID)
        );
    }

    private SessionMessage mapMessage(Record record) {
        return new SessionMessage(
            record.get(SESSION_RUNTIME_MESSAGE.MESSAGE_ID),
            record.get(SESSION_RUNTIME_MESSAGE.SESSION_ID),
            record.get(SESSION_RUNTIME_MESSAGE.SEQUENCE),
            SessionMessageRole.valueOf(record.get(SESSION_RUNTIME_MESSAGE.ROLE)),
            new SessionMessageSender(
                SessionMessageSenderType.valueOf(record.get(SESSION_RUNTIME_MESSAGE.SENDER_TYPE)),
                record.get(SESSION_RUNTIME_MESSAGE.SENDER_ID),
                record.get(SESSION_RUNTIME_MESSAGE.SENDER_NAME)
            ),
            SessionMessageStatus.valueOf(record.get(SESSION_RUNTIME_MESSAGE.STATUS)),
            jsonbSupport.readList(record.get(SESSION_RUNTIME_MESSAGE.BLOCKS)),
            jsonbSupport.readObjectMap(record.get(SESSION_RUNTIME_MESSAGE.METADATA)),
            record.get(SESSION_RUNTIME_MESSAGE.RELATED_PLAYBOOK_RUN_ID),
            record.get(SESSION_RUNTIME_MESSAGE.RELATED_OWNER_AGENT_ID),
            record.get(SESSION_RUNTIME_MESSAGE.SOURCE_EVENT_ID),
            JooqTimeSupport.toInstant(record.get(SESSION_RUNTIME_MESSAGE.CREATED_AT)),
            JooqTimeSupport.toInstant(record.get(SESSION_RUNTIME_MESSAGE.UPDATED_AT))
        );
    }

    private PlaybookRun mapPlaybookRun(Record record) {
        return new PlaybookRun(
            record.get(SESSION_RUNTIME_PLAYBOOK_RUN.RUN_ID),
            record.get(SESSION_RUNTIME_PLAYBOOK_RUN.SESSION_ID),
            record.get(SESSION_RUNTIME_PLAYBOOK_RUN.PARENT_SESSION_EVENT_ID),
            record.get(SESSION_RUNTIME_PLAYBOOK_RUN.PLAYBOOK_ID),
            record.get(SESSION_RUNTIME_PLAYBOOK_RUN.OWNER_AGENT_ID),
            PlaybookRunStatus.valueOf(record.get(SESSION_RUNTIME_PLAYBOOK_RUN.STATUS)),
            jsonbSupport.readObjectMap(record.get(SESSION_RUNTIME_PLAYBOOK_RUN.INPUT)),
            jsonbSupport.readObjectMap(record.get(SESSION_RUNTIME_PLAYBOOK_RUN.RESULT)),
            record.get(SESSION_RUNTIME_PLAYBOOK_RUN.FAILURE_REASON),
            JooqTimeSupport.toInstant(record.get(SESSION_RUNTIME_PLAYBOOK_RUN.CREATED_AT)),
            JooqTimeSupport.toInstant(record.get(SESSION_RUNTIME_PLAYBOOK_RUN.UPDATED_AT)),
            record.get(SESSION_RUNTIME_PLAYBOOK_RUN.WAITING_REASON)
        );
    }

    public record SessionRuntimeSessionData(
        String id,
        String scenarioId,
        String title,
        String customerId,
        String assistantId,
        String assistantName,
        String assistantReleaseVersion,
        String status,
        String primaryAgentId,
        String currentOwnerAgentId,
        String activePlaybookRunId,
        boolean agentTurnActive,
        boolean sessionHumanHandoffActive,
        boolean pendingOwnerReevaluation,
        boolean draining,
        Map<String, Object> sharedState,
        Instant idleDeadline,
        Instant createdAt,
        Instant updatedAt,
        Instant endedAt,
        long latestMessageSequence,
        long latestEventSequence
    ) {
    }

    public record SessionRuntimeChangeStamp(
        String sessionId,
        Instant sessionUpdatedAt,
        long latestMessageSequence,
        long latestEventSequence,
        Instant latestPlaybookRunUpdatedAt
    ) {
        public String fingerprint() {
            long sessionMillis = sessionUpdatedAt == null ? 0L : sessionUpdatedAt.toEpochMilli();
            long playbookMillis = latestPlaybookRunUpdatedAt == null ? 0L : latestPlaybookRunUpdatedAt.toEpochMilli();
            return sessionId + ":" + sessionMillis + ":" + latestMessageSequence + ":" + latestEventSequence + ":" + playbookMillis;
        }
    }
}
