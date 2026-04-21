package com.lynxus.platform.shared.redis;

import com.lynxus.platform.shared.ConflictException;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisSharedStateMetrics;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisIdempotencyService {
    private static final String STARTED_PREFIX = "STARTED:";
    private static final String COMPLETED_PREFIX = "COMPLETED:";
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>(
        """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """,
        Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final RedisJsonCodec codec;
    private final RedisSharedStateProperties properties;
    private final RedisSharedStateMetrics metrics;

    public RedisIdempotencyService(
        StringRedisTemplate redisTemplate,
        RedisJsonCodec codec,
        RedisSharedStateProperties properties,
        RedisSharedStateMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.codec = codec;
        this.properties = properties;
        this.metrics = metrics;
    }

    public <T> T execute(String key, Class<T> type, Supplier<T> action) {
        return execute(key, properties.idempotencyTtl(), type, action);
    }

    public <T> T execute(String key, Duration ttl, Class<T> type, Supplier<T> action) {
        String existing = redisTemplate.opsForValue().get(key);
        if (isCompleted(existing)) {
            metrics.incrementIdempotencyCompletedHit();
            return codec.read(existing.substring(COMPLETED_PREFIX.length()), type);
        }

        String token = STARTED_PREFIX + properties.instanceId() + ":" + UUID.randomUUID();
        Boolean reserved = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        if (Boolean.TRUE.equals(reserved)) {
            metrics.incrementIdempotencyReserved();
            try {
                T value = action.get();
                redisTemplate.opsForValue().set(key, COMPLETED_PREFIX + codec.write(value), ttl);
                metrics.incrementIdempotencyCompletedWrite();
                return value;
            } catch (RuntimeException | Error error) {
                redisTemplate.execute(COMPARE_AND_DELETE, List.of(key), token);
                throw error;
            }
        }

        existing = redisTemplate.opsForValue().get(key);
        if (isCompleted(existing)) {
            metrics.incrementIdempotencyCompletedHit();
            return codec.read(existing.substring(COMPLETED_PREFIX.length()), type);
        }
        metrics.incrementIdempotencyInProgressConflict();
        throw new ConflictException("duplicate request is already in progress");
    }

    private static boolean isCompleted(String payload) {
        return payload != null && payload.startsWith(COMPLETED_PREFIX);
    }
}
