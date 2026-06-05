package com.agentyard.worker.session;

import com.agentyard.contracts.session.SessionContracts.PlaybookRun;
import com.agentyard.contracts.session.SessionContracts.SessionEvent;
import com.agentyard.contracts.session.SessionContracts.SessionMessage;
import com.agentyard.persistence.event.PlatformEventStore;
import com.agentyard.persistence.jooqsupport.JooqJsonbSupport;
import com.agentyard.persistence.session.SessionRuntimeStore;
import com.agentyard.persistence.usage.LlmUsageStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JooqSessionProjectionRepository {
    private final SessionRuntimeStore sessionStore;
    private final PlatformEventStore platformEventStore;
    private final LlmUsageStore llmUsageStore;

    public JooqSessionProjectionRepository(DSLContext dsl, ObjectMapper objectMapper) {
        JooqJsonbSupport jsonbSupport = new JooqJsonbSupport(objectMapper);
        this.sessionStore = new SessionRuntimeStore(dsl, jsonbSupport);
        this.platformEventStore = new PlatformEventStore(dsl, jsonbSupport);
        this.llmUsageStore = new LlmUsageStore(dsl, jsonbSupport);
    }

    public void saveSession(SessionPersistenceActivities.SessionRecord session) {
        sessionStore.updateSessionProjection(new SessionRuntimeStore.SessionRuntimeSessionData(
            session.id(),
            session.scenarioId(),
            session.title(),
            null,
            null,
            null,
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
            session.sharedState(),
            1L,
            session.sharedStateRevision(),
            session.idleDeadline(),
            session.createdAt(),
            session.updatedAt(),
            session.endedAt(),
            0L,
            0L
        ));
    }

    public Optional<SessionRuntimeChangeStamp> findSessionChangeStamp(String sessionId) {
        return sessionStore.findSessionChangeStamp(sessionId)
            .map(item -> new SessionRuntimeChangeStamp(
                item.sessionId(),
                item.sessionUpdatedAt(),
                item.latestMessageSequence(),
                item.latestEventSequence(),
                item.latestPlaybookRunUpdatedAt()
            ));
    }

    public List<SessionMessage> appendSessionMessages(
        String sessionId,
        String turnId,
        List<SessionPersistenceActivities.SessionMessageAppendRecord> messages
    ) {
        return sessionStore.appendSessionMessages(
            sessionId,
            turnId,
            messages.stream()
                .map(message -> new SessionRuntimeStore.SessionMessageAppendData(
                    message.messageId(),
                    message.producerType(),
                    message.externalMessageId(),
                    message.clientMessageId(),
                    message.occurredAt(),
                    message.role(),
                    message.sender(),
                    message.status(),
                    message.blocks(),
                    message.metadata(),
                    message.relatedPlaybookRunId(),
                    message.relatedOwnerAgentId(),
                    message.sourceEventId(),
                    message.createdAt(),
                    message.updatedAt()
                ))
                .toList()
        );
    }

    public SessionRuntimeStore.SessionRuntimeTurnData allocatePlatformTurn(
        String sessionId,
        String triggerType,
        String dedupKey,
        String sourceEventId,
        java.util.Map<String, Object> metadata
    ) {
        return sessionStore.allocatePlatformTurn(sessionId, triggerType, dedupKey, sourceEventId, metadata);
    }

    public void appendEvent(SessionEvent event) {
        sessionStore.appendEvent(event);
    }

    public void appendPlatformEvent(SessionPersistenceActivities.PlatformEventRecord event) {
        platformEventStore.append(new PlatformEventStore.PlatformEventData(
            event.id(),
            event.eventType(),
            event.aggregateType(),
            event.aggregateId(),
            event.actorId(),
            event.payload(),
            event.occurredAt()
        ));
    }

    public void appendLlmUsage(List<SessionPersistenceActivities.LlmUsageRecord> records) {
        llmUsageStore.append(records.stream()
            .map(record -> new LlmUsageStore.LlmUsageData(
                record.id(),
                record.sourceType(),
                record.sessionId(),
                record.triggerEventId(),
                record.triggerType(),
                record.playbookRunId(),
                record.scenarioId(),
                record.customerId(),
                record.assistantId(),
                record.assistantReleaseVersion(),
                record.agentId(),
                record.providerType(),
                record.modelResourceId(),
                record.modelResourceVersionId(),
                record.modelId(),
                record.usageAvailable(),
                record.promptTokens(),
                record.completionTokens(),
                record.totalTokens(),
                record.rawUsage(),
                record.callSequence(),
                record.toolLoopStep(),
                record.occurredAt()
            ))
            .toList());
    }

    public void savePlaybookRun(PlaybookRun playbookRun) {
        sessionStore.savePlaybookRun(playbookRun);
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
