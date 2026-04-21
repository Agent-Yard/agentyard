package com.lynxus.shared.redis;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class RedisSharedStateMetrics {
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();

    public RedisSharedStateMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void incrementLockAcquireSuccess() {
        counter("lynxus.shared_state.lock.acquire.success").increment();
    }

    public void incrementLockAcquireFailure() {
        counter("lynxus.shared_state.lock.acquire.failure").increment();
    }

    public void incrementIdempotencyCompletedHit() {
        counter("lynxus.shared_state.idempotency.completed.hit").increment();
    }

    public void incrementIdempotencyReserved() {
        counter("lynxus.shared_state.idempotency.reserved").increment();
    }

    public void incrementIdempotencyCompletedWrite() {
        counter("lynxus.shared_state.idempotency.completed.write").increment();
    }

    public void incrementIdempotencyInProgressConflict() {
        counter("lynxus.shared_state.idempotency.in_progress.conflict").increment();
    }

    public void incrementPubSubPublished(String channel) {
        taggedCounter("lynxus.shared_state.redis.pubsub.published", channel).increment();
    }

    public void incrementPubSubReceived(String channel) {
        taggedCounter("lynxus.shared_state.redis.pubsub.received", channel).increment();
    }

    public void incrementInvalidationPublished(String domain) {
        taggedCounter("lynxus.shared_state.cache.invalidation.published", domain).increment();
    }

    public void incrementInvalidationReceived(String domain) {
        taggedCounter("lynxus.shared_state.cache.invalidation.received", domain).increment();
    }

    public void recordInvalidationSubscriberLag(String domain, Duration lag) {
        taggedTimer("lynxus.shared_state.cache.invalidation.subscriber.lag", domain).record(
            lag == null || lag.isNegative() ? Duration.ZERO : lag
        );
    }

    private Counter counter(String name) {
        return counters.computeIfAbsent(name, meterRegistry::counter);
    }

    private Counter taggedCounter(String name, String tagValue) {
        String cacheKey = name + ":" + tagValue;
        return counters.computeIfAbsent(cacheKey, key -> meterRegistry.counter(name, "target", tagValue));
    }

    private Timer taggedTimer(String name, String tagValue) {
        String cacheKey = name + ":" + tagValue;
        return timers.computeIfAbsent(cacheKey, key -> meterRegistry.timer(name, "target", tagValue));
    }
}
