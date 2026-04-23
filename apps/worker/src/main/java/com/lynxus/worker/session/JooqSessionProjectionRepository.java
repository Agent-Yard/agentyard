package com.lynxus.worker.session;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import com.lynxus.contracts.session.SessionContracts.SessionMessage;
import com.lynxus.persistence.event.PlatformEventStore;
import com.lynxus.persistence.jooqsupport.JooqJsonbSupport;
import com.lynxus.persistence.session.SessionRuntimeStore;
import com.lynxus.persistence.usage.LlmUsageStore;
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
        sessionStore.saveSession(new SessionRuntimeStore.SessionRuntimeSessionData(
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
            session.sharedState(),
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

    public void appendMessage(SessionMessage message) {
        sessionStore.appendMessage(message);
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
