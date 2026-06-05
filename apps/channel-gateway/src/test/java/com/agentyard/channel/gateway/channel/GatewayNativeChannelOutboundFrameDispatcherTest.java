package com.agentyard.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentyard.channel.gateway.channel.ChannelOutboundProfileConsumerResolver.ResolvedProfileConsumer;
import com.agentyard.channel.gateway.extension.ChannelProviderDescriptor;
import com.agentyard.channel.gateway.extension.ChannelProviderRegistry;
import com.agentyard.channel.gateway.extension.ChannelProviderRegistryLoadResult;
import com.agentyard.channel.gateway.extension.GatewayNativeChannelProviderAdapter;
import com.agentyard.channel.gateway.extension.GatewayNativeChannelProviderAdapter.OutboundFrameDispatch;
import com.agentyard.channel.gateway.extension.GatewayNativeChannelProviderAdapters;
import com.agentyard.contracts.channel.ChannelContracts;
import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameCheckpoint;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderOutboundCapability;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    void nativeTransientBatchFailureDropsFailedFrameAndContinuesToFinalDelivery() {
        Fixture fixture = new Fixture();
        ChannelOutboundFrame first = draftUpdateFrame(2, "a");
        ChannelOutboundFrame finalDelivery = finalFrame(102);
        fixture.adapter.failFrameId = first.frameId();
        fixture.handoff.offer(fixture.consumer, first, 1, 10);
        fixture.handoff.offer(fixture.consumer, finalDelivery, 1, 10);
        when(fixture.repository.findOutboundFinalCheckpoint(fixture.consumer)).thenReturn(Optional.empty());
        when(fixture.repository.advanceOutboundFinalCheckpoint(
            eq(fixture.consumer),
            eq(102L),
            eq(finalDelivery.frameId()),
            eq("session-1"),
            eq("message-102"),
            any(Instant.class)
        )).thenReturn(true);

        fixture.dispatcher.dispatchOne(fixture.resolved);

        assertEquals(List.of(finalDelivery), fixture.adapter.frames);
        assertEquals(List.of(), fixture.handoff.drain(fixture.consumer, 10));
        verify(fixture.repository).advanceOutboundFinalCheckpoint(
            eq(fixture.consumer),
            eq(102L),
            eq(finalDelivery.frameId()),
            eq("session-1"),
            eq("message-102"),
            any(Instant.class)
        );
    }

    @Test
    void nativeFinalBatchFailureRequeuesFailedAndUnconsumedFramesInOriginalOrder() {
        Fixture fixture = new Fixture();
        ChannelOutboundFrame first = draftUpdateFrame(2, "a");
        ChannelOutboundFrame second = finalFrame(103);
        ChannelOutboundFrame third = draftUpdateFrame(4, "c");
        fixture.adapter.failFrameId = second.frameId();
        fixture.handoff.offer(fixture.consumer, first, 1, 10);
        fixture.handoff.offer(fixture.consumer, second, 1, 10);
        fixture.handoff.offer(fixture.consumer, third, 1, 10);
        when(fixture.repository.findOutboundFinalCheckpoint(fixture.consumer)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> fixture.dispatcher.dispatchOne(fixture.resolved));

        assertEquals(List.of(first), fixture.adapter.frames);
        assertEquals(List.of(second, third), fixture.handoff.drain(fixture.consumer, 10));
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
    void nativePreparedDispatchRejectsFinalDeliveryMergedWithOtherFrames() {
        Fixture fixture = new Fixture();
        ChannelOutboundFrame first = draftUpdateFrame(2, "a");
        ChannelOutboundFrame finalDelivery = finalFrame(104);
        fixture.adapter.preparedDispatches = List.of(new OutboundFrameDispatch(finalDelivery, List.of(first, finalDelivery)));
        fixture.handoff.offer(fixture.consumer, first, 1, 10);
        fixture.handoff.offer(fixture.consumer, finalDelivery, 1, 10);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> fixture.dispatcher.dispatchOne(fixture.resolved));

        assertTrue(error.getMessage().contains("FINAL_DELIVERY must be dispatched independently"));
        assertEquals(List.of(), fixture.adapter.frames);
        assertEquals(List.of(first, finalDelivery), fixture.handoff.drain(fixture.consumer, 10));
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

    @Test
    void offerTriggersNativeDispatchWithoutWaitingForScheduledScan() throws Exception {
        Fixture fixture = new Fixture();
        ChannelOutboundFrame frame = finalFrame(104);
        when(fixture.repository.findOutboundFinalCheckpoint(fixture.consumer)).thenReturn(Optional.empty());
        when(fixture.repository.advanceOutboundFinalCheckpoint(
            eq(fixture.consumer),
            eq(104L),
            eq(frame.frameId()),
            eq("session-1"),
            eq("message-104"),
            any(Instant.class)
        )).thenReturn(true);

        fixture.dispatcher.start();
        try {
            fixture.handoff.offer(fixture.consumer, frame, 1, 0);

            assertEquals(frame, fixture.adapter.awaitFrame(500));
            verify(fixture.repository).advanceOutboundFinalCheckpoint(
                eq(fixture.consumer),
                eq(104L),
                eq(frame.frameId()),
                eq("session-1"),
                eq("message-104"),
                any(Instant.class)
            );
        } finally {
            fixture.dispatcher.shutdown();
        }
    }

    @Test
    void immediateNativeDispatchRemainsSerialForSameConsumer() throws Exception {
        Fixture fixture = new Fixture();
        ChannelOutboundFrame first = finalFrame(105);
        ChannelOutboundFrame second = finalFrame(106);
        fixture.adapter.blockFirstFrame = true;
        when(fixture.repository.findOutboundFinalCheckpoint(fixture.consumer)).thenReturn(Optional.empty());
        when(fixture.repository.advanceOutboundFinalCheckpoint(
            eq(fixture.consumer),
            anyLong(),
            any(),
            eq("session-1"),
            any(),
            any(Instant.class)
        )).thenReturn(true);

        fixture.dispatcher.start();
        try {
            fixture.handoff.offer(fixture.consumer, first, 1, 0);
            assertTrue(fixture.adapter.firstFrameStarted.await(500, TimeUnit.MILLISECONDS));

            fixture.handoff.offer(fixture.consumer, second, 1, 0);
            fixture.adapter.releaseFirstFrame.countDown();

            assertTrue(fixture.adapter.twoFramesConsumed.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(first, second), fixture.adapter.frames);
            assertEquals(1, fixture.adapter.maxInFlight.get());
        } finally {
            fixture.dispatcher.shutdown();
        }
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

        private Fixture() {
            when(repository.findProfile(profile.id())).thenReturn(Optional.of(profile));
        }
    }

    private static final class CapturingNativeAdapter implements GatewayNativeChannelProviderAdapter {
        private final String providerType;
        private final List<ChannelOutboundFrame> frames = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstFrameStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstFrame = new CountDownLatch(1);
        private final CountDownLatch twoFramesConsumed = new CountDownLatch(2);
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();
        private boolean fail;
        private boolean blockFirstFrame;
        private String failFrameId;
        private List<OutboundFrameDispatch> preparedDispatches;

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
        public List<OutboundFrameDispatch> prepareOutboundFrames(ChannelGatewayProfile profile, List<ChannelOutboundFrame> frames) {
            if (preparedDispatches != null) {
                return preparedDispatches;
            }
            return GatewayNativeChannelProviderAdapter.super.prepareOutboundFrames(profile, frames);
        }

        @Override
        public void consumeOutboundFrame(ChannelGatewayProfile profile, ChannelOutboundFrame frame) {
            if (fail || frame.frameId().equals(failFrameId)) {
                throw new IllegalStateException("native delivery failed");
            }
            int currentInFlight = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(currentInFlight, Math::max);
            try {
                if (blockFirstFrame && frames.isEmpty()) {
                    firstFrameStarted.countDown();
                    if (!releaseFirstFrame.await(1, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release first native frame");
                    }
                }
                frames.add(frame);
                twoFramesConsumed.countDown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("native delivery interrupted", interrupted);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        private ChannelOutboundFrame awaitFrame(long timeoutMillis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (System.nanoTime() < deadline) {
                if (!frames.isEmpty()) {
                    return frames.getFirst();
                }
                Thread.sleep(10);
            }
            throw new AssertionError("timed out waiting for native frame");
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
            new ChannelProviderOutboundCapability("FRAME_STREAM", false, false, true, true),
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
            Map.of(
                "sessionMessageId", "message-" + finalSequence,
                "messageSequence", finalSequence,
                "messageBlocks", List.of(Map.of("type", "TEXT", "text", "hello"))
            ),
            null
        );
    }

    private static ChannelOutboundFrame draftUpdateFrame(long sourceSeq, String delta) {
        String frameId = "profile-1:exec-1:" + sourceSeq + ":DRAFT_UPDATE";
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            frameId,
            "profile-1",
            "native-provider",
            "assistant-1",
            "conversation-1",
            "session-1",
            "turn-1",
            "exec-1",
            sourceSeq,
            null,
            ChannelOutboundFrameKind.DRAFT_UPDATE,
            Instant.parse("2026-05-05T00:00:00Z"),
            frameId,
            Map.of(
                "replyMessageId", "message-1",
                "blockId", "block-1",
                "blockType", "TEXT",
                "delta", delta
            ),
            null
        );
    }
}
