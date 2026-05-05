package com.lynxus.shared.redis;

import com.lynxus.contracts.runtime.SharedStateKeyspace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RedisKeyspace {
    private static final String DEFAULT_ROOT_PREFIX = "lynxus";

    private final String rootPrefix;

    public RedisKeyspace() {
        this(DEFAULT_ROOT_PREFIX);
    }

    @Autowired
    public RedisKeyspace(@Value("${lynxus.redis.key-prefix:lynxus}") String rootPrefix) {
        this.rootPrefix = normalizeRootPrefix(rootPrefix);
    }

    public String lock(String domain, String key) {
        return qualify(SharedStateKeyspace.lock(domain, key));
    }

    public String idempotency(String domain, String key) {
        return qualify(SharedStateKeyspace.idempotency(domain, key));
    }

    public String sseEvents(String sessionId) {
        return qualify(SharedStateKeyspace.sseEvents(sessionId));
    }

    public String sseEventDedup(String sessionId, String eventId) {
        return qualify(SharedStateKeyspace.sseEventDedup(sessionId, eventId));
    }

    public String sseChannelSessionChanged() {
        return qualify(SharedStateKeyspace.sseChannelSessionChanged());
    }

    public String sseChannelSessionUpdated() {
        return qualify(SharedStateKeyspace.sseChannelSessionUpdated());
    }

    public String privacySessionSummary(String sessionId) {
        return qualify(SharedStateKeyspace.privacySessionSummary(sessionId));
    }

    public String catalogInvalidationChannel() {
        return qualify(SharedStateKeyspace.catalogInvalidationChannel());
    }

    public String knowledgeInvalidationChannel() {
        return qualify(SharedStateKeyspace.knowledgeInvalidationChannel());
    }

    public String channelBindingSnapshotRefreshChannel() {
        return qualify("lynxus:channel-binding-snapshot:refresh");
    }

    public String channelBindingSnapshotInvalidationChannel() {
        return qualify("lynxus:channel-binding-snapshot:invalidation");
    }

    public String channelOutboundFrameChannel() {
        return qualify("lynxus:channel-outbound:frames");
    }

    public String channelOutboundApiStreamOwnerLock(String profileConsumerKey) {
        return qualify("lynxus:lock:channel-outbound-api-stream-owner:" + profileConsumerKey);
    }

    public String channelOutboundExtensionStreamLease(String profileConsumerKey) {
        return qualify("lynxus:lock:channel-outbound-extension-stream:" + profileConsumerKey);
    }

    public String channelOutboundExtensionPendingFinals(String profileConsumerKey) {
        return qualify("lynxus:channel-outbound:extension-pending-finals:" + profileConsumerKey);
    }

    public String channelOutboundExtensionForwardedFinal(String profileConsumerKey, long finalSequence) {
        return qualify("lynxus:channel-outbound:extension-forwarded-final:" + profileConsumerKey + ":" + finalSequence);
    }

    public String channelBindingSnapshotRefreshLock(String key) {
        return qualify("lynxus:lock:channel-binding-snapshot-refresh:" + key);
    }

    public String httpSessionNamespace() {
        return qualify(SharedStateKeyspace.httpSessionNamespace());
    }

    private String qualify(String key) {
        if (DEFAULT_ROOT_PREFIX.equals(rootPrefix)) {
            return key;
        }
        if (key.startsWith(DEFAULT_ROOT_PREFIX + ":")) {
            return rootPrefix + key.substring(DEFAULT_ROOT_PREFIX.length());
        }
        return rootPrefix + ":" + key;
    }

    private static String normalizeRootPrefix(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith(":")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? DEFAULT_ROOT_PREFIX : normalized;
    }
}
