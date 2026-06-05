package com.agentyard.channel.gateway.channel;

import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import com.agentyard.shared.redis.RedisKeyspace;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class ChannelOutboundStreamOwnerLockService {
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>(
        """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """,
        Long.class
    );
    private static final DefaultRedisScript<Long> COMPARE_AND_EXPIRE = new DefaultRedisScript<>(
        """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('pexpire', KEYS[1], ARGV[2])
            end
            return 0
            """,
        Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyspace keyspace;

    public ChannelOutboundStreamOwnerLockService(StringRedisTemplate redisTemplate, RedisKeyspace keyspace) {
        this.redisTemplate = redisTemplate;
        this.keyspace = keyspace;
    }

    public boolean acquire(ChannelOutboundProfileConsumer consumer, String ownerToken, Duration ttl) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey(consumer), ownerToken, ttl);
        return Boolean.TRUE.equals(acquired);
    }

    public boolean owns(ChannelOutboundProfileConsumer consumer, String ownerToken) {
        return ownerToken != null && ownerToken.equals(redisTemplate.opsForValue().get(lockKey(consumer)));
    }

    public boolean renew(ChannelOutboundProfileConsumer consumer, String ownerToken, Duration ttl) {
        if (ownerToken == null) {
            return false;
        }
        Long renewed = redisTemplate.execute(
            COMPARE_AND_EXPIRE,
            List.of(lockKey(consumer)),
            ownerToken,
            String.valueOf(ttl.toMillis())
        );
        return Long.valueOf(1L).equals(renewed);
    }

    public void release(ChannelOutboundProfileConsumer consumer, String ownerToken) {
        if (ownerToken != null) {
            redisTemplate.execute(COMPARE_AND_DELETE, List.of(lockKey(consumer)), ownerToken);
        }
    }

    String lockKey(ChannelOutboundProfileConsumer consumer) {
        return keyspace.channelOutboundApiStreamOwnerLock(ProfileConsumerKeys.key(consumer));
    }
}
