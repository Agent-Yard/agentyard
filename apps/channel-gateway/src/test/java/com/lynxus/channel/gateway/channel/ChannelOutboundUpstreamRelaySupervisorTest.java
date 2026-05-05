package com.lynxus.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.channel.gateway.extension.ChannelProviderDescriptor;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistry;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistryLoadResult;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameCheckpoint;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderOutboundCapability;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChannelOutboundUpstreamRelaySupervisorTest {
    @Test
    void remoteProfileDoesNotPreconsumeApiReplayWithoutDownstreamStream() {
        Fixture fixture = new Fixture();

        fixture.supervisor.reconcileSubscriptions();

        verify(fixture.ownerLockService, never()).acquire(eq(fixture.consumer), anyString(), any(Duration.class));
    }

    @Test
    void streamConnectUsesGatewayCheckpointAsApiReplayCheckpoint() throws Exception {
        Fixture fixture = new Fixture();
        fixture.downstreamRegistry.markActive(fixture.consumer);
        when(fixture.repository.findOutboundFinalCheckpoint(fixture.consumer)).thenReturn(Optional.of(new ChannelOutboundFrameCheckpoint(
            fixture.consumer,
            42L,
            "frame-42",
            "session-1",
            "message-42",
            Instant.parse("2026-05-05T00:00:00Z")
        )));

        fixture.supervisor.reconcileSubscriptions();
        fixture.executor.shutdown();
        fixture.executor.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals(42L, fixture.streamClient.request.get().lastAckedFinalSequence());
        assertEquals("session-1", fixture.streamClient.request.get().lastAckedSessionId());
        assertEquals("message-42", fixture.streamClient.request.get().lastAckedSessionMessageId());
    }

    @Test
    void unackedFinalReplayStartsFromNoCheckpoint() throws Exception {
        Fixture fixture = new Fixture();
        fixture.downstreamRegistry.markActive(fixture.consumer);
        when(fixture.repository.findOutboundFinalCheckpoint(fixture.consumer)).thenReturn(Optional.empty());

        fixture.supervisor.reconcileSubscriptions();
        fixture.executor.shutdown();
        fixture.executor.awaitTermination(5, TimeUnit.SECONDS);

        assertNull(fixture.streamClient.request.get().lastAckedFinalSequence());
    }

    private static final class Fixture {
        private final ChannelAdminRepository repository = mock(ChannelAdminRepository.class);
        private final ChannelOutboundStreamOwnerLockService ownerLockService = mock(ChannelOutboundStreamOwnerLockService.class);
        private final ChannelOutboundDownstreamConsumerRegistry downstreamRegistry = new ChannelOutboundDownstreamConsumerRegistry();
        private final ChannelOutboundForwardedPendingStore pendingStore = mock(ChannelOutboundForwardedPendingStore.class);
        private final InMemoryChannelOutboundFrameHandoff handoff = new InMemoryChannelOutboundFrameHandoff();
        private final ChannelProviderDescriptor descriptor = descriptor();
        private final ChannelGatewayProfile profile = profile();
        private final ChannelOutboundProfileConsumer consumer = new ChannelOutboundProfileConsumer(
            profile.id(),
            profile.providerType(),
            ChannelOutboundConsumerKind.REMOTE_EXTENSION,
            "acme-channel-provider",
            "acme-channel-provider"
        );
        private final CapturingStreamClient streamClient = new CapturingStreamClient();
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final ChannelOutboundUpstreamRelaySupervisor supervisor;

        private Fixture() {
            when(repository.listProfiles()).thenReturn(List.of(profile));
            when(ownerLockService.acquire(eq(consumer), anyString(), eq(Duration.ofSeconds(30)))).thenReturn(true);
            when(pendingStore.pendingFinalCount(consumer)).thenReturn(0L);
            ChannelOutboundRelayProperties properties = new ChannelOutboundRelayProperties();
            supervisor = new ChannelOutboundUpstreamRelaySupervisor(
                repository,
                new ChannelOutboundProfileConsumerResolver(new StaticRegistry(descriptor)),
                ownerLockService,
                downstreamRegistry,
                pendingStore,
                handoff,
                new ChannelOutboundCapabilityFilter(),
                new ChannelOutboundReplayCreditCalculator(),
                properties,
                streamClient,
                executor,
                "owner"
            );
        }
    }

    private static final class CapturingStreamClient extends ChannelOutboundApiFrameStreamClient {
        private final AtomicReference<StreamRequest> request = new AtomicReference<>();

        private CapturingStreamClient() {
            super(new ObjectMapper(), "http://api.example.com", "token", HttpClient.newHttpClient(), Duration.ofSeconds(1));
        }

        @Override
        void stream(StreamRequest request, StreamHandler handler) {
            this.request.set(request);
        }
    }

    private static ChannelGatewayProfile profile() {
        Instant now = Instant.parse("2026-05-05T00:00:00Z");
        return new ChannelGatewayProfile(
            "profile-1",
            "enterprise.acme.im",
            "Profile",
            ChannelProfileStatus.ACTIVE,
            true,
            Map.of(),
            null,
            null,
            false,
            1,
            now,
            now
        );
    }

    private static ChannelProviderDescriptor descriptor() {
        return new ChannelProviderDescriptor(
            "enterprise.acme.im",
            "acme-channel-provider",
            "http://provider.example.com",
            null,
            false,
            Map.of(),
            "digest",
            Map.of(),
            Map.of(),
            new ChannelProviderOutboundCapability("FRAME_STREAM", true, true, true, false, true),
            Map.of()
        );
    }

    private record StaticRegistry(ChannelProviderDescriptor descriptor) implements ChannelProviderRegistry {
        @Override
        public ChannelProviderRegistryLoadResult snapshot() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelProviderDescriptor requireProvider(String providerType) {
            return descriptor;
        }

        @Override
        public Map<String, Object> materializeAndValidateProfileConfig(String providerType, Map<String, Object> config) {
            return config == null ? Map.of() : Map.copyOf(config);
        }
    }
}
