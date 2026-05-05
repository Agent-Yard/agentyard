package com.lynxus.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.channel.gateway.channel.ChannelOutboundProfileConsumerResolver.ResolvedProfileConsumer;
import com.lynxus.channel.gateway.extension.ChannelProviderDescriptor;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistry;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistryLoadResult;
import com.lynxus.channel.gateway.extension.GatewayNativeChannelProviderAdapter;
import com.lynxus.channel.gateway.extension.GatewayNativeChannelProviderAdapters;
import com.lynxus.contracts.channel.ChannelContracts;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameCheckpoint;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderOutboundCapability;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GatewayNativeChannelOutboundFrameDispatcherTest {
    @Test
    void nativeFinalAdvancesCheckpointOnlyAfterAdapterConsumesFrame() {
        Fixture fixture = new Fixture();
        ChannelOutboundFrame frame = finalFrame(101);
        fixture.handoff.offer(fixture.consumer, frame, 1, 0);
        when(fixture.repository.findOutboundFinalCheckpoint(fixture.consumer)).thenReturn(Optional.empty());
        when(fixture.repository.advanceOutboundFinalCheckpoint(
            eq(fixture.consumer),
            eq(101L),
            eq(frame.frameId()),
            eq("session-1"),
            eq("message-101"),
            any(Instant.class)
        )).thenReturn(true);

        fixture.dispatcher.dispatchOne(fixture.resolved);

        assertEquals(List.of(frame), fixture.adapter.frames);
        verify(fixture.repository).advanceOutboundFinalCheckpoint(
            eq(fixture.consumer),
            eq(101L),
            eq(frame.frameId()),
            eq("session-1"),
            eq("message-101"),
            any(Instant.class)
        );
        verify(fixture.upstreamRelaySupervisor).reconcileSubscriptions();
    }

    @Test
    void duplicateFinalAlreadyCoveredByCheckpointDoesNotReachNativeProvider() {
        Fixture fixture = new Fixture();
        ChannelOutboundFrame frame = finalFrame(101);
        fixture.handoff.offer(fixture.consumer, frame, 1, 0);
        when(fixture.repository.findOutboundFinalCheckpoint(fixture.consumer)).thenReturn(Optional.of(new ChannelOutboundFrameCheckpoint(
            fixture.consumer,
            101L,
            frame.frameId(),
            "session-1",
            "message-101",
            Instant.parse("2026-05-05T00:00:00Z")
        )));

        fixture.dispatcher.dispatchOne(fixture.resolved);

        assertEquals(List.of(), fixture.adapter.frames);
        verify(fixture.repository, never()).advanceOutboundFinalCheckpoint(
            any(),
            anyLong(),
            any(),
            any(),
            any(),
            any(Instant.class)
        );
        verify(fixture.upstreamRelaySupervisor).reconcileSubscriptions();
    }

    @Test
    void nativeFinalFailureRequeuesBeforeCheckpointAdvances() {
        Fixture fixture = new Fixture();
        ChannelOutboundFrame frame = finalFrame(102);
        fixture.adapter.fail = true;
        fixture.handoff.offer(fixture.consumer, frame, 1, 0);
        when(fixture.repository.findOutboundFinalCheckpoint(fixture.consumer)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> fixture.dispatcher.dispatchOne(fixture.resolved));

        assertEquals(List.of(frame), fixture.handoff.drain(fixture.consumer, 1));
        verify(fixture.repository, never()).advanceOutboundFinalCheckpoint(
            any(),
            anyLong(),
            any(),
            any(),
            any(),
            any(Instant.class)
        );
    }

    @Test
    void scheduledDispatchOnlyRunsGatewayNativeProfiles() {
        Fixture fixture = new Fixture();
        ChannelGatewayProfile remoteProfile = profile("remote-profile", "remote-provider");
        ChannelOutboundProfileConsumer remoteConsumer = new ChannelOutboundProfileConsumer(
            "remote-profile",
            "remote-provider",
            ChannelOutboundConsumerKind.REMOTE_EXTENSION,
            "remote-registration",
            "remote-registration"
        );
        fixture.handoff.offer(remoteConsumer, remoteFinalFrame(201), 1, 0);
        fixture.handoff.offer(fixture.consumer, finalFrame(103), 1, 0);
        when(fixture.repository.listProfiles()).thenReturn(List.of(remoteProfile, fixture.profile));
        when(fixture.repository.findOutboundFinalCheckpoint(fixture.consumer)).thenReturn(Optional.empty());
        when(fixture.repository.advanceOutboundFinalCheckpoint(
            eq(fixture.consumer),
            eq(103L),
            any(),
            eq("session-1"),
            eq("message-103"),
            any(Instant.class)
        )).thenReturn(true);

        fixture.dispatcher.dispatchAvailableFrames();

        assertEquals(List.of(finalFrame(103)), fixture.adapter.frames);
        assertEquals(List.of(remoteFinalFrame(201)), fixture.handoff.drain(remoteConsumer, 1));
    }

    private static final class Fixture {
        private final ChannelAdminRepository repository = mock(ChannelAdminRepository.class);
        private final InMemoryChannelOutboundFrameHandoff handoff = new InMemoryChannelOutboundFrameHandoff();
        private final ChannelOutboundUpstreamRelaySupervisor upstreamRelaySupervisor = mock(ChannelOutboundUpstreamRelaySupervisor.class);
        private final CapturingNativeAdapter adapter = new CapturingNativeAdapter("native-provider");
        private final ChannelGatewayProfile profile = profile("profile-1", "native-provider");
        private final ChannelOutboundProfileConsumer consumer = new ChannelOutboundProfileConsumer(
            "profile-1",
            "native-provider",
            ChannelOutboundConsumerKind.GATEWAY_NATIVE,
            "native:native-provider",
            null
        );
        private final ChannelProviderDescriptor descriptor = descriptor("native-provider", true, null);
        private final ResolvedProfileConsumer resolved = new ResolvedProfileConsumer(profile, descriptor, consumer);
        private final GatewayNativeChannelOutboundFrameDispatcher dispatcher = new GatewayNativeChannelOutboundFrameDispatcher(
            repository,
            new ChannelOutboundProfileConsumerResolver(new StaticRegistry(Map.of(
                "native-provider", descriptor,
                "remote-provider", descriptor("remote-provider", false, "remote-registration")
            ))),
            new GatewayNativeChannelProviderAdapters(List.of(adapter)),
            handoff,
            upstreamRelaySupervisor,
            new ChannelOutboundRelayProperties()
        );
    }

    private static final class CapturingNativeAdapter implements GatewayNativeChannelProviderAdapter {
        private final String providerType;
        private final List<ChannelOutboundFrame> frames = new ArrayList<>();
        private boolean fail;

        private CapturingNativeAdapter(String providerType) {
            this.providerType = providerType;
        }

        @Override
        public String providerType() {
            return providerType;
        }

        @Override
        public Map<String, Object> descriptor() {
            return Map.of();
        }

        @Override
        public void consumeOutboundFrame(ChannelGatewayProfile profile, ChannelOutboundFrame frame) {
            if (fail) {
                throw new IllegalStateException("native delivery failed");
            }
            frames.add(frame);
        }
    }

    private record StaticRegistry(Map<String, ChannelProviderDescriptor> descriptors) implements ChannelProviderRegistry {
        @Override
        public ChannelProviderRegistryLoadResult snapshot() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelProviderDescriptor requireProvider(String providerType) {
            ChannelProviderDescriptor descriptor = descriptors.get(providerType);
            if (descriptor == null) {
                throw new IllegalArgumentException("unknown provider: " + providerType);
            }
            return descriptor;
        }

        @Override
        public Map<String, Object> materializeAndValidateProfileConfig(String providerType, Map<String, Object> config) {
            return config == null ? Map.of() : Map.copyOf(config);
        }
    }

    private static ChannelGatewayProfile profile(String profileId, String providerType) {
        Instant now = Instant.parse("2026-05-05T00:00:00Z");
        return new ChannelGatewayProfile(
            profileId,
            providerType,
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

    private static ChannelProviderDescriptor descriptor(String providerType, boolean gatewayNative, String registrationId) {
        return new ChannelProviderDescriptor(
            providerType,
            registrationId,
            gatewayNative ? null : "http://provider.example.com",
            null,
            gatewayNative,
            Map.of(),
            "digest",
            Map.of(),
            Map.of(),
            new ChannelProviderOutboundCapability("FRAME_STREAM", false, false, true, false, true),
            Map.of()
        );
    }

    private static ChannelOutboundFrame remoteFinalFrame(long finalSequence) {
        String frameId = "remote-profile:session-1:message-" + finalSequence + ":FINAL_DELIVERY";
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            frameId,
            "remote-profile",
            "remote-provider",
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
            null,
            Map.of(
                "sessionMessageId", "message-" + finalSequence,
                "messageSequence", finalSequence,
                "messageBlocks", List.of(Map.of("type", "TEXT", "text", "hello"))
            ),
            null
        );
    }

    private static ChannelOutboundFrame finalFrame(long finalSequence) {
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
            null,
            Map.of(
                "sessionMessageId", "message-" + finalSequence,
                "messageSequence", finalSequence,
                "messageBlocks", List.of(Map.of("type", "TEXT", "text", "hello"))
            ),
            null
        );
    }
}
