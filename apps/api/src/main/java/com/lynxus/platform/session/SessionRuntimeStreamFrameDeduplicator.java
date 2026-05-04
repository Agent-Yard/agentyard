package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionContracts.AgentTurnTransientFrame;
import com.lynxus.platform.shared.redis.RedisSharedStateProperties;
import com.lynxus.shared.redis.RedisKeyspace;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

public interface SessionRuntimeStreamFrameDeduplicator {
    boolean claim(AgentTurnTransientFrame frame);

    static SessionRuntimeStreamFrameDeduplicator noop() {
        return ignored -> true;
    }
}

@Component
final class RedisSessionRuntimeStreamFrameDeduplicator implements SessionRuntimeStreamFrameDeduplicator {
    private static final String DEDUP_DOMAIN = "session-runtime-stream-frame";

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyspace keyspace;
    private final RedisSharedStateProperties properties;
    private final Counter acceptedCounter;
    private final Counter duplicateCounter;

    RedisSessionRuntimeStreamFrameDeduplicator(
        StringRedisTemplate redisTemplate,
        RedisKeyspace keyspace,
        RedisSharedStateProperties properties,
        MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.keyspace = keyspace;
        this.properties = properties;
        this.acceptedCounter = Counter.builder("lynxus.runtime_stream.frame_dedup.accepted")
            .description("Number of transient runtime stream frames accepted by early API frame dedup")
            .register(meterRegistry);
        this.duplicateCounter = Counter.builder("lynxus.runtime_stream.frame_dedup.duplicate")
            .description("Number of transient runtime stream frames skipped by early API frame dedup")
            .register(meterRegistry);
    }

    @Override
    public boolean claim(AgentTurnTransientFrame frame) {
        Duration ttl = properties.sseReplayTtl();
        String key = keyspace.idempotency(
            DEDUP_DOMAIN,
            frame.sessionId() + ":" + frame.turnExecutionId() + ":" + frame.frameId()
        );
        Boolean fresh = redisTemplate.opsForValue().setIfAbsent(key, frame.frameId(), ttl);
        if (Boolean.TRUE.equals(fresh)) {
            acceptedCounter.increment();
            return true;
        }
        duplicateCounter.increment();
        return false;
    }
}
