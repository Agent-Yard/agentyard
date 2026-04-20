package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SessionRuntimeDtos {
    private SessionRuntimeDtos() {
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public record CreateSessionRequest(
        String assistantId,
        String customerId,
        String openingMessage
    ) {
    }

    public record SendSessionMessageRequest(
        String customerId,
        String message
    ) {
    }

    public record HumanResumeRequest(
        String playbookRunId,
        Map<String, Object> payload
    ) {
        public HumanResumeRequest {
            payload = immutableObjectMap(payload);
        }
    }

    public record ExternalCallbackRequest(
        String playbookRunId,
        Map<String, Object> payload
    ) {
        public ExternalCallbackRequest {
            payload = immutableObjectMap(payload);
        }
    }

    public record HumanOperatorReplyRequest(
        String operatorId,
        String message,
        Map<String, Object> payload
    ) {
        public HumanOperatorReplyRequest {
            payload = immutableObjectMap(payload);
        }
    }

    public record SessionRuntimeSessionDto(
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
        long latestEventSequence
    ) {
        public SessionRuntimeSessionDto {
            sharedState = immutableObjectMap(sharedState);
        }
    }

    public record SessionRuntimeDetailDto(
        SessionRuntimeSessionDto session,
        List<SessionEvent> events,
        List<PlaybookRun> playbookRuns
    ) {
        public SessionRuntimeDetailDto {
            events = events == null ? List.of() : List.copyOf(events);
            playbookRuns = playbookRuns == null ? List.of() : List.copyOf(playbookRuns);
        }
    }

    public record PrivacyMappingSummaryDto(
        boolean enabled,
        String privacyModelResourceId,
        String privacyModelName,
        Map<String, Integer> sanitizeCountByChannel,
        Map<String, Integer> restoreCountByChannel,
        Map<String, Integer> entityTypeBreakdown,
        int placeholderCount,
        int unresolvedPlaceholderCount,
        int blockedEventCount,
        Instant lastProcessedAt
    ) {
        public PrivacyMappingSummaryDto {
            sanitizeCountByChannel = sanitizeCountByChannel == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(sanitizeCountByChannel));
            restoreCountByChannel = restoreCountByChannel == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(restoreCountByChannel));
            entityTypeBreakdown = entityTypeBreakdown == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(entityTypeBreakdown));
        }
    }
}
