package com.agentyard.platform.shared.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agentyard.shared.redis.RedisKeyspace;
import org.junit.jupiter.api.Test;

class RedisKeyspaceTest {
    private final RedisKeyspace keyspace = new RedisKeyspace();

    @Test
    void shouldUseSharedPrefixesForMultiInstanceState() {
        assertEquals("agentyard:lock:session:session-1", keyspace.lock("session", "session-1"));
        assertEquals("agentyard:idempotency:webhook:event-1", keyspace.idempotency("webhook", "event-1"));
        assertEquals("agentyard:sse:event:session-1", keyspace.sseEvents("session-1"));
        assertEquals("agentyard:sse:channel:session-changed", keyspace.sseChannelSessionChanged());
        assertEquals("agentyard:sse:channel:session-updated", keyspace.sseChannelSessionUpdated());
        assertEquals("agentyard:privacy:session:session-1:summary", keyspace.privacySessionSummary("session-1"));
        assertEquals("agentyard:integration-account:invalidation", keyspace.integrationAccountInvalidationChannel());
        assertEquals("agentyard:session:http", keyspace.httpSessionNamespace());
    }

    @Test
    void shouldApplyEnvironmentRootPrefix() {
        RedisKeyspace testKeyspace = new RedisKeyspace("agentyard:test");

        assertEquals("agentyard:test:lock:session:session-1", testKeyspace.lock("session", "session-1"));
        assertEquals("agentyard:test:sse:channel:session-changed", testKeyspace.sseChannelSessionChanged());
        assertEquals("agentyard:test:cache:invalidate:knowledge", testKeyspace.knowledgeInvalidationChannel());
        assertEquals("agentyard:test:integration-account:invalidation", testKeyspace.integrationAccountInvalidationChannel());
    }
}
