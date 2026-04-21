package com.lynxus.platform.session;

import java.time.Instant;

import static com.lynxus.platform.session.SessionRuntimeDtos.SessionRuntimeDetailDto;

public final class SessionRuntimeStreamDtos {
    private SessionRuntimeStreamDtos() {
    }

    public record SessionRuntimeStreamEvent(
        String id,
        String type,
        Instant occurredAt,
        String sessionId,
        SessionRuntimeDetailDto detail
    ) {
    }

    public record SessionRuntimeStreamReplayResult(
        boolean matchedLastEventId,
        java.util.List<SessionRuntimeStreamEvent> events
    ) {
    }
}
