package com.agentyard.platform.session;

import com.agentyard.contracts.session.SessionContracts.SessionMessageBlockType;
import com.agentyard.contracts.session.SessionContracts.SessionReplyDraftOperation;
import com.agentyard.contracts.session.SessionContracts.StreamVisibility;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.agentyard.platform.session.SessionRuntimeDtos.SessionRuntimeDetailDto;

public final class SessionRuntimeStreamDtos {
    private SessionRuntimeStreamDtos() {
    }

    public record SessionRuntimeStreamEvent(
        String id,
        String type,
        Instant occurredAt,
        String sessionId,
        Object detail,
        String turnId,
        StreamVisibility visibility,
        String phase,
        String status,
        String title,
        String replyMessageId,
        SessionReplyDraftOperation operation,
        String blockId,
        SessionMessageBlockType blockType,
        String delta,
        String text,
        String code,
        String message,
        Boolean retryable
    ) {
        public static SessionRuntimeStreamEvent snapshot(
            String id,
            String type,
            Instant occurredAt,
            String sessionId,
            SessionRuntimeDetailDto detail
        ) {
            return new SessionRuntimeStreamEvent(
                id,
                type,
                occurredAt,
                sessionId,
                detail,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            );
        }

        public static SessionRuntimeStreamEvent progress(
            String id,
            Instant occurredAt,
            String sessionId,
            String turnId,
            StreamVisibility visibility,
            String phase,
            String status,
            String title,
            Map<String, Object> detail
        ) {
            return new SessionRuntimeStreamEvent(
                id,
                "SESSION_PROGRESS",
                occurredAt,
                sessionId,
                immutableDetail(detail),
                turnId,
                visibility,
                phase,
                status,
                title,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            );
        }

        public static SessionRuntimeStreamEvent draft(
            String id,
            Instant occurredAt,
            String sessionId,
            String turnId,
            String replyMessageId,
            SessionReplyDraftOperation operation,
            String blockId,
            SessionMessageBlockType blockType,
            String delta,
            String text
        ) {
            return new SessionRuntimeStreamEvent(
                id,
                "SESSION_REPLY_DRAFT",
                occurredAt,
                sessionId,
                null,
                turnId,
                StreamVisibility.CUSTOMER,
                null,
                null,
                null,
                replyMessageId,
                operation,
                blockId,
                blockType,
                delta,
                text,
                null,
                null,
                null
            );
        }

        public static SessionRuntimeStreamEvent streamError(
            String id,
            Instant occurredAt,
            String sessionId,
            String turnId,
            String code,
            String message,
            Boolean retryable,
            Map<String, Object> detail
        ) {
            return new SessionRuntimeStreamEvent(
                id,
                "SESSION_STREAM_ERROR",
                occurredAt,
                sessionId,
                immutableDetail(detail),
                turnId,
                StreamVisibility.OPERATOR,
                "ERROR",
                "FAILED",
                message,
                null,
                null,
                null,
                null,
                null,
                null,
                code,
                message,
                retryable
            );
        }

        private static Map<String, Object> immutableDetail(Map<String, Object> detail) {
            return detail == null || detail.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
        }
    }

    public record SessionRuntimeStreamReplayResult(
        boolean matchedLastEventId,
        java.util.List<SessionRuntimeStreamEvent> events
    ) {
    }
}
