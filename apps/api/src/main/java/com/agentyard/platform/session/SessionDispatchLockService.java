package com.agentyard.platform.session;

import com.agentyard.platform.shared.redis.RedisLockService;
import com.agentyard.shared.redis.RedisKeyspace;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SessionDispatchLockService {
    private final RedisLockService redisLockService;
    private final RedisKeyspace redisKeyspace;

    @Autowired
    public SessionDispatchLockService(RedisLockService redisLockService, RedisKeyspace redisKeyspace) {
        this.redisLockService = redisLockService;
        this.redisKeyspace = redisKeyspace;
    }

    SessionDispatchLockService() {
        this.redisLockService = null;
        this.redisKeyspace = null;
    }

    public <T> T withSessionLock(String sessionId, Supplier<T> action) {
        return withLock("session", sessionId, action);
    }

    public <T> T withConversationLock(String customerId, String assistantId, Supplier<T> action) {
        return withLock("conversation", customerId + ":" + assistantId, action);
    }

    private <T> T withLock(String domain, String key, Supplier<T> action) {
        if (redisLockService == null || redisKeyspace == null) {
            return action.get();
        }
        return redisLockService.withLock(redisKeyspace.lock(domain, key), action);
    }
}
