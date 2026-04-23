package com.lynxus.worker.session;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import com.lynxus.contracts.session.SessionContracts.SessionMessage;
import io.temporal.activity.ActivityInterface;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@ActivityInterface
public interface SessionPersistenceActivities {
    void saveSession(SessionRecord session);

    void appendMessage(SessionMessage message);

    void appendEvent(SessionEvent event);

    void appendPlatformEvent(PlatformEventRecord event);

    void appendLlmUsage(java.util.List<LlmUsageRecord> records);

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

    record LlmUsageRecord(
        String id,
        String sourceType,
        String sessionId,
        String triggerEventId,
        String triggerType,
        String playbookRunId,
        String scenarioId,
        String customerId,
        String assistantId,
        String assistantReleaseVersion,
        String agentId,
        String providerType,
        String modelResourceId,
        String modelResourceVersionId,
        String modelId,
        boolean usageAvailable,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Map<String, Object> rawUsage,
        int callSequence,
        int toolLoopStep,
        Instant occurredAt
    ) {
        public LlmUsageRecord {
            rawUsage = immutableObjectMap(rawUsage);
        }
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
