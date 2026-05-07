package com.lynxus.channel.gateway.connector.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lynxus.contracts.integration.IntegrationAccountInvalidationNotice;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FeishuIntegrationCredentialInvalidationSubscriberTest {
    @Test
    void evictsCachedCredentialForFeishuChannelProviderInvalidation() {
        AtomicInteger loads = new AtomicInteger();
        FeishuGatewayProperties properties = new FeishuGatewayProperties();
        properties.setCredentialCacheTtl(Duration.ofMinutes(10));
        FeishuIntegrationCredentialProvider provider = new FeishuIntegrationCredentialProvider(accountId -> {
            int load = loads.incrementAndGet();
            return new FeishuIntegrationAccountRuntime(
                accountId,
                "CHANNEL_PROVIDER",
                "feishu",
                "ENABLED",
                Map.of("appId", "cli_test_" + load),
                Map.of("appSecret", "secret_test_" + load)
            );
        }, properties, Clock.fixed(Instant.parse("2026-05-05T00:00:00Z"), ZoneId.of("UTC")));
        FeishuIntegrationCredentialInvalidationSubscriber subscriber =
            new FeishuIntegrationCredentialInvalidationSubscriber(provider, null, null, null);

        FeishuAppCredential first = provider.resolve("account-1");
        subscriber.handleNotice(new IntegrationAccountInvalidationNotice(
            "account-1",
            "CHANNEL_PROVIDER",
            "feishu",
            "CREDENTIAL_ROTATED",
            Instant.parse("2026-05-05T00:01:00Z"),
            "api-a"
        ));
        FeishuAppCredential second = provider.resolve("account-1");

        assertEquals(2, loads.get());
        assertEquals("cli_test_1", first.appId());
        assertEquals("cli_test_2", second.appId());
    }

    @Test
    void ignoresNonFeishuInvalidation() {
        AtomicInteger loads = new AtomicInteger();
        FeishuGatewayProperties properties = new FeishuGatewayProperties();
        properties.setCredentialCacheTtl(Duration.ofMinutes(10));
        FeishuIntegrationCredentialProvider provider = new FeishuIntegrationCredentialProvider(accountId -> {
            int load = loads.incrementAndGet();
            return new FeishuIntegrationAccountRuntime(
                accountId,
                "CHANNEL_PROVIDER",
                "feishu",
                "ENABLED",
                Map.of("appId", "cli_test_" + load),
                Map.of("appSecret", "secret_test_" + load)
            );
        }, properties, Clock.fixed(Instant.parse("2026-05-05T00:00:00Z"), ZoneId.of("UTC")));
        FeishuIntegrationCredentialInvalidationSubscriber subscriber =
            new FeishuIntegrationCredentialInvalidationSubscriber(provider, null, null, null);

        FeishuAppCredential first = provider.resolve("account-1");
        subscriber.handleNotice(new IntegrationAccountInvalidationNotice(
            "account-1",
            "CHANNEL_PROVIDER",
            "slack",
            "CREDENTIAL_ROTATED",
            Instant.parse("2026-05-05T00:01:00Z"),
            "api-a"
        ));
        FeishuAppCredential second = provider.resolve("account-1");

        assertEquals(1, loads.get());
        assertEquals("cli_test_1", first.appId());
        assertEquals("cli_test_1", second.appId());
    }
}
