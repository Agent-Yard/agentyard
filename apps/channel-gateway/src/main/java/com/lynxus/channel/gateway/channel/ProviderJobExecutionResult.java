package com.lynxus.channel.gateway.channel;

import java.util.Map;

record ProviderJobExecutionResult(
    int eventsIngested,
    String nextCursor,
    Map<String, Object> metadata
) {
    static ProviderJobExecutionResult empty() {
        return new ProviderJobExecutionResult(0, null, Map.of());
    }
}
