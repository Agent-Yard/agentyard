package com.lynxus.channel.gateway.connector.feishu;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
final class FeishuIntegrationCredentialProvider implements FeishuCredentialProvider {
    private final FeishuIntegrationAccountRuntimeProvider runtimeProvider;
    private final FeishuGatewayProperties properties;
    private final Clock clock;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Autowired
    FeishuIntegrationCredentialProvider(
        FeishuIntegrationAccountRuntimeProvider runtimeProvider,
        FeishuGatewayProperties properties
    ) {
        this(runtimeProvider, properties, Clock.systemUTC());
    }

    FeishuIntegrationCredentialProvider(FeishuIntegrationAccountRuntimeProvider runtimeProvider) {
        this(runtimeProvider, new FeishuGatewayProperties(), Clock.systemUTC());
    }

    FeishuIntegrationCredentialProvider(
        FeishuIntegrationAccountRuntimeProvider runtimeProvider,
        FeishuGatewayProperties properties,
        Clock clock
    ) {
        this.runtimeProvider = runtimeProvider;
        this.properties = properties == null ? new FeishuGatewayProperties() : properties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public FeishuAppCredential resolve(String accountId) {
        String normalizedAccountId = requireText(accountId, "channel profile integration account");
        Duration ttl = properties.getCredentialCacheTtl();
        if (ttl.isZero()) {
            return loadCredential(normalizedAccountId);
        }
        Instant now = clock.instant();
        CacheEntry cached = cache.get(normalizedAccountId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.credential();
        }
        FeishuAppCredential credential = loadCredential(normalizedAccountId);
        cache.put(normalizedAccountId, new CacheEntry(credential, now.plus(ttl)));
        return credential;
    }

    void invalidate(String accountId) {
        String normalizedAccountId = normalizeText(accountId);
        if (normalizedAccountId != null) {
            cache.remove(normalizedAccountId);
        }
    }

    private FeishuAppCredential loadCredential(String normalizedAccountId) {
        FeishuIntegrationAccountRuntime runtime = runtimeProvider.load(normalizedAccountId);
        runtime.requireEnabledFeishuChannelProvider();
        return new FeishuAppCredential(normalizedAccountId, runtime.appId(), runtime.appSecret());
    }

    private record CacheEntry(FeishuAppCredential credential, Instant expiresAt) {
    }

    private static String requireText(String value, String field) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
