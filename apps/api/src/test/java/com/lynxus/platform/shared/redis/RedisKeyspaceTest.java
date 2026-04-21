package com.lynxus.platform.shared.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lynxus.shared.redis.RedisKeyspace;
import org.junit.jupiter.api.Test;

class RedisKeyspaceTest {
    private final RedisKeyspace keyspace = new RedisKeyspace();

    @Test
    void shouldUseSharedPrefixesForMultiInstanceState() {
        assertEquals("lynxus:lock:session:session-1", keyspace.lock("session", "session-1"));
        assertEquals("lynxus:idempotency:webhook:event-1", keyspace.idempotency("webhook", "event-1"));
        assertEquals("lynxus:sse:event:session-1", keyspace.sseEvents("session-1"));
        assertEquals("lynxus:sse:channel:session-changed", keyspace.sseChannelSessionChanged());
        assertEquals("lynxus:sse:channel:session-updated", keyspace.sseChannelSessionUpdated());
        assertEquals("lynxus:privacy:session:session-1:summary", keyspace.privacySessionSummary("session-1"));
        assertEquals("lynxus:session:http", keyspace.httpSessionNamespace());
    }
}
