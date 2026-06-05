package com.agentyard.channel.gateway.channel;

import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import com.agentyard.shared.redis.RedisKeyspace;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class ChannelOutboundExtensionStreamLeaseService {
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

    public ChannelOutboundExtensionStreamLeaseService(StringRedisTemplate redisTemplate, RedisKeyspace keyspace) {
        this.redisTemplate = redisTemplate;
        this.keyspace = keyspace;
    }

    public boolean acquire(ChannelOutboundProfileConsumer consumer, String leaseToken, Duration ttl) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(leaseKey(consumer), leaseToken, ttl);
        return Boolean.TRUE.equals(acquired);
    }

    public boolean renew(ChannelOutboundProfileConsumer consumer, String leaseToken, Duration ttl) {
        if (leaseToken == null) {
            return false;
        }
        Long renewed = redisTemplate.execute(
            COMPARE_AND_EXPIRE,
            List.of(leaseKey(consumer)),
            leaseToken,
            String.valueOf(ttl.toMillis())
        );
        return Long.valueOf(1L).equals(renewed);
    }

    public void release(ChannelOutboundProfileConsumer consumer, String leaseToken) {
        if (leaseToken != null) {
            redisTemplate.execute(COMPARE_AND_DELETE, List.of(leaseKey(consumer)), leaseToken);
        }
    }

    String leaseKey(ChannelOutboundProfileConsumer consumer) {
        return keyspace.channelOutboundExtensionStreamLease(ProfileConsumerKeys.key(consumer));
    }
}
