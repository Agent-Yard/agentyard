package com.lynxus.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lynxus.channel.gateway.extension.ChannelProviderDescriptor;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistry;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistryLoadResult;
import com.lynxus.channel.gateway.extension.ExtensionRegistrationProperties;
import com.lynxus.channel.gateway.extension.ExtensionRegistrationService;
import com.lynxus.contracts.channel.ChannelContracts;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameAck;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameCheckpoint;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderOutboundCapability;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChannelOutboundFakeExtensionFlowTest {
    @Test
    void fakeExtensionReplaysUnackedFinalAndStopsReplayAfterAckAdvancesCheckpoint() throws Exception {
        Fixture fixture = new Fixture();
        fixture.downstreamRegistry.markActive(fixture.consumer);

        fixture.reconnectToApiStream();
        fixture.extension.drainStream(false);

        assertEquals(1, fixture.extension.externalDeliveries());
        assertEquals(null, fixture.checkpoint.get());

        fixture.reconnectToApiStream();
        fixture.extension.drainStream(true);

        assertEquals(1, fixture.extension.externalDeliveries());
        assertEquals(501L, fixture.checkpoint.get().lastAckedFinalSequence());

        fixture.reconnectToApiStream();
        fixture.extension.drainStream(true);

        assertEquals(1, fixture.extension.externalDeliveries());
        assertEquals(2, fixture.streamClient.emittedFrames.get());
        assertEquals(0, fixture.handoff.pendingFinals(fixture.consumer));
    }

    private static final class Fixture {
        private final ChannelAdminRepository repository = mock(ChannelAdminRepository.class);
        private final ChannelOutboundStreamOwnerLockService ownerLockService = mock(ChannelOutboundStreamOwnerLockService.class);
        private final ChannelOutboundDownstreamConsumerRegistry downstreamRegistry = new ChannelOutboundDownstreamConsumerRegistry();
        private final ChannelOutboundForwardedPendingStore pendingStore = mock(ChannelOutboundForwardedPendingStore.class);
        private final InMemoryChannelOutboundFrameHandoff handoff = new InMemoryChannelOutboundFrameHandoff();
        private final ChannelGatewayProfile profile = profile();
        private final ChannelProviderDescriptor descriptor = descriptor();
        private final ChannelOutboundProfileConsumer consumer = new ChannelOutboundProfileConsumer(
            profile.id(),
            profile.providerType(),
            ChannelOutboundConsumerKind.REMOTE_EXTENSION,
            "acme-channel-provider",
            "acme-channel-provider"
        );
        private final AtomicReference<ChannelOutboundFrameCheckpoint> checkpoint = new AtomicReference<>();
        private final ReplayStreamClient streamClient = new ReplayStreamClient(finalFrame());
        private final ChannelOutboundExtensionAckService ackService;
        private final FakeExtension extension;

        private Fixture() {
            when(repository.listProfiles()).thenReturn(List.of(profile));
            when(repository.findProfile(profile.id())).thenReturn(Optional.of(profile));
            when(repository.findOutboundFinalCheckpoint(consumer)).thenAnswer(ignored -> Optional.ofNullable(checkpoint.get()));
            when(repository.advanceOutboundFinalCheckpoint(
                eq(consumer),
                anyLong(),
                anyString(),
                anyString(),
                anyString(),
                any(Instant.class)
            )).thenAnswer(invocation -> {
                checkpoint.set(new ChannelOutboundFrameCheckpoint(
                    consumer,
                    invocation.getArgument(1, Long.class),
                    invocation.getArgument(2, String.class),
                    invocation.getArgument(3, String.class),
                    invocation.getArgument(4, String.class),
                    invocation.getArgument(5, Instant.class)
                ));
                return true;
            });
            when(ownerLockService.acquire(eq(consumer), anyString(), eq(Duration.ofSeconds(30)))).thenReturn(true);
            when(pendingStore.pendingFinalCount(consumer)).thenReturn(0L);
            when(pendingStore.ackForwarded(eq(consumer), any(ChannelOutboundFrameAck.class)))
                .thenReturn(ChannelOutboundForwardedPendingStore.AckPendingResult.ACKED);
            ChannelOutboundUpstreamRelaySupervisor ackWakeupSupervisor = mock(ChannelOutboundUpstreamRelaySupervisor.class);
            ackService = new ChannelOutboundExtensionAckService(
                repository,
                new ChannelOutboundExtensionAccessService(repository, new StaticRegistry(descriptor), registrationService()),
                pendingStore,
                ackWakeupSupervisor
            );
            extension = new FakeExtension(consumer, handoff, pendingStore, ackService);
        }

        private void reconnectToApiStream() throws InterruptedException {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            ChannelOutboundRelayProperties properties = new ChannelOutboundRelayProperties();
            ChannelOutboundUpstreamRelaySupervisor supervisor = new ChannelOutboundUpstreamRelaySupervisor(
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
            int expectedRequests = streamClient.requestCount() + 1;
            supervisor.reconcileSubscriptions();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            assertEquals(expectedRequests, streamClient.requestCount());
        }
    }

    private static final class FakeExtension {
        private final ChannelOutboundProfileConsumer consumer;
        private final ChannelOutboundFrameHandoff handoff;
        private final ChannelOutboundForwardedPendingStore pendingStore;
        private final ChannelOutboundExtensionAckService ackService;
        private final Set<String> processedFrameIds = new HashSet<>();
        private int externalDeliveries;

        private FakeExtension(
            ChannelOutboundProfileConsumer consumer,
            ChannelOutboundFrameHandoff handoff,
            ChannelOutboundForwardedPendingStore pendingStore,
            ChannelOutboundExtensionAckService ackService
        ) {
            this.consumer = consumer;
            this.handoff = handoff;
            this.pendingStore = pendingStore;
            this.ackService = ackService;
        }

        private void drainStream(boolean ackFinal) {
            for (ChannelOutboundFrame frame : handoff.drain(consumer, 25)) {
                if (frame.kind() != ChannelOutboundFrameKind.FINAL_DELIVERY) {
                    continue;
                }
                pendingStore.markForwarded(consumer, frame);
                if (processedFrameIds.add(frame.frameId())) {
                    externalDeliveries += 1;
                }
                if (ackFinal) {
                    ackService.ack(headers(), ack(frame));
                }
            }
        }

        private int externalDeliveries() {
            return externalDeliveries;
        }
    }

    private static final class ReplayStreamClient extends ChannelOutboundApiFrameStreamClient {
        private final ChannelOutboundFrame frame;
        private final AtomicInteger emittedFrames = new AtomicInteger();
        private final List<StreamRequest> requests = new ArrayList<>();

        private ReplayStreamClient(ChannelOutboundFrame frame) {
            super(new ObjectMapper(), "http://api.example.com", "token", HttpClient.newHttpClient(), Duration.ofSeconds(1));
            this.frame = frame;
        }

        @Override
        void stream(StreamRequest request, StreamHandler handler) throws Exception {
            requests.add(request);
            if (request.lastAckedFinalSequence() == null || request.lastAckedFinalSequence() < frame.finalSequence()) {
                emittedFrames.incrementAndGet();
                handler.onFrame("cursor-" + requests.size(), frame);
            }
        }

        private int requestCount() {
            return requests.size();
        }
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

    private static ChannelOutboundExtensionHeaders headers() {
        return new ChannelOutboundExtensionHeaders("acme-channel-provider", "CHANNEL_PROVIDER", "enterprise.acme.im");
    }

    private static ChannelOutboundFrameAck ack(ChannelOutboundFrame frame) {
        return new ChannelOutboundFrameAck(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_ACK_PROTOCOL,
            frame.channelProfileId(),
            frame.providerType(),
            frame.frameId(),
            frame.finalSequence(),
            frame.sessionId(),
            String.valueOf(frame.payload().get("sessionMessageId")),
            Map.of()
        );
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

    private static ChannelOutboundFrame finalFrame() {
        String frameId = "profile-1:session-1:message-501:FINAL_DELIVERY";
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            frameId,
            "profile-1",
            "enterprise.acme.im",
            "assistant-1",
            "conversation-1",
            "session-1",
            null,
            null,
            null,
            501L,
            ChannelOutboundFrameKind.FINAL_DELIVERY,
            Instant.parse("2026-05-05T00:00:00Z"),
            frameId,
            Map.of(
                "sessionMessageId", "message-501",
                "messageSequence", 501,
                "messageBlocks", List.of(Map.of("type", "TEXT", "text", "hello"))
            ),
            null
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
}
