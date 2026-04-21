package com.lynxus.shared.redis;

import com.lynxus.contracts.runtime.SharedStateKeyspace;
import org.springframework.stereotype.Component;

@Component
public class RedisKeyspace {
    public String lock(String domain, String key) {
        return SharedStateKeyspace.lock(domain, key);
    }

    public String idempotency(String domain, String key) {
        return SharedStateKeyspace.idempotency(domain, key);
    }

    public String sseEvents(String sessionId) {
        return SharedStateKeyspace.sseEvents(sessionId);
    }

    public String sseEventDedup(String sessionId, String eventId) {
        return SharedStateKeyspace.sseEventDedup(sessionId, eventId);
    }

    public String sseChannelSessionChanged() {
        return SharedStateKeyspace.sseChannelSessionChanged();
    }

    public String sseChannelSessionUpdated() {
        return SharedStateKeyspace.sseChannelSessionUpdated();
    }

    public String privacySessionSummary(String sessionId) {
        return SharedStateKeyspace.privacySessionSummary(sessionId);
    }

    public String catalogInvalidationChannel() {
        return SharedStateKeyspace.catalogInvalidationChannel();
    }

    public String knowledgeInvalidationChannel() {
        return SharedStateKeyspace.knowledgeInvalidationChannel();
    }

    public String httpSessionNamespace() {
        return SharedStateKeyspace.httpSessionNamespace();
    }
}
