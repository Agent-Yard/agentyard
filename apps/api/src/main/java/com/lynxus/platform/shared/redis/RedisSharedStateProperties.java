package com.lynxus.platform.shared.redis;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lynxus.shared-state")
public record RedisSharedStateProperties(
    String instanceId,
    Duration lockTtl,
    Duration lockAcquireTimeout,
    Duration idempotencyTtl,
    Duration sseReplayTtl,
    int sseReplayLimit,
    Duration streamPollInterval
) {
    public RedisSharedStateProperties {
        instanceId = normalizeInstanceId(instanceId);
        lockTtl = lockTtl == null || lockTtl.isZero() || lockTtl.isNegative() ? Duration.ofSeconds(10) : lockTtl;
        lockAcquireTimeout = lockAcquireTimeout == null || lockAcquireTimeout.isZero() || lockAcquireTimeout.isNegative()
            ? Duration.ofSeconds(3)
            : lockAcquireTimeout;
        idempotencyTtl = idempotencyTtl == null || idempotencyTtl.isZero() || idempotencyTtl.isNegative()
            ? Duration.ofHours(24)
            : idempotencyTtl;
        sseReplayTtl = sseReplayTtl == null || sseReplayTtl.isZero() || sseReplayTtl.isNegative()
            ? Duration.ofMinutes(15)
            : sseReplayTtl;
        sseReplayLimit = sseReplayLimit <= 0 ? 128 : sseReplayLimit;
        streamPollInterval = streamPollInterval == null || streamPollInterval.isZero() || streamPollInterval.isNegative()
            ? Duration.ofSeconds(1)
            : streamPollInterval;
    }

    private static String normalizeInstanceId(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        try {
            String host = InetAddress.getLocalHost().getHostName();
            String runtime = ManagementFactory.getRuntimeMXBean().getName().replace('@', '-');
            return host + "-" + runtime;
        } catch (Exception ignored) {
            return "lynxus-api-instance";
        }
    }
}
