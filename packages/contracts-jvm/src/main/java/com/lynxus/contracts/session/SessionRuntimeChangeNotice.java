package com.lynxus.contracts.session;

import java.time.Instant;

public record SessionRuntimeChangeNotice(
    String sessionId,
    String fingerprint,
    Instant occurredAt,
    String sourceInstanceId
) {
}
