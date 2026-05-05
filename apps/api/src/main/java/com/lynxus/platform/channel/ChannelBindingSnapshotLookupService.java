package com.lynxus.platform.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshot;
import com.lynxus.platform.channel.ChannelBindingSnapshotRefreshCoordinator.SnapshotInvalidationNotice;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ChannelBindingSnapshotLookupService {
    private static final Logger log = LoggerFactory.getLogger(ChannelBindingSnapshotLookupService.class);

    private final JooqChannelBindingSnapshotRepository repository;
    private final ChannelBindingSnapshotRefreshCoordinator refreshCoordinator;
    private final RedisPubSubBus pubSubBus;
    private final RedisKeyspace keyspace;
    private final RedisJsonCodec codec;
    private final ConcurrentMap<String, Optional<ChannelOutboundBindingSnapshot>> sessionRouteCache = new ConcurrentHashMap<>();
    private AutoCloseable invalidationSubscription;

    public ChannelBindingSnapshotLookupService(
        JooqChannelBindingSnapshotRepository repository,
        ChannelBindingSnapshotRefreshCoordinator refreshCoordinator,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec
    ) {
        this.repository = repository;
        this.refreshCoordinator = refreshCoordinator;
        this.pubSubBus = pubSubBus;
        this.keyspace = keyspace;
        this.codec = codec;
    }

    @PostConstruct
    void subscribeInvalidations() {
        invalidationSubscription = pubSubBus.subscribe(
            keyspace.channelBindingSnapshotInvalidationChannel(),
            payload -> invalidate(codec.read(payload, SnapshotInvalidationNotice.class))
        );
    }

    @PreDestroy
    void close() throws Exception {
        if (invalidationSubscription != null) {
            invalidationSubscription.close();
        }
    }

    public Optional<ChannelOutboundBindingSnapshot> findActiveBySession(String sessionId) {
        if (!hasText(sessionId)) {
            return Optional.empty();
        }
        return sessionRouteCache.computeIfAbsent(sessionId.trim(), repository::findActiveBySessionId);
    }

    public Optional<ChannelOutboundBindingSnapshot> findActiveBySessionFailClosed(String sessionId) {
        Optional<ChannelOutboundBindingSnapshot> snapshot = repository.findActiveBySessionId(sessionId);
        if (snapshot.isPresent()) {
            return snapshot;
        }
        try {
            refreshCoordinator.refreshBySessionNow(sessionId, "OUTBOUND_LOOKUP_MISS");
        } catch (RuntimeException error) {
            log.warn("channel binding snapshot by-session refresh failed during outbound lookup: sessionId={}", sessionId, error);
            return Optional.empty();
        }
        snapshot = repository.findActiveBySessionId(sessionId);
        if (snapshot.isEmpty()) {
            log.warn("channel outbound lookup failed closed because no ACTIVE binding snapshot exists: sessionId={}", sessionId);
        }
        return snapshot;
    }

    void invalidate(SnapshotInvalidationNotice notice) {
        if (notice == null || !hasText(notice.key())) {
            return;
        }
        String key = notice.key().trim();
        if (key.startsWith("session:")) {
            String sessionId = key.substring("session:".length()).trim();
            if (hasText(sessionId)) {
                sessionRouteCache.remove(sessionId);
            }
            return;
        }
        sessionRouteCache.clear();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
