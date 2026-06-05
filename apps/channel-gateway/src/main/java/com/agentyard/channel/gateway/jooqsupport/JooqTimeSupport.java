package com.agentyard.channel.gateway.jooqsupport;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class JooqTimeSupport {
    private JooqTimeSupport() {
    }

    public static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public static OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
