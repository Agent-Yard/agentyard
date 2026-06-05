package com.agentyard.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentyard.channel.gateway.channel.ChannelOutboundExtensionAccessService.AuthorizedExtensionConsumer;
import com.agentyard.channel.gateway.extension.ChannelProviderDescriptor;
import com.agentyard.contracts.channel.ChannelContracts;
import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderOutboundCapability;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ChannelOutboundExtensionStreamServiceTest {
    @Test
    void extensionStreamWaitsForHandoffSignalInsteadOfPollingSleep() throws Exception {
        ChannelOutboundExtensionAccessService accessService = mock(ChannelOutboundExtensionAccessService.class);
        ChannelAdminRepository repository = mock(ChannelAdminRepository.class);
        ChannelOutboundExtensionStreamLeaseService leaseService = mock(ChannelOutboundExtensionStreamLeaseService.class);
        ChannelOutboundDownstreamConsumerRegistry downstreamRegistry = new ChannelOutboundDownstreamConsumerRegistry();
        ChannelOutboundUpstreamRelaySupervisor upstreamRelaySupervisor = mock(ChannelOutboundUpstreamRelaySupervisor.class);
        SignalingHandoff handoff = new SignalingHandoff();
        ChannelOutboundForwardedPendingStore pendingStore = mock(ChannelOutboundForwardedPendingStore.class);
        ChannelOutboundRelayProperties properties = new ChannelOutboundRelayProperties();
        ChannelGatewayProfile profile = profile();
        ChannelProviderDescriptor descriptor = descriptor();
        ChannelOutboundProfileConsumer consumer = consumer();
        ChannelOutboundFrame frame = finalFrame(1);

        when(accessService.requireConsumer(eq(profile.id()), any(ChannelOutboundExtensionHeaders.class)))
            .thenReturn(new AuthorizedExtensionConsumer(profile, descriptor, consumer));
        when(leaseService.acquire(eq(consumer), any(String.class), any(Duration.class))).thenReturn(true);
        when(leaseService.renew(eq(consumer), any(String.class), any(Duration.class))).thenReturn(true);

        ChannelOutboundExtensionStreamService service = new ChannelOutboundExtensionStreamService(
            accessService,
            repository,
            leaseService,
            downstreamRegistry,
            upstreamRelaySupervisor,
            handoff,
            pendingStore,
            properties
        );
        try {
            service.open(profile.id(), headers(), null, null);
            assertTrue(handoff.awaitWaitingDrain());

            assertTrue(handoff.offer(consumer, frame, 1, 0));

            verify(pendingStore, org.mockito.Mockito.timeout(500)).markForwarded(consumer, frame);
        } finally {
            service.shutdown();
        }
    }

    private static final class SignalingHandoff implements ChannelOutboundFrameHandoff {
        private final ArrayDeque<ChannelOutboundFrame> frames = new ArrayDeque<>();
        private final CountDownLatch waitingDrain = new CountDownLatch(1);
        private final List<FrameAvailableListener> listeners = new CopyOnWriteArrayList<>();
        private int pendingFinals;

        @Override
        public boolean offer(
            ChannelOutboundProfileConsumer consumer,
            ChannelOutboundFrame frame,
            int maxPendingFinals,
            int transientCapacity
        ) {
            synchronized (this) {
                frames.addLast(frame);
                if (frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY) {
                    pendingFinals += 1;
                }
                notifyAll();
            }
            listeners.forEach(listener -> listener.framesAvailable(consumer));
            return true;
        }

        @Override
        public int pendingFinals(ChannelOutboundProfileConsumer consumer) {
            synchronized (this) {
                return pendingFinals;
            }
        }

        @Override
        public List<ChannelOutboundFrame> drain(ChannelOutboundProfileConsumer consumer, int limit) {
            throw new AssertionError("extension stream should use drainOrWait instead of polling drain");
        }

        @Override
        public List<ChannelOutboundFrame> drainOrWait(ChannelOutboundProfileConsumer consumer, int limit, Duration timeout) throws InterruptedException {
            waitingDrain.countDown();
            synchronized (this) {
                if (frames.isEmpty()) {
                    wait(timeout.toMillis());
                }
                List<ChannelOutboundFrame> drained = new ArrayList<>();
                while (!frames.isEmpty() && drained.size() < limit) {
                    ChannelOutboundFrame frame = frames.removeFirst();
                    if (frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY) {
                        pendingFinals -= 1;
                    }
                    drained.add(frame);
                }
                return List.copyOf(drained);
            }
        }

        @Override
        public boolean hasFrames(ChannelOutboundProfileConsumer consumer) {
            synchronized (this) {
                return !frames.isEmpty();
            }
        }

        @Override
        public void requeueFirst(ChannelOutboundProfileConsumer consumer, ChannelOutboundFrame frame) {
            synchronized (this) {
                frames.addFirst(frame);
                if (frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY) {
                    pendingFinals += 1;
                }
                notifyAll();
            }
            listeners.forEach(listener -> listener.framesAvailable(consumer));
        }

        @Override
        public void registerListener(FrameAvailableListener listener) {
            listeners.add(listener);
        }

        @Override
        public void unregisterListener(FrameAvailableListener listener) {
            listeners.remove(listener);
        }

        private boolean awaitWaitingDrain() throws InterruptedException {
            return waitingDrain.await(1, TimeUnit.SECONDS);
        }
    }

    private static ChannelOutboundExtensionHeaders headers() {
        return new ChannelOutboundExtensionHeaders("acme-channel-provider", "CHANNEL_PROVIDER", "enterprise.acme.im");
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

    private static ChannelOutboundProfileConsumer consumer() {
        return new ChannelOutboundProfileConsumer(
            "profile-1",
            "enterprise.acme.im",
            ChannelOutboundConsumerKind.REMOTE_EXTENSION,
            "acme-channel-provider",
            "acme-channel-provider"
        );
    }

    private static ChannelOutboundFrame finalFrame(long finalSequence) {
        String frameId = "profile-1:session-1:message-" + finalSequence + ":FINAL_DELIVERY";
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
}
