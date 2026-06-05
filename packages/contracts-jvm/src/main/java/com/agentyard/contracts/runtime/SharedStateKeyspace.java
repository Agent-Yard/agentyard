package com.agentyard.contracts.runtime;

public final class SharedStateKeyspace {
    private SharedStateKeyspace() {
    }

    public static String lock(String domain, String key) {
        return "agentyard:lock:" + domain + ":" + key;
    }

    public static String idempotency(String domain, String key) {
        return "agentyard:idempotency:" + domain + ":" + key;
    }

    public static String sseEvents(String sessionId) {
        return "agentyard:sse:event:" + sessionId;
    }

    public static String sseEventDedup(String sessionId, String eventId) {
        return "agentyard:sse:event-dedup:" + sessionId + ":" + eventId;
    }

    public static String sseChannelSessionChanged() {
        return "agentyard:sse:channel:session-changed";
    }

    public static String sseChannelSessionUpdated() {
        return "agentyard:sse:channel:session-updated";
    }

    public static String privacySessionSummary(String sessionId) {
        return "agentyard:privacy:session:" + sessionId + ":summary";
    }

    public static String catalogInvalidationChannel() {
        return "agentyard:cache:invalidate:catalog";
    }

    public static String knowledgeInvalidationChannel() {
        return "agentyard:cache:invalidate:knowledge";
    }

    public static String httpSessionNamespace() {
        return "agentyard:session:http";
    }
}
