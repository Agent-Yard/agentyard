package com.lynxus.platform.shared.redis;

import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import com.lynxus.shared.redis.RedisSharedStateMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class RedisInvalidationBus {
    private final RedisPubSubBus pubSubBus;
    private final RedisKeyspace keyspace;
    private final RedisJsonCodec codec;
    private final RedisSharedStateProperties properties;
    private final RedisSharedStateMetrics metrics;

    public RedisInvalidationBus(
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec,
        RedisSharedStateProperties properties,
        RedisSharedStateMetrics metrics
    ) {
        this.pubSubBus = pubSubBus;
        this.keyspace = keyspace;
        this.codec = codec;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void publishCatalogInvalidated(long revision) {
        publish(keyspace.catalogInvalidationChannel(), "catalog", revision);
    }

    public void publishKnowledgeInvalidated(long revision) {
        publish(keyspace.knowledgeInvalidationChannel(), "knowledge", revision);
    }

    public AutoCloseable subscribeCatalogInvalidated(Consumer<InvalidationNotice> consumer) {
        return subscribe(keyspace.catalogInvalidationChannel(), "catalog", consumer);
    }

    public AutoCloseable subscribeKnowledgeInvalidated(Consumer<InvalidationNotice> consumer) {
        return subscribe(keyspace.knowledgeInvalidationChannel(), "knowledge", consumer);
    }

    private void publish(String channel, String domain, long revision) {
        pubSubBus.publish(channel, codec.write(new InvalidationNotice(domain, revision, Instant.now(), properties.instanceId())));
        metrics.incrementInvalidationPublished(domain);
    }

    private AutoCloseable subscribe(String channel, String domain, Consumer<InvalidationNotice> consumer) {
        return pubSubBus.subscribe(channel, payload -> {
            InvalidationNotice notice = codec.read(payload, InvalidationNotice.class);
            metrics.incrementInvalidationReceived(domain);
            if (notice != null && notice.occurredAt() != null) {
                metrics.recordInvalidationSubscriberLag(domain, Duration.between(notice.occurredAt(), Instant.now()));
            }
            consumer.accept(notice);
        });
    }

    public record InvalidationNotice(
        String domain,
        long revision,
        Instant occurredAt,
        String sourceInstanceId
    ) {
    }
}
