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
import com.lynxus.contracts.channel.ChannelContracts;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameCheckpoint;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
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

    @Test
    void nativeProfileStartsWithoutExtensionStreamAndFiltersUnsupportedTransientFrames() throws Exception {
        NativeFixture fixture = new NativeFixture();

        fixture.supervisor.reconcileSubscriptions();
        fixture.executor.shutdown();
        fixture.executor.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals(List.of(nativeFinalFrame(1)), fixture.handoff.drain(fixture.consumer, 10));
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

    private static final class EmittingStreamClient extends ChannelOutboundApiFrameStreamClient {
        private EmittingStreamClient() {
            super(new ObjectMapper(), "http://api.example.com", "token", HttpClient.newHttpClient(), Duration.ofSeconds(1));
        }

        @Override
        void stream(StreamRequest request, StreamHandler handler) throws Exception {
            handler.onFrame("1", nativeTransientFrame(ChannelOutboundFrameKind.TYPING_START, Map.of("messageId", "message-1")));
            handler.onFrame("2", nativeTransientFrame(ChannelOutboundFrameKind.DRAFT_UPDATE, Map.of(
                "messageId", "message-1",
                "blockId", "block-1",
                "blockType", "TEXT",
                "delta", "draft"
            )));
            handler.onFrame("3", nativeFinalFrame(1));
        }
    }

    private static final class NativeFixture {
        private final ChannelAdminRepository repository = mock(ChannelAdminRepository.class);
        private final ChannelOutboundStreamOwnerLockService ownerLockService = mock(ChannelOutboundStreamOwnerLockService.class);
        private final ChannelOutboundDownstreamConsumerRegistry downstreamRegistry = new ChannelOutboundDownstreamConsumerRegistry();
        private final ChannelOutboundForwardedPendingStore pendingStore = mock(ChannelOutboundForwardedPendingStore.class);
        private final InMemoryChannelOutboundFrameHandoff handoff = new InMemoryChannelOutboundFrameHandoff();
        private final ChannelProviderDescriptor descriptor = nativeDescriptor();
        private final ChannelGatewayProfile profile = nativeProfile();
        private final ChannelOutboundProfileConsumer consumer = new ChannelOutboundProfileConsumer(
            profile.id(),
            profile.providerType(),
            ChannelOutboundConsumerKind.GATEWAY_NATIVE,
            "native:native-provider",
            null
        );
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final ChannelOutboundUpstreamRelaySupervisor supervisor;

        private NativeFixture() {
            when(repository.listProfiles()).thenReturn(List.of(profile));
            when(repository.findOutboundFinalCheckpoint(consumer)).thenReturn(Optional.empty());
            when(ownerLockService.acquire(eq(consumer), anyString(), eq(Duration.ofSeconds(30)))).thenReturn(true);
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
                new EmittingStreamClient(),
                executor,
                "owner"
            );
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
            new ChannelProviderOutboundCapability("FRAME_STREAM", true, true, true, true),
            Map.of()
        );
    }

    private static ChannelGatewayProfile nativeProfile() {
        Instant now = Instant.parse("2026-05-05T00:00:00Z");
        return new ChannelGatewayProfile(
            "profile-1",
            "native-provider",
            "Native profile",
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

    private static ChannelProviderDescriptor nativeDescriptor() {
        return new ChannelProviderDescriptor(
            "native-provider",
            null,
            null,
            null,
            true,
            Map.of(),
            "digest",
            Map.of(),
            Map.of(),
            new ChannelProviderOutboundCapability("FRAME_STREAM", false, false, true, true),
            Map.of()
        );
    }

    private static ChannelOutboundFrame nativeTransientFrame(ChannelOutboundFrameKind kind, Map<String, Object> payload) {
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            "profile-1:turn-execution-1:1:" + kind,
            "profile-1",
            "native-provider",
            "assistant-1",
            "conversation-1",
            "session-1",
            "turn-1",
            "turn-execution-1",
            1L,
            null,
            kind,
            Instant.parse("2026-05-05T00:00:00Z"),
            "profile-1:turn-execution-1:1:" + kind,
            payload,
            null
        );
    }

    private static ChannelOutboundFrame nativeFinalFrame(long finalSequence) {
        String frameId = "profile-1:session-1:message-" + finalSequence + ":FINAL_DELIVERY";
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            frameId,
            "profile-1",
            "native-provider",
            "assistant-1",
            "conversation-1",
            "session-1",
            null,
            null,
            null,
            finalSequence,
            ChannelOutboundFrameKind.FINAL_DELIVERY,
            Instant.parse("2026-05-05T00:00:00Z"),
            frameId,
            Map.of(
                "sessionMessageId", "message-" + finalSequence,
                "messageSequence", finalSequence,
                "messageBlocks", List.of(Map.of("type", "TEXT", "text", "hello"))
            ),
            null
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
