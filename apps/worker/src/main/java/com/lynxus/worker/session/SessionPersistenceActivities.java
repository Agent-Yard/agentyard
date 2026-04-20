package com.lynxus.worker.session;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import io.temporal.activity.ActivityInterface;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@ActivityInterface
public interface SessionPersistenceActivities {
    void saveSession(SessionRecord session);

    void appendEvent(SessionEvent event);

    void appendPlatformEvent(PlatformEventRecord event);

    void savePlaybookRun(PlaybookRun playbookRun);

    record SessionRecord(
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
        Instant endedAt
    ) {
        public SessionRecord {
            sharedState = immutableObjectMap(sharedState);
        }
    }

    record PlatformEventRecord(
        String id,
        String eventType,
        String aggregateType,
        String aggregateId,
        String actorId,
        Map<String, Object> payload,
        Instant occurredAt
    ) {
        public PlatformEventRecord {
            payload = immutableObjectMap(payload);
        }
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
