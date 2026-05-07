package com.lynxus.channel.gateway.connector.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FeishuIntegrationCredentialProviderTest {
    @Test
    void loadsAppCredentialFromIntegrationAccountConfigAndCredential() {
        FeishuIntegrationCredentialProvider provider = new FeishuIntegrationCredentialProvider(accountId ->
            new FeishuIntegrationAccountRuntime(
                accountId,
                "CHANNEL_PROVIDER",
                "feishu",
                "ENABLED",
                Map.of("appId", "cli_test"),
                Map.of("appSecret", "secret_test")
            )
        );

        FeishuAppCredential credential = provider.resolve("account-1");

        assertEquals("account-1", credential.accountId());
        assertEquals("cli_test", credential.appId());
        assertEquals("secret_test", credential.appSecret());
    }

    @Test
    void cachesEnabledCredentialUntilTtlExpires() {
        AtomicInteger loads = new AtomicInteger();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-05T00:00:00Z"));
        FeishuGatewayProperties properties = new FeishuGatewayProperties();
        properties.setCredentialCacheTtl(Duration.ofMinutes(2));
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
        }, properties, clock);

        FeishuAppCredential first = provider.resolve("account-1");
        FeishuAppCredential second = provider.resolve("account-1");
        clock.advance(Duration.ofMinutes(3));
        FeishuAppCredential third = provider.resolve("account-1");

        assertEquals(2, loads.get());
        assertEquals("cli_test_1", first.appId());
        assertEquals("cli_test_1", second.appId());
        assertEquals("cli_test_2", third.appId());
    }

    @Test
    void invalidatesCachedCredentialBeforeTtlExpires() {
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

        FeishuAppCredential first = provider.resolve(" account-1 ");
        provider.invalidate("account-1");
        FeishuAppCredential second = provider.resolve("account-1");

        assertEquals(2, loads.get());
        assertEquals("cli_test_1", first.appId());
        assertEquals("cli_test_2", second.appId());
    }

    @Test
    void doesNotCacheDisabledCredentialRuntime() {
        AtomicInteger loads = new AtomicInteger();
        FeishuGatewayProperties properties = new FeishuGatewayProperties();
        properties.setCredentialCacheTtl(Duration.ofMinutes(2));
        FeishuIntegrationCredentialProvider provider = new FeishuIntegrationCredentialProvider(accountId -> {
            loads.incrementAndGet();
            return new FeishuIntegrationAccountRuntime(
                accountId,
                "CHANNEL_PROVIDER",
                "feishu",
                "DISABLED",
                Map.of("appId", "cli_test"),
                Map.of("appSecret", "secret_test")
            );
        }, properties, Clock.fixed(Instant.parse("2026-05-05T00:00:00Z"), ZoneId.of("UTC")));

        assertThrows(IllegalStateException.class, () -> provider.resolve("account-1"));
        assertThrows(IllegalStateException.class, () -> provider.resolve("account-1"));

        assertEquals(2, loads.get());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
