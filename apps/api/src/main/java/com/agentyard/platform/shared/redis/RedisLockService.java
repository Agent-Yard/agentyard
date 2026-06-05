package com.agentyard.platform.shared.redis;

import com.agentyard.platform.shared.ConflictException;
import com.agentyard.shared.redis.RedisSharedStateMetrics;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisLockService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisLockService.class);
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
    private final RedisSharedStateProperties properties;
    private final RedisSharedStateMetrics metrics;

    public RedisLockService(
        StringRedisTemplate redisTemplate,
        RedisSharedStateProperties properties,
        RedisSharedStateMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    public <T> T withLock(String key, Supplier<T> action) {
        return withLock(key, properties.lockTtl(), properties.lockAcquireTimeout(), action);
    }

    public <T> T withLock(String key, Duration ttl, Duration acquireTimeout, Supplier<T> action) {
        String ownerToken = properties.instanceId() + ":" + UUID.randomUUID();
        long deadline = System.nanoTime() + acquireTimeout.toNanos();
        boolean acquired = false;
        while (System.nanoTime() < deadline) {
            Boolean success = redisTemplate.opsForValue().setIfAbsent(key, ownerToken, ttl);
            if (Boolean.TRUE.equals(success)) {
                acquired = true;
                break;
            }
            sleepBriefly();
        }
        if (!acquired) {
            LOGGER.warn("distributed lock acquire timed out key={}", key);
            metrics.incrementLockAcquireFailure();
            throw new ConflictException("resource is busy");
        }
        try {
            metrics.incrementLockAcquireSuccess();
            return action.get();
        } finally {
            redisTemplate.execute(COMPARE_AND_DELETE, List.of(key), ownerToken);
        }
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for distributed lock", error);
        }
    }
}
