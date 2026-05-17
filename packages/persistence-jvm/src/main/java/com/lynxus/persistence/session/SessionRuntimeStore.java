package com.lynxus.persistence.session;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.PlaybookRunStatus;
import com.lynxus.contracts.session.SessionContracts.SessionActorType;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import com.lynxus.contracts.session.SessionContracts.SessionEventType;
import com.lynxus.contracts.session.SessionContracts.SessionMessage;
import com.lynxus.contracts.session.SessionContracts.SessionMessageProducerType;
import com.lynxus.contracts.session.SessionContracts.SessionMessageRole;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSender;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSenderType;
import com.lynxus.contracts.session.SessionContracts.SessionMessageStatus;
import com.lynxus.persistence.jooqsupport.JooqJsonbSupport;
import com.lynxus.persistence.jooqsupport.JooqTimeSupport;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;

import static com.lynxus.persistence.jooq.Tables.SESSION_RUNTIME_TURN;
import static com.lynxus.persistence.jooq.Tables.SESSION_RUNTIME_EVENT;
import static com.lynxus.persistence.jooq.Tables.SESSION_RUNTIME_MESSAGE;
import static com.lynxus.persistence.jooq.Tables.SESSION_RUNTIME_PLAYBOOK_RUN;
import static com.lynxus.persistence.jooq.Tables.SESSION_RUNTIME_SESSION;
import static com.lynxus.persistence.jooq.Tables.CHANNEL_SESSION_BINDING_SNAPSHOT;

public final class SessionRuntimeStore {
    private static final List<String> OUTBOUND_MESSAGE_ROLES = List.of(
        SessionMessageRole.ASSISTANT.name(),
        SessionMessageRole.HUMAN_OPERATOR.name(),
        SessionMessageRole.SYSTEM.name()
    );
    private static final List<String> FINAL_MESSAGE_STATUSES = List.of(
        SessionMessageStatus.SENT.name(),
        SessionMessageStatus.DELIVERED.name()
    );

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
        return findActiveWebSession(customerId, assistantId);
    }

    public Optional<SessionRuntimeSessionData> findActiveWebSession(String customerId, String assistantId) {
        Field<Long> latestMessageSequence = latestMessageSequenceField();
        Field<Long> latestEventSequence = latestEventSequenceField();
        return dsl.select(SESSION_RUNTIME_SESSION.fields())
            .select(latestMessageSequence)
            .select(latestEventSequence)
            .from(SESSION_RUNTIME_SESSION)
            .where(SESSION_RUNTIME_SESSION.ENTRY_SCOPE.eq(SessionEntryScope.WEB.name()))
            .and(SESSION_RUNTIME_SESSION.CHANNEL_PROFILE_ID.isNull())
            .and(SESSION_RUNTIME_SESSION.EXTERNAL_CONVERSATION_ID.isNull())
            .and(SESSION_RUNTIME_SESSION.CUSTOMER_ID.eq(customerId))
            .and(SESSION_RUNTIME_SESSION.ASSISTANT_ID.eq(assistantId))
            .and(SESSION_RUNTIME_SESSION.STATUS.ne("ENDED"))
            .orderBy(SESSION_RUNTIME_SESSION.UPDATED_AT.desc(), SESSION_RUNTIME_SESSION.ID.desc())
            .limit(1)
            .fetchOptional(record -> mapSession(record, latestMessageSequence, latestEventSequence));
    }

    public Optional<SessionRuntimeSessionData> findActiveChannelSession(
        String channelProfileId,
        String externalConversationId,
        String customerId,
        String assistantId
    ) {
        Field<Long> latestMessageSequence = latestMessageSequenceField();
        Field<Long> latestEventSequence = latestEventSequenceField();
        return dsl.select(SESSION_RUNTIME_SESSION.fields())
            .select(latestMessageSequence)
            .select(latestEventSequence)
            .from(SESSION_RUNTIME_SESSION)
            .where(SESSION_RUNTIME_SESSION.ENTRY_SCOPE.eq(SessionEntryScope.CHANNEL.name()))
            .and(SESSION_RUNTIME_SESSION.CHANNEL_PROFILE_ID.eq(channelProfileId))
            .and(SESSION_RUNTIME_SESSION.EXTERNAL_CONVERSATION_ID.eq(externalConversationId))
            .and(SESSION_RUNTIME_SESSION.CUSTOMER_ID.eq(customerId))
            .and(SESSION_RUNTIME_SESSION.ASSISTANT_ID.eq(assistantId))
            .and(SESSION_RUNTIME_SESSION.STATUS.ne("ENDED"))
            .orderBy(SESSION_RUNTIME_SESSION.UPDATED_AT.desc(), SESSION_RUNTIME_SESSION.ID.desc())
            .limit(1)
            .fetchOptional(record -> mapSession(record, latestMessageSequence, latestEventSequence));
    }

    public SessionRuntimeSessionData createOrReuseActiveSession(SessionRuntimeSessionData initialSession) {
        validateActiveSessionIdentity(initialSession);
        Optional<SessionRuntimeSessionData> existing = findActiveSessionByIdentity(initialSession);
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            insertSession(initialSession);
        } catch (DataAccessException error) {
            Optional<SessionRuntimeSessionData> createdByConcurrentRequest = findActiveSessionByIdentity(initialSession);
            if (createdByConcurrentRequest.isPresent()) {
                return createdByConcurrentRequest.get();
            }
            throw error;
        }

        return findSession(initialSession.id())
            .orElseThrow(() -> new IllegalStateException("session was not persisted: " + initialSession.id()));
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
        validateActiveSessionIdentity(session);
        dsl.insertInto(SESSION_RUNTIME_SESSION)
            .set(SESSION_RUNTIME_SESSION.ID, session.id())
            .set(SESSION_RUNTIME_SESSION.SCENARIO_ID, session.scenarioId())
            .set(SESSION_RUNTIME_SESSION.TITLE, session.title())
            .set(SESSION_RUNTIME_SESSION.ENTRY_SCOPE, session.entryScope())
            .set(SESSION_RUNTIME_SESSION.CHANNEL_PROFILE_ID, session.channelProfileId())
            .set(SESSION_RUNTIME_SESSION.EXTERNAL_CONVERSATION_ID, session.externalConversationId())
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
            .set(SESSION_RUNTIME_SESSION.NEXT_MESSAGE_SEQUENCE, session.nextMessageSequence())
            .set(SESSION_RUNTIME_SESSION.SHARED_STATE_REVISION, session.sharedStateRevision())
            .set(SESSION_RUNTIME_SESSION.IDLE_DEADLINE, JooqTimeSupport.toOffsetDateTime(session.idleDeadline()))
            .set(SESSION_RUNTIME_SESSION.CREATED_AT, JooqTimeSupport.toOffsetDateTime(session.createdAt()))
            .set(SESSION_RUNTIME_SESSION.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(session.updatedAt()))
            .set(SESSION_RUNTIME_SESSION.ENDED_AT, JooqTimeSupport.toOffsetDateTime(session.endedAt()))
            .onConflict(SESSION_RUNTIME_SESSION.ID)
            .doUpdate()
            .set(SESSION_RUNTIME_SESSION.SCENARIO_ID, session.scenarioId())
            .set(SESSION_RUNTIME_SESSION.TITLE, session.title())
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
            .set(SESSION_RUNTIME_SESSION.SHARED_STATE_REVISION, session.sharedStateRevision())
            .set(SESSION_RUNTIME_SESSION.IDLE_DEADLINE, JooqTimeSupport.toOffsetDateTime(session.idleDeadline()))
            .set(SESSION_RUNTIME_SESSION.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(session.updatedAt()))
            .set(SESSION_RUNTIME_SESSION.ENDED_AT, JooqTimeSupport.toOffsetDateTime(session.endedAt()))
            .execute();
    }

    public void updateSessionProjection(SessionRuntimeSessionData session) {
        int updatedRows = dsl.update(SESSION_RUNTIME_SESSION)
            .set(SESSION_RUNTIME_SESSION.SCENARIO_ID, session.scenarioId())
            .set(SESSION_RUNTIME_SESSION.TITLE, session.title())
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
            .set(SESSION_RUNTIME_SESSION.SHARED_STATE_REVISION, session.sharedStateRevision())
            .set(SESSION_RUNTIME_SESSION.IDLE_DEADLINE, JooqTimeSupport.toOffsetDateTime(session.idleDeadline()))
            .set(SESSION_RUNTIME_SESSION.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(session.updatedAt()))
            .set(SESSION_RUNTIME_SESSION.ENDED_AT, JooqTimeSupport.toOffsetDateTime(session.endedAt()))
            .where(SESSION_RUNTIME_SESSION.ID.eq(session.id()))
            .execute();
        if (updatedRows == 0) {
            throw new IllegalStateException("session projection row does not exist: " + session.id());
        }
    }

    public Optional<SessionRuntimeTurnData> findTurn(String turnId) {
        return dsl.selectFrom(SESSION_RUNTIME_TURN)
            .where(SESSION_RUNTIME_TURN.TURN_ID.eq(turnId))
            .fetchOptional(this::mapTurn);
    }

    public Optional<SessionRuntimeTurnData> findTurnByDedupKey(String sessionId, String dedupKey) {
        return dsl.selectFrom(SESSION_RUNTIME_TURN)
            .where(SESSION_RUNTIME_TURN.SESSION_ID.eq(sessionId))
            .and(SESSION_RUNTIME_TURN.DEDUP_KEY.eq(dedupKey))
            .fetchOptional(this::mapTurn);
    }

    public SessionRuntimeTurnData createOrReuseTurn(SessionRuntimeTurnData turn) {
        Optional<SessionRuntimeTurnData> existing = findTurnByDedupKey(turn.sessionId(), turn.dedupKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            dsl.insertInto(SESSION_RUNTIME_TURN)
                .set(SESSION_RUNTIME_TURN.TURN_ID, turn.turnId())
                .set(SESSION_RUNTIME_TURN.SESSION_ID, turn.sessionId())
                .set(SESSION_RUNTIME_TURN.DEDUP_KEY, turn.dedupKey())
                .set(SESSION_RUNTIME_TURN.TRIGGER_TYPE, turn.triggerType())
                .set(SESSION_RUNTIME_TURN.STATUS, turn.status())
                .set(SESSION_RUNTIME_TURN.INPUT_ALLOCATIONS, jsonbSupport.toJsonb(turn.inputAllocations()))
                .set(SESSION_RUNTIME_TURN.ACCEPTED_INPUT_MESSAGE_IDS, jsonbSupport.toJsonb(turn.acceptedInputMessageIds()))
                .set(SESSION_RUNTIME_TURN.DUPLICATE_EXTERNAL_MESSAGE_IDS, jsonbSupport.toJsonb(turn.duplicateExternalMessageIds()))
                .set(SESSION_RUNTIME_TURN.MESSAGE_IDS, jsonbSupport.toJsonb(turn.messageIds()))
                .set(SESSION_RUNTIME_TURN.TEMPORAL_UPDATE_ID, turn.temporalUpdateId())
                .set(SESSION_RUNTIME_TURN.METADATA, jsonbSupport.toJsonb(turn.metadata()))
                .set(SESSION_RUNTIME_TURN.CREATED_AT, JooqTimeSupport.toOffsetDateTime(turn.createdAt()))
                .set(SESSION_RUNTIME_TURN.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(turn.updatedAt()))
                .set(SESSION_RUNTIME_TURN.COMPLETED_AT, JooqTimeSupport.toOffsetDateTime(turn.completedAt()))
                .execute();
        } catch (DataAccessException error) {
            Optional<SessionRuntimeTurnData> createdByConcurrentRequest = findTurnByDedupKey(turn.sessionId(), turn.dedupKey());
            if (createdByConcurrentRequest.isPresent()) {
                return createdByConcurrentRequest.get();
            }
            throw error;
        }

        return findTurn(turn.turnId())
            .orElseThrow(() -> new IllegalStateException("turn was not persisted: " + turn.turnId()));
    }

    public SessionRuntimeTurnData allocatePlatformTurn(
        String sessionId,
        String triggerType,
        String dedupKey,
        String sourceEventId,
        Map<String, Object> metadata
    ) {
        String effectiveSessionId = requireText(sessionId, "sessionId");
        String effectiveTriggerType = requireText(triggerType, "triggerType");
        String rawDedupKey = requireText(dedupKey, "dedupKey");
        String effectiveDedupKey = platformDedupKey(effectiveTriggerType, rawDedupKey);
        Map<String, Object> turnMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            turnMetadata.putAll(metadata);
        }
        turnMetadata.put("platformDedupKey", rawDedupKey);
        if (sourceEventId != null && !sourceEventId.isBlank()) {
            turnMetadata.put("sourceEventId", sourceEventId.trim());
        }
        Instant now = Instant.now();
        SessionRuntimeTurnData turn = createOrReuseTurn(new SessionRuntimeTurnData(
            nextTurnId(),
            effectiveSessionId,
            effectiveDedupKey,
            effectiveTriggerType,
            SessionRuntimeTurnStatus.ALLOCATED_IDS.name(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            turnMetadata,
            now,
            now,
            null
        ));
        validatePlatformTurnReuse(turn, effectiveTriggerType);
        return turn;
    }

    public SessionRuntimeTurnData updateTurnState(
        String sessionId,
        String turnId,
        String status,
        List<String> acceptedInputMessageIds,
        List<String> duplicateExternalMessageIds,
        List<String> messageIds,
        String temporalUpdateId,
        Instant completedAt
    ) {
        int updatedRows = dsl.update(SESSION_RUNTIME_TURN)
            .set(SESSION_RUNTIME_TURN.STATUS, status)
            .set(SESSION_RUNTIME_TURN.ACCEPTED_INPUT_MESSAGE_IDS, jsonbSupport.toJsonb(acceptedInputMessageIds == null ? List.of() : acceptedInputMessageIds))
            .set(SESSION_RUNTIME_TURN.DUPLICATE_EXTERNAL_MESSAGE_IDS, jsonbSupport.toJsonb(duplicateExternalMessageIds == null ? List.of() : duplicateExternalMessageIds))
            .set(SESSION_RUNTIME_TURN.MESSAGE_IDS, jsonbSupport.toJsonb(messageIds == null ? List.of() : messageIds))
            .set(SESSION_RUNTIME_TURN.TEMPORAL_UPDATE_ID, temporalUpdateId)
            .set(SESSION_RUNTIME_TURN.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(Instant.now()))
            .set(SESSION_RUNTIME_TURN.COMPLETED_AT, JooqTimeSupport.toOffsetDateTime(completedAt))
            .where(SESSION_RUNTIME_TURN.TURN_ID.eq(turnId))
            .and(SESSION_RUNTIME_TURN.SESSION_ID.eq(sessionId))
            .execute();
        if (updatedRows == 0) {
            throw new IllegalArgumentException("turn does not exist for session: " + turnId);
        }
        return findTurn(turnId)
            .orElseThrow(() -> new IllegalStateException("turn was not persisted: " + turnId));
    }

    public List<SessionMessage> appendSessionMessages(
        String sessionId,
        String turnId,
        List<SessionMessageAppendData> messages
    ) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return dsl.transactionResult(configuration -> {
            DSLContext tx = DSL.using(configuration);
            Long nextMessageSequence = tx.select(SESSION_RUNTIME_SESSION.NEXT_MESSAGE_SEQUENCE)
                .from(SESSION_RUNTIME_SESSION)
                .where(SESSION_RUNTIME_SESSION.ID.eq(sessionId))
                .forUpdate()
                .fetchOne(SESSION_RUNTIME_SESSION.NEXT_MESSAGE_SEQUENCE);
            if (nextMessageSequence == null) {
                throw new IllegalArgumentException("session does not exist: " + sessionId);
            }

            var turnRecord = tx.selectFrom(SESSION_RUNTIME_TURN)
                .where(SESSION_RUNTIME_TURN.TURN_ID.eq(turnId))
                .and(SESSION_RUNTIME_TURN.SESSION_ID.eq(sessionId))
                .forUpdate()
                .fetchOne();
            if (turnRecord == null) {
                throw new IllegalArgumentException("turn does not exist for session: " + turnId);
            }

            List<String> requestedMessageIds = messages.stream().map(SessionMessageAppendData::messageId).toList();
            List<SessionMessage> existingMessages = tx.selectFrom(SESSION_RUNTIME_MESSAGE)
                .where(SESSION_RUNTIME_MESSAGE.SESSION_ID.eq(sessionId))
                .and(SESSION_RUNTIME_MESSAGE.TURN_ID.eq(turnId))
                .and(SESSION_RUNTIME_MESSAGE.MESSAGE_ID.in(requestedMessageIds))
                .orderBy(SESSION_RUNTIME_MESSAGE.TURN_INDEX.asc())
                .fetch(this::mapMessage);
            if (!existingMessages.isEmpty()) {
                if (existingMessages.size() != messages.size()) {
                    throw new IllegalStateException("partial append detected for turn: " + turnId);
                }
                refreshTurnMessageIds(tx, sessionId, turnId);
                return existingMessages;
            }

            Integer maxTurnIndex = tx.select(DSL.max(SESSION_RUNTIME_MESSAGE.TURN_INDEX))
                .from(SESSION_RUNTIME_MESSAGE)
                .where(SESSION_RUNTIME_MESSAGE.SESSION_ID.eq(sessionId))
                .and(SESSION_RUNTIME_MESSAGE.TURN_ID.eq(turnId))
                .fetchOne(0, Integer.class);
            int nextTurnIndex = maxTurnIndex == null ? 0 : maxTurnIndex + 1;
            Instant now = Instant.now();
            List<SessionMessage> appended = new ArrayList<>(messages.size());

            for (int index = 0; index < messages.size(); index++) {
                SessionMessageAppendData input = messages.get(index);
                long sequence = nextMessageSequence + index;
                int turnIndex = nextTurnIndex + index;
                Instant createdAt = input.createdAt() == null ? now : input.createdAt();
                Instant updatedAt = input.updatedAt() == null ? createdAt : input.updatedAt();
                tx.insertInto(SESSION_RUNTIME_MESSAGE)
                    .set(SESSION_RUNTIME_MESSAGE.MESSAGE_ID, input.messageId())
                    .set(SESSION_RUNTIME_MESSAGE.SESSION_ID, sessionId)
                    .set(SESSION_RUNTIME_MESSAGE.SEQUENCE, sequence)
                    .set(SESSION_RUNTIME_MESSAGE.TURN_ID, turnId)
                    .set(SESSION_RUNTIME_MESSAGE.TURN_INDEX, turnIndex)
                    .set(SESSION_RUNTIME_MESSAGE.PRODUCER_TYPE, input.producerType().name())
                    .set(SESSION_RUNTIME_MESSAGE.EXTERNAL_MESSAGE_ID, input.externalMessageId())
                    .set(SESSION_RUNTIME_MESSAGE.CLIENT_MESSAGE_ID, input.clientMessageId())
                    .set(SESSION_RUNTIME_MESSAGE.OCCURRED_AT, JooqTimeSupport.toOffsetDateTime(input.occurredAt()))
                    .set(SESSION_RUNTIME_MESSAGE.ROLE, input.role().name())
                    .set(SESSION_RUNTIME_MESSAGE.SENDER_TYPE, input.sender().senderType().name())
                    .set(SESSION_RUNTIME_MESSAGE.SENDER_ID, input.sender().senderId())
                    .set(SESSION_RUNTIME_MESSAGE.SENDER_NAME, input.sender().senderName())
                    .set(SESSION_RUNTIME_MESSAGE.STATUS, input.status().name())
                    .set(SESSION_RUNTIME_MESSAGE.BLOCKS, jsonbSupport.toJsonb(input.blocks()))
                    .set(SESSION_RUNTIME_MESSAGE.METADATA, jsonbSupport.toJsonb(input.metadata()))
                    .set(SESSION_RUNTIME_MESSAGE.RELATED_PLAYBOOK_RUN_ID, input.relatedPlaybookRunId())
                    .set(SESSION_RUNTIME_MESSAGE.RELATED_OWNER_AGENT_ID, input.relatedOwnerAgentId())
                    .set(SESSION_RUNTIME_MESSAGE.SOURCE_EVENT_ID, input.sourceEventId())
                    .set(SESSION_RUNTIME_MESSAGE.CREATED_AT, JooqTimeSupport.toOffsetDateTime(createdAt))
                    .set(SESSION_RUNTIME_MESSAGE.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(updatedAt))
                    .execute();
                appended.add(new SessionMessage(
                    input.messageId(),
                    sessionId,
                    sequence,
                    turnId,
                    turnIndex,
                    input.producerType(),
                    input.externalMessageId(),
                    input.clientMessageId(),
                    input.occurredAt(),
                    input.role(),
                    input.sender(),
                    input.status(),
                    input.blocks(),
                    input.metadata(),
                    input.relatedPlaybookRunId(),
                    input.relatedOwnerAgentId(),
                    input.sourceEventId(),
                    createdAt,
                    updatedAt
                ));
            }

            tx.update(SESSION_RUNTIME_SESSION)
                .set(SESSION_RUNTIME_SESSION.NEXT_MESSAGE_SEQUENCE, nextMessageSequence + messages.size())
                .where(SESSION_RUNTIME_SESSION.ID.eq(sessionId))
                .execute();
            refreshTurnMessageIds(tx, sessionId, turnId);
            return appended;
        });
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

    public List<SessionMessage> listMessagesForTurn(String sessionId, String turnId) {
        return dsl.selectFrom(SESSION_RUNTIME_MESSAGE)
            .where(SESSION_RUNTIME_MESSAGE.SESSION_ID.eq(sessionId))
            .and(SESSION_RUNTIME_MESSAGE.TURN_ID.eq(turnId))
            .orderBy(SESSION_RUNTIME_MESSAGE.TURN_INDEX.asc(), SESSION_RUNTIME_MESSAGE.SEQUENCE.asc())
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

    private Optional<SessionRuntimeSessionData> findActiveSessionByIdentity(SessionRuntimeSessionData session) {
        if (SessionEntryScope.CHANNEL.name().equals(session.entryScope())) {
            return findActiveChannelSession(
                session.channelProfileId(),
                session.externalConversationId(),
                session.customerId(),
                session.assistantId()
            );
        }
        return findActiveWebSession(session.customerId(), session.assistantId());
    }

    private void insertSession(SessionRuntimeSessionData session) {
        dsl.insertInto(SESSION_RUNTIME_SESSION)
            .set(SESSION_RUNTIME_SESSION.ID, session.id())
            .set(SESSION_RUNTIME_SESSION.SCENARIO_ID, session.scenarioId())
            .set(SESSION_RUNTIME_SESSION.TITLE, session.title())
            .set(SESSION_RUNTIME_SESSION.ENTRY_SCOPE, session.entryScope())
            .set(SESSION_RUNTIME_SESSION.CHANNEL_PROFILE_ID, session.channelProfileId())
            .set(SESSION_RUNTIME_SESSION.EXTERNAL_CONVERSATION_ID, session.externalConversationId())
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
            .set(SESSION_RUNTIME_SESSION.SHARED_STATE, jsonbSupport.toJsonb(session.sharedState()))
            .set(SESSION_RUNTIME_SESSION.IDLE_DEADLINE, JooqTimeSupport.toOffsetDateTime(session.idleDeadline()))
            .set(SESSION_RUNTIME_SESSION.NEXT_MESSAGE_SEQUENCE, session.nextMessageSequence())
            .set(SESSION_RUNTIME_SESSION.SHARED_STATE_REVISION, session.sharedStateRevision())
            .set(SESSION_RUNTIME_SESSION.CREATED_AT, JooqTimeSupport.toOffsetDateTime(session.createdAt()))
            .set(SESSION_RUNTIME_SESSION.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(session.updatedAt()))
            .set(SESSION_RUNTIME_SESSION.ENDED_AT, JooqTimeSupport.toOffsetDateTime(session.endedAt()))
            .execute();
    }

    private void refreshTurnMessageIds(DSLContext tx, String sessionId, String turnId) {
        List<String> messageIds = tx.select(SESSION_RUNTIME_MESSAGE.MESSAGE_ID)
            .from(SESSION_RUNTIME_MESSAGE)
            .where(SESSION_RUNTIME_MESSAGE.SESSION_ID.eq(sessionId))
            .and(SESSION_RUNTIME_MESSAGE.TURN_ID.eq(turnId))
            .orderBy(SESSION_RUNTIME_MESSAGE.TURN_INDEX.asc())
            .fetch(SESSION_RUNTIME_MESSAGE.MESSAGE_ID);
        tx.update(SESSION_RUNTIME_TURN)
            .set(SESSION_RUNTIME_TURN.MESSAGE_IDS, jsonbSupport.toJsonb(messageIds))
            .set(
                SESSION_RUNTIME_TURN.STATUS,
                DSL.when(SESSION_RUNTIME_TURN.STATUS.eq(SessionRuntimeTurnStatus.ALLOCATED_IDS.name()),
                        SessionRuntimeTurnStatus.MESSAGES_APPENDED.name())
                    .otherwise(SESSION_RUNTIME_TURN.STATUS)
            )
            .set(SESSION_RUNTIME_TURN.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(Instant.now()))
            .where(SESSION_RUNTIME_TURN.TURN_ID.eq(turnId))
            .and(SESSION_RUNTIME_TURN.SESSION_ID.eq(sessionId))
            .execute();
    }

    private SessionRuntimeTurnData mapTurn(Record record) {
        return new SessionRuntimeTurnData(
            record.get(SESSION_RUNTIME_TURN.TURN_ID),
            record.get(SESSION_RUNTIME_TURN.SESSION_ID),
            record.get(SESSION_RUNTIME_TURN.DEDUP_KEY),
            record.get(SESSION_RUNTIME_TURN.TRIGGER_TYPE),
            record.get(SESSION_RUNTIME_TURN.STATUS),
            readObjectList(record.get(SESSION_RUNTIME_TURN.INPUT_ALLOCATIONS)),
            readStringList(record.get(SESSION_RUNTIME_TURN.ACCEPTED_INPUT_MESSAGE_IDS)),
            readStringList(record.get(SESSION_RUNTIME_TURN.DUPLICATE_EXTERNAL_MESSAGE_IDS)),
            readStringList(record.get(SESSION_RUNTIME_TURN.MESSAGE_IDS)),
            record.get(SESSION_RUNTIME_TURN.TEMPORAL_UPDATE_ID),
            jsonbSupport.readObjectMap(record.get(SESSION_RUNTIME_TURN.METADATA)),
            JooqTimeSupport.toInstant(record.get(SESSION_RUNTIME_TURN.CREATED_AT)),
            JooqTimeSupport.toInstant(record.get(SESSION_RUNTIME_TURN.UPDATED_AT)),
            JooqTimeSupport.toInstant(record.get(SESSION_RUNTIME_TURN.COMPLETED_AT))
        );
    }

    private List<Map<String, Object>> readObjectList(JSONB value) {
        List<Object> raw = jsonbSupport.readList(value);
        if (raw.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalStateException("expected json object in list but got: " + item);
            }
            items.add(copyObjectMap(map));
        }
        return Collections.unmodifiableList(items);
    }

    private List<String> readStringList(JSONB value) {
        List<Object> raw = jsonbSupport.readList(value);
        if (raw.isEmpty()) {
            return List.of();
        }
        return raw.stream().map(String::valueOf).toList();
    }

    private static Map<String, Object> copyObjectMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static void validateActiveSessionIdentity(SessionRuntimeSessionData session) {
        requireText(session.id(), "session.id");
        requireText(session.entryScope(), "session.entryScope");
        requireText(session.customerId(), "session.customerId");
        requireText(session.assistantId(), "session.assistantId");
        if (SessionEntryScope.CHANNEL.name().equals(session.entryScope())) {
            requireText(session.channelProfileId(), "session.channelProfileId");
            requireText(session.externalConversationId(), "session.externalConversationId");
            return;
        }
        if (!SessionEntryScope.WEB.name().equals(session.entryScope())) {
            throw new IllegalArgumentException("unsupported session entry scope: " + session.entryScope());
        }
        if (session.channelProfileId() != null || session.externalConversationId() != null) {
            throw new IllegalArgumentException("web session identity cannot include channel fields");
        }
    }

    public List<ChannelOutboundFinalMessageData> listChannelOutboundFinalMessages(
        String channelProfileId,
        long afterFinalSequence,
        int limit
    ) {
        Field<Integer> blockCount = DSL.field(
            "jsonb_array_length({0})",
            Integer.class,
            SESSION_RUNTIME_MESSAGE.BLOCKS
        );
        return dsl.select(
                SESSION_RUNTIME_MESSAGE.MESSAGE_ID,
                SESSION_RUNTIME_MESSAGE.SESSION_ID,
                SESSION_RUNTIME_MESSAGE.SEQUENCE,
                SESSION_RUNTIME_MESSAGE.ROLE,
                SESSION_RUNTIME_MESSAGE.BLOCKS,
                SESSION_RUNTIME_MESSAGE.METADATA,
                SESSION_RUNTIME_MESSAGE.CREATED_AT,
                SESSION_RUNTIME_MESSAGE.UPDATED_AT,
                SESSION_RUNTIME_MESSAGE.FINAL_SEQUENCE,
                CHANNEL_SESSION_BINDING_SNAPSHOT.CHANNEL_PROFILE_ID,
                CHANNEL_SESSION_BINDING_SNAPSHOT.PROVIDER_TYPE,
                CHANNEL_SESSION_BINDING_SNAPSHOT.EXTERNAL_CONVERSATION_ID,
                CHANNEL_SESSION_BINDING_SNAPSHOT.ASSISTANT_ID
            )
            .from(SESSION_RUNTIME_MESSAGE)
            .join(SESSION_RUNTIME_SESSION)
            .on(SESSION_RUNTIME_SESSION.ID.eq(SESSION_RUNTIME_MESSAGE.SESSION_ID))
            .join(CHANNEL_SESSION_BINDING_SNAPSHOT)
            .on(CHANNEL_SESSION_BINDING_SNAPSHOT.SESSION_ID.eq(SESSION_RUNTIME_SESSION.ID))
            .where(CHANNEL_SESSION_BINDING_SNAPSHOT.CHANNEL_PROFILE_ID.eq(requireText(channelProfileId, "channelProfileId")))
            .and(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_STATUS.eq("ACTIVE"))
            .and(CHANNEL_SESSION_BINDING_SNAPSHOT.PROFILE_STATUS.eq("ACTIVE"))
            .and(SESSION_RUNTIME_SESSION.ENTRY_SCOPE.eq(SessionEntryScope.CHANNEL.name()))
            .and(SESSION_RUNTIME_SESSION.STATUS.ne("ENDED"))
            .and(SESSION_RUNTIME_SESSION.CHANNEL_PROFILE_ID.eq(CHANNEL_SESSION_BINDING_SNAPSHOT.CHANNEL_PROFILE_ID))
            .and(SESSION_RUNTIME_SESSION.EXTERNAL_CONVERSATION_ID.eq(CHANNEL_SESSION_BINDING_SNAPSHOT.EXTERNAL_CONVERSATION_ID))
            .and(SESSION_RUNTIME_SESSION.CUSTOMER_ID.eq(CHANNEL_SESSION_BINDING_SNAPSHOT.CUSTOMER_ID))
            .and(SESSION_RUNTIME_SESSION.ASSISTANT_ID.eq(CHANNEL_SESSION_BINDING_SNAPSHOT.ASSISTANT_ID))
            .and(SESSION_RUNTIME_MESSAGE.FINAL_SEQUENCE.gt(afterFinalSequence))
            .and(SESSION_RUNTIME_MESSAGE.PRODUCER_TYPE.eq(SessionMessageProducerType.PLATFORM.name()))
            .and(SESSION_RUNTIME_MESSAGE.ROLE.in(OUTBOUND_MESSAGE_ROLES))
            .and(SESSION_RUNTIME_MESSAGE.STATUS.in(FINAL_MESSAGE_STATUSES))
            .and(blockCount.gt(0))
            .orderBy(SESSION_RUNTIME_MESSAGE.FINAL_SEQUENCE.asc())
            .limit(Math.max(0, limit))
            .fetch(this::mapChannelOutboundFinalMessage);
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
            record.get(SESSION_RUNTIME_SESSION.ENTRY_SCOPE),
            record.get(SESSION_RUNTIME_SESSION.CHANNEL_PROFILE_ID),
            record.get(SESSION_RUNTIME_SESSION.EXTERNAL_CONVERSATION_ID),
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
            record.get(SESSION_RUNTIME_SESSION.NEXT_MESSAGE_SEQUENCE),
            record.get(SESSION_RUNTIME_SESSION.SHARED_STATE_REVISION),
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
            record.get(SESSION_RUNTIME_MESSAGE.TURN_ID),
            record.get(SESSION_RUNTIME_MESSAGE.TURN_INDEX),
            SessionMessageProducerType.valueOf(record.get(SESSION_RUNTIME_MESSAGE.PRODUCER_TYPE)),
            record.get(SESSION_RUNTIME_MESSAGE.EXTERNAL_MESSAGE_ID),
            record.get(SESSION_RUNTIME_MESSAGE.CLIENT_MESSAGE_ID),
            JooqTimeSupport.toInstant(record.get(SESSION_RUNTIME_MESSAGE.OCCURRED_AT)),
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

    private ChannelOutboundFinalMessageData mapChannelOutboundFinalMessage(Record record) {
        return new ChannelOutboundFinalMessageData(
            record.get(SESSION_RUNTIME_MESSAGE.FINAL_SEQUENCE),
            record.get(SESSION_RUNTIME_MESSAGE.MESSAGE_ID),
            record.get(SESSION_RUNTIME_MESSAGE.SESSION_ID),
            record.get(SESSION_RUNTIME_MESSAGE.SEQUENCE),
            SessionMessageRole.valueOf(record.get(SESSION_RUNTIME_MESSAGE.ROLE)),
            jsonbSupport.readList(record.get(SESSION_RUNTIME_MESSAGE.BLOCKS)),
            jsonbSupport.readObjectMap(record.get(SESSION_RUNTIME_MESSAGE.METADATA)),
            JooqTimeSupport.toInstant(record.get(SESSION_RUNTIME_MESSAGE.CREATED_AT)),
            JooqTimeSupport.toInstant(record.get(SESSION_RUNTIME_MESSAGE.UPDATED_AT)),
            record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.CHANNEL_PROFILE_ID),
            record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.PROVIDER_TYPE),
            record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.EXTERNAL_CONVERSATION_ID),
            record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.ASSISTANT_ID)
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String platformDedupKey(String triggerType, String rawDedupKey) {
        return "platform:" + triggerType + ":" + rawDedupKey;
    }

    private static void validatePlatformTurnReuse(SessionRuntimeTurnData turn, String triggerType) {
        if (!triggerType.equals(turn.triggerType())) {
            throw new IllegalStateException(
                "platform turn dedup key collision: triggerType mismatch for " + turn.dedupKey()
            );
        }
        if (!turn.inputAllocations().isEmpty()
            || !turn.acceptedInputMessageIds().isEmpty()
            || !turn.duplicateExternalMessageIds().isEmpty()) {
            throw new IllegalStateException(
                "platform turn dedup key collision: existing turn has external input allocation for " + turn.dedupKey()
            );
        }
    }

    private static String nextTurnId() {
        return "session-turn-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public enum SessionEntryScope {
        WEB,
        CHANNEL
    }

    public enum SessionRuntimeTurnStatus {
        ALLOCATED_IDS,
        MESSAGES_APPENDED,
        WORKFLOW_ACCEPTED,
        REJECTED,
        FAILED
    }

    public record SessionRuntimeSessionData(
        String id,
        String scenarioId,
        String title,
        String entryScope,
        String channelProfileId,
        String externalConversationId,
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
        long nextMessageSequence,
        long sharedStateRevision,
        Instant idleDeadline,
        Instant createdAt,
        Instant updatedAt,
        Instant endedAt,
        long latestMessageSequence,
        long latestEventSequence
    ) {
        public SessionRuntimeSessionData {
            entryScope = entryScope == null ? SessionEntryScope.WEB.name() : entryScope;
            sharedState = immutableObjectMap(sharedState);
            nextMessageSequence = Math.max(1L, nextMessageSequence);
        }

        public SessionRuntimeSessionData(
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
            this(
                id,
                scenarioId,
                title,
                SessionEntryScope.WEB.name(),
                null,
                null,
                customerId,
                assistantId,
                assistantName,
                assistantReleaseVersion,
                status,
                primaryAgentId,
                currentOwnerAgentId,
                activePlaybookRunId,
                agentTurnActive,
                sessionHumanHandoffActive,
                pendingOwnerReevaluation,
                draining,
                sharedState,
                Math.max(1L, latestMessageSequence + 1L),
                0L,
                idleDeadline,
                createdAt,
                updatedAt,
                endedAt,
                latestMessageSequence,
                latestEventSequence
            );
        }
    }

    public record SessionRuntimeTurnData(
        String turnId,
        String sessionId,
        String dedupKey,
        String triggerType,
        String status,
        List<Map<String, Object>> inputAllocations,
        List<String> acceptedInputMessageIds,
        List<String> duplicateExternalMessageIds,
        List<String> messageIds,
        String temporalUpdateId,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
    ) {
        public SessionRuntimeTurnData {
            inputAllocations = inputAllocations == null
                ? List.of()
                : inputAllocations.stream()
                    .map(SessionRuntimeStore::immutableObjectMap)
                    .toList();
            acceptedInputMessageIds = acceptedInputMessageIds == null ? List.of() : List.copyOf(acceptedInputMessageIds);
            duplicateExternalMessageIds = duplicateExternalMessageIds == null ? List.of() : List.copyOf(duplicateExternalMessageIds);
            messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
            metadata = immutableObjectMap(metadata);
        }
    }

    public record SessionMessageAppendData(
        String messageId,
        SessionMessageProducerType producerType,
        String externalMessageId,
        String clientMessageId,
        Instant occurredAt,
        SessionMessageRole role,
        SessionMessageSender sender,
        SessionMessageStatus status,
        List<Object> blocks,
        Map<String, Object> metadata,
        String relatedPlaybookRunId,
        String relatedOwnerAgentId,
        String sourceEventId,
        Instant createdAt,
        Instant updatedAt
    ) {
        public SessionMessageAppendData {
            requireText(messageId, "messageId");
            if (producerType == null) {
                throw new IllegalArgumentException("producerType is required");
            }
            if (role == null) {
                throw new IllegalArgumentException("role is required");
            }
            if (sender == null) {
                throw new IllegalArgumentException("sender is required");
            }
            if (status == null) {
                throw new IllegalArgumentException("status is required");
            }
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            metadata = immutableObjectMap(metadata);
        }
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

    public record ChannelOutboundFinalMessageData(
        long finalSequence,
        String messageId,
        String sessionId,
        long messageSequence,
        SessionMessageRole role,
        List<Object> blocks,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt,
        String channelProfileId,
        String providerType,
        String externalConversationId,
        String assistantId
    ) {
        public ChannelOutboundFinalMessageData {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
