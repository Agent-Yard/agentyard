package com.agentyard.channel.gateway.channel;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class ProviderJobLockService {
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

    public ProviderJobLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean acquire(String jobId, String runId, Duration ttl) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key(jobId), runId, ttl);
        return Boolean.TRUE.equals(acquired);
    }

    public boolean owns(String jobId, String runId) {
        return runId != null && runId.equals(redisTemplate.opsForValue().get(key(jobId)));
    }

    public boolean exists(String jobId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(jobId)));
    }

    public void release(String jobId, String runId) {
        if (runId != null) {
            redisTemplate.execute(COMPARE_AND_DELETE, List.of(key(jobId)), runId);
        }
    }

    public String key(String jobId) {
        return "channel-provider-job:" + jobId;
    }
}
