package com.lynxus.contracts.runtime;

public final class SharedStateKeyspace {
    private SharedStateKeyspace() {
    }

    public static String lock(String domain, String key) {
        return "lynxus:lock:" + domain + ":" + key;
    }

    public static String idempotency(String domain, String key) {
        return "lynxus:idempotency:" + domain + ":" + key;
    }

    public static String sseEvents(String sessionId) {
        return "lynxus:sse:event:" + sessionId;
    }

    public static String sseEventDedup(String sessionId, String eventId) {
        return "lynxus:sse:event-dedup:" + sessionId + ":" + eventId;
    }

    public static String sseChannelSessionChanged() {
        return "lynxus:sse:channel:session-changed";
    }

    public static String sseChannelSessionUpdated() {
        return "lynxus:sse:channel:session-updated";
    }

    public static String privacySessionSummary(String sessionId) {
        return "lynxus:privacy:session:" + sessionId + ":summary";
    }

    public static String catalogInvalidationChannel() {
        return "lynxus:cache:invalidate:catalog";
    }

    public static String knowledgeInvalidationChannel() {
        return "lynxus:cache:invalidate:knowledge";
    }

    public static String httpSessionNamespace() {
        return "lynxus:session:http";
    }
}
