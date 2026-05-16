package com.lynxus.worker.session;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import com.lynxus.contracts.session.SessionContracts.SessionMessage;
import com.lynxus.contracts.session.SessionContracts.SessionMessageProducerType;
import com.lynxus.contracts.session.SessionContracts.SessionMessageRole;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSender;
import com.lynxus.contracts.session.SessionContracts.SessionMessageStatus;
import io.temporal.activity.ActivityInterface;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ActivityInterface
public interface SessionPersistenceActivities {
    void saveSession(SessionRecord session);

    PlatformTurnAllocation allocatePlatformTurn(
        String sessionId,
        String triggerType,
        String dedupKey,
        String sourceEventId,
        Map<String, Object> metadata
    );

    List<SessionMessage> appendSessionMessages(String sessionId, String turnId, List<SessionMessageAppendRecord> messages);

    void appendEvent(SessionEvent event);

    void appendPlatformEvent(PlatformEventRecord event);

    void appendLlmUsage(List<LlmUsageRecord> records);

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
        long sharedStateRevision,
        Instant idleDeadline,
        Instant createdAt,
        Instant updatedAt,
        Instant endedAt
    ) {
        public SessionRecord {
            sharedState = immutableObjectMap(sharedState);
        }
    }

    record PlatformTurnAllocation(
        String turnId,
        String sessionId,
        String triggerType,
        String dedupKey,
        String sourceEventId,
        Map<String, Object> metadata
    ) {
        public PlatformTurnAllocation {
            metadata = immutableObjectMap(metadata);
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

    record SessionMessageAppendRecord(
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
        public SessionMessageAppendRecord {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            metadata = immutableObjectMap(metadata);
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
