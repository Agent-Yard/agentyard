package com.lynxus.channel.gateway.connector.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.channel.gateway.channel.ChannelAdminRepository;
import com.lynxus.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FeishuLongConnectionManagerTest {
    private static EmbeddedPostgresTestDatabase database;

    private ChannelAdminRepository repository;
    private FeishuLongConnectionManager manager;
    private CapturingCredentialProvider credentialProvider;
    private CapturingClientFactory clientFactory;

    @BeforeAll
    static void startDatabase() throws Exception {
        database = new EmbeddedPostgresTestDatabase();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        database.close();
    }

    @BeforeEach
    void setUp() {
        database.reset();
        repository = new ChannelAdminRepository(database.dsl(), new ObjectMapper());
        credentialProvider = new CapturingCredentialProvider();
        clientFactory = new CapturingClientFactory();
        manager = manager(clientFactory);
    }

    private FeishuLongConnectionManager manager(CapturingClientFactory clientFactory) {
        return new FeishuLongConnectionManager(
            repository,
            credentialProvider,
            clientFactory
        );
    }

    @Test
    void startsOnlyActiveInboundFeishuProfilesWithAssistantBindingAndAccount() {
        createProfile("eligible", FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, "assistant-1", "account-1");
        createProfile("no-account", FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, "assistant-1", null);
        createProfile("no-assistant", FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, null, "account-2");
        createProfile("outbound-only", FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, false, "assistant-1", "account-3");
        createProfile("inactive", FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE, ChannelProfileStatus.INACTIVE, true, "assistant-1", "account-4");
        createProfile("other", "enterprise.acme.internal-im", ChannelProfileStatus.ACTIVE, true, "assistant-1", "account-5");

        manager.reconcile();

        assertTrue(manager.isStarted("eligible"));
        assertFalse(manager.isStarted("no-account"));
        assertFalse(manager.isStarted("no-assistant"));
        assertFalse(manager.isStarted("outbound-only"));
        assertFalse(manager.isStarted("inactive"));
        assertFalse(manager.isStarted("other"));
        assertEquals("account-1", credentialProvider.accountId.get());
        assertEquals("eligible", clientFactory.profileId.get());
        assertEquals(1, clientFactory.startCalls.get());
    }

    @Test
    void keepsNonBlockingSdkClientStartedAcrossReconcileCycles() {
        clientFactory = new CapturingClientFactory();
        manager = manager(clientFactory);
        createProfile("eligible", FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, "assistant-1", "account-1");

        manager.reconcile();
        manager.reconcile();

        assertTrue(manager.isStarted("eligible"));
        assertEquals(1, clientFactory.startCalls.get());
    }

    @Test
    void startsOneLongConnectionClientPerIntegrationAccount() {
        clientFactory = new CapturingClientFactory();
        manager = manager(clientFactory);
        createProfile("eligible-a", FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, "assistant-1", "account-1");
        createProfile("eligible-b", FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, "assistant-2", "account-1");

        manager.reconcile();
        manager.reconcile();

        assertEquals(1, clientFactory.startCalls.get());
    }

    @Test
    void retriesAccountWhenSdkStartFails() {
        clientFactory = new CapturingClientFactory();
        clientFactory.failNextStart = true;
        manager = manager(clientFactory);
        createProfile("eligible", FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, "assistant-1", "account-1");

        manager.reconcile();
        assertFalse(manager.isStarted("eligible"));

        manager.reconcile();

        assertTrue(manager.isStarted("eligible"));
        assertEquals(2, clientFactory.startCalls.get());
    }

    private void createProfile(
        String profileId,
        String providerType,
        ChannelProfileStatus status,
        boolean inboundEnabled,
        String assistantId,
        String accountId
    ) {
        Instant now = Instant.parse("2026-04-30T00:00:00Z");
        repository.createProfile(new ChannelGatewayProfile(
            profileId,
            providerType,
            "Channel",
            status,
            inboundEnabled,
            Map.of(),
            assistantId == null ? null : new ChannelAssistantBinding(assistantId, null),
            accountId,
            false,
            1,
            now,
            now
        ), null);
    }

    private static final class CapturingCredentialProvider implements FeishuCredentialProvider {
        private final AtomicReference<String> accountId = new AtomicReference<>();

        @Override
        public FeishuAppCredential resolve(String accountId, Map<String, Object> profileConfig) {
            this.accountId.set(accountId);
            return new FeishuAppCredential(accountId, "cli_test", "secret_test");
        }
    }

    private static final class CapturingClientFactory implements FeishuLongConnectionClientFactory {
        private final AtomicInteger startCalls = new AtomicInteger();
        private final AtomicReference<String> profileId = new AtomicReference<>();
        private boolean failNextStart;

        @Override
        public void start(Supplier<FeishuLongConnectionProfile> profileResolver, FeishuAppCredential credential) {
            startCalls.incrementAndGet();
            if (failNextStart) {
                failNextStart = false;
                throw new IllegalStateException("start failed");
            }
            FeishuLongConnectionProfile profile = profileResolver.get();
            profileId.set(profile.channelProfileId());
        }
    }
}
