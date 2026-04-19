package com.lynxus.platform.session;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class SessionDispatchLockService {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T withSessionLock(String sessionId, Supplier<T> action) {
        return withLock("session:" + sessionId, action);
    }

    public <T> T withConversationLock(String customerId, String assistantId, Supplier<T> action) {
        return withLock("conversation:" + customerId + ":" + assistantId, action);
    }

    private <T> T withLock(String key, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
            if (!lock.isLocked() && !lock.hasQueuedThreads()) {
                locks.remove(key, lock);
            }
        }
    }
}
