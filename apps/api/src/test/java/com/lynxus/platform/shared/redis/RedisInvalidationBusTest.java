package com.lynxus.platform.shared.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import com.lynxus.shared.redis.RedisSharedStateMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RedisInvalidationBusTest {
    @Test
    void shouldPublishCatalogInvalidationNotice() {
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        RedisJsonCodec codec = new RedisJsonCodec(new ObjectMapper());
        RedisInvalidationBus bus = new RedisInvalidationBus(
            pubSubBus,
            new RedisKeyspace(),
            codec,
            properties(),
            new RedisSharedStateMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
        );

        bus.publishCatalogInvalidated(7L);

        verify(pubSubBus).publish(eq("lynxus:cache:invalidate:catalog"), org.mockito.ArgumentMatchers.argThat(payload -> {
            RedisInvalidationBus.InvalidationNotice notice = codec.read(payload, RedisInvalidationBus.InvalidationNotice.class);
            assertEquals("catalog", notice.domain());
            assertEquals(7L, notice.revision());
            assertEquals("api-a", notice.sourceInstanceId());
            return true;
        }));
    }

    @Test
    void shouldDecodeKnowledgeInvalidationNoticeForSubscribers() {
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        AtomicReference<Consumer<String>> subscribedConsumer = new AtomicReference<>();
        doAnswer(invocation -> {
            subscribedConsumer.set(invocation.getArgument(1));
            return (AutoCloseable) () -> {
            };
        }).when(pubSubBus).subscribe(eq("lynxus:cache:invalidate:knowledge"), org.mockito.ArgumentMatchers.any());
        RedisJsonCodec codec = new RedisJsonCodec(new ObjectMapper());
        RedisInvalidationBus bus = new RedisInvalidationBus(
            pubSubBus,
            new RedisKeyspace(),
            codec,
            properties(),
            new RedisSharedStateMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
        );
        AtomicReference<RedisInvalidationBus.InvalidationNotice> received = new AtomicReference<>();

        bus.subscribeKnowledgeInvalidated(received::set);
        subscribedConsumer.get().accept(codec.write(
            new RedisInvalidationBus.InvalidationNotice("knowledge", 11L, Instant.parse("2026-04-21T00:00:00Z"), "api-b")
        ));

        assertEquals("knowledge", received.get().domain());
        assertEquals(11L, received.get().revision());
        assertEquals("api-b", received.get().sourceInstanceId());
    }

    private static RedisSharedStateProperties properties() {
        return new RedisSharedStateProperties(
            "api-a",
            Duration.ofSeconds(10),
            Duration.ofSeconds(3),
            Duration.ofHours(24),
            Duration.ofMinutes(15),
            128,
            Duration.ofSeconds(1)
        );
    }
}
