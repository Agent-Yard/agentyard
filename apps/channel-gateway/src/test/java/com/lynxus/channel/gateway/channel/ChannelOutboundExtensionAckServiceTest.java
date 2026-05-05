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

import com.lynxus.channel.gateway.extension.ChannelProviderDescriptor;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistry;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistryLoadResult;
import com.lynxus.channel.gateway.extension.ExtensionRegistrationProperties;
import com.lynxus.channel.gateway.extension.ExtensionRegistrationService;
import com.lynxus.channel.gateway.shared.ConflictException;
import com.lynxus.channel.gateway.shared.UnprocessableEntityException;
import com.lynxus.contracts.channel.ChannelContracts;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameAck;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameCheckpoint;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderOutboundCapability;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChannelOutboundExtensionAckServiceTest {
    @Test
    void oldAckIsAcceptedAsNoopWithoutPendingMarker() {
        Fixture fixture = new Fixture();
        when(fixture.repository.findProfile("profile-1")).thenReturn(Optional.of(fixture.profile));
        when(fixture.repository.findOutboundFinalCheckpoint(fixture.consumer)).thenReturn(Optional.of(new ChannelOutboundFrameCheckpoint(
            fixture.consumer,
            100L,
            "frame-100",
            "session-1",
            "message-100",
            Instant.parse("2026-05-05T00:00:00Z")
        )));

        var response = fixture.service.ack(headers(), ack(99, "frame-99", "message-99"));

        assertEquals(true, response.accepted());
        assertEquals(true, response.duplicate());
        assertEquals(100L, response.lastAckedFinalSequence());
        verify(fixture.pendingStore, never()).ackForwarded(fixture.consumer, ack(99, "frame-99", "message-99"));
    }

    @Test
    void markerMissRejectsFutureAckWithoutAdvancingCheckpoint() {
        Fixture fixture = new Fixture();
        when(fixture.repository.findProfile("profile-1")).thenReturn(Optional.of(fixture.profile));
        when(fixture.repository.findOutboundFinalCheckpoint(fixture.consumer)).thenReturn(Optional.empty());
        when(fixture.pendingStore.ackForwarded(fixture.consumer, ack(101, "frame-101", "message-101")))
            .thenReturn(ChannelOutboundForwardedPendingStore.AckPendingResult.MARKER_MISSING);

        assertThrows(ConflictException.class, () -> fixture.service.ack(headers(), ack(101, "frame-101", "message-101")));
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
    void nonHeadAckIsRejectedWithoutAdvancingCheckpoint() {
        Fixture fixture = new Fixture();
        when(fixture.repository.findProfile("profile-1")).thenReturn(Optional.of(fixture.profile));
        when(fixture.repository.findOutboundFinalCheckpoint(fixture.consumer)).thenReturn(Optional.empty());
        when(fixture.pendingStore.ackForwarded(fixture.consumer, ack(102, "frame-102", "message-102")))
            .thenReturn(ChannelOutboundForwardedPendingStore.AckPendingResult.NON_HEAD);

        assertThrows(ConflictException.class, () -> fixture.service.ack(headers(), ack(102, "frame-102", "message-102")));
    }

    @Test
    void markerMismatchReturnsUnprocessableEntity() {
        Fixture fixture = new Fixture();
        when(fixture.repository.findProfile("profile-1")).thenReturn(Optional.of(fixture.profile));
        when(fixture.repository.findOutboundFinalCheckpoint(fixture.consumer)).thenReturn(Optional.empty());
        when(fixture.pendingStore.ackForwarded(fixture.consumer, ack(103, "frame-103", "message-103")))
            .thenReturn(ChannelOutboundForwardedPendingStore.AckPendingResult.MARKER_MISMATCH);

        assertThrows(UnprocessableEntityException.class, () -> fixture.service.ack(headers(), ack(103, "frame-103", "message-103")));
    }

    @Test
    void validAckAdvancesCheckpointAndWakesUpstreamRelay() {
        Fixture fixture = new Fixture();
        when(fixture.repository.findProfile("profile-1")).thenReturn(Optional.of(fixture.profile));
        when(fixture.repository.findOutboundFinalCheckpoint(fixture.consumer)).thenReturn(Optional.empty());
        when(fixture.pendingStore.ackForwarded(fixture.consumer, ack(104, "frame-104", "message-104")))
            .thenReturn(ChannelOutboundForwardedPendingStore.AckPendingResult.ACKED);
        when(fixture.repository.advanceOutboundFinalCheckpoint(
            eq(fixture.consumer),
            eq(104L),
            eq("frame-104"),
            eq("session-1"),
            eq("message-104"),
            any(Instant.class)
        )).thenReturn(true);

        var response = fixture.service.ack(headers(), ack(104, "frame-104", "message-104"));

        assertEquals(true, response.accepted());
        assertEquals(false, response.duplicate());
        assertEquals(104L, response.lastAckedFinalSequence());
        verify(fixture.upstreamRelaySupervisor).reconcileSubscriptions();
    }

    private static ChannelOutboundExtensionHeaders headers() {
        return new ChannelOutboundExtensionHeaders("acme-channel-provider", "CHANNEL_PROVIDER", "enterprise.acme.im");
    }

    private static ChannelOutboundFrameAck ack(long finalSequence, String frameId, String sessionMessageId) {
        return new ChannelOutboundFrameAck(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_ACK_PROTOCOL,
            "profile-1",
            "enterprise.acme.im",
            frameId,
            finalSequence,
            "session-1",
            sessionMessageId,
            Map.of()
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

    private static ExtensionRegistrationService registrationService() {
        try {
            Path file = Files.createTempFile("lynxus-extension-registration", ".yaml");
            Files.writeString(file, """
                lynxus:
                  extensions:
                    services:
                      - registrationId: acme-channel-provider
                        baseUrl: http://channel.example.com
                        exposes:
                          channelProviderTypes:
                            - enterprise.acme.im
                        auth:
                          type: INTERNAL_TOKEN
                """);
            return new ExtensionRegistrationService(
                new ExtensionRegistrationProperties(file.toString()),
                "http://channel-gateway.example.com",
                "http://agent-runtime.example.com"
            );
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class Fixture {
        private final ChannelAdminRepository repository = mock(ChannelAdminRepository.class);
        private final ChannelProviderDescriptor descriptor = descriptor();
        private final ChannelGatewayProfile profile = new ChannelGatewayProfile(
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
            Instant.parse("2026-05-05T00:00:00Z"),
            Instant.parse("2026-05-05T00:00:00Z")
        );
        private final ChannelOutboundProfileConsumer consumer = new ChannelOutboundProfileConsumer(
            "profile-1",
            "enterprise.acme.im",
            ChannelOutboundConsumerKind.REMOTE_EXTENSION,
            "acme-channel-provider",
            "acme-channel-provider"
        );
        private final ChannelOutboundForwardedPendingStore pendingStore = mock(ChannelOutboundForwardedPendingStore.class);
        private final ChannelOutboundUpstreamRelaySupervisor upstreamRelaySupervisor = mock(ChannelOutboundUpstreamRelaySupervisor.class);
        private final ChannelOutboundExtensionAckService service = new ChannelOutboundExtensionAckService(
            repository,
            new ChannelOutboundExtensionAccessService(repository, new StaticRegistry(descriptor), registrationService()),
            pendingStore,
            upstreamRelaySupervisor
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
