package com.agentyard.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentyard.channel.gateway.extension.ChannelProviderDescriptor;
import com.agentyard.channel.gateway.extension.ChannelProviderRegistry;
import com.agentyard.contracts.channel.ChannelContracts;
import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderOutboundCapability;
import com.agentyard.shared.redis.RedisKeyspace;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ChannelOutboundRelayComponentsTest {
    @Test
    void capabilityFilterUsesOutboundDescriptorCapability() {
        ChannelOutboundCapabilityFilter filter = new ChannelOutboundCapabilityFilter();
        ChannelProviderDescriptor descriptor = descriptor("provider-1", false, "registration-1", true, false, true);

        assertTrue(filter.allows(descriptor, typingFrame()));
        assertFalse(filter.allows(descriptor, draftFrame()));
        assertTrue(filter.allows(descriptor, finalFrame(1)));
    }

    @Test
    void resolverStartsExactlyOneConsumerKindForActiveProfile() {
        ChannelOutboundProfileConsumerResolver remoteResolver = new ChannelOutboundProfileConsumerResolver(
            new StaticRegistry(descriptor("remote-provider", false, "registration-1", false, false, true))
        );
        ChannelOutboundProfileConsumerResolver nativeResolver = new ChannelOutboundProfileConsumerResolver(
            new StaticRegistry(descriptor("native-provider", true, null, false, false, true))
        );

        ChannelOutboundProfileConsumer remote = remoteResolver.resolve(profile("profile-1", "remote-provider")).orElseThrow().consumer();
        ChannelOutboundProfileConsumer nativeConsumer = nativeResolver.resolve(profile("profile-2", "native-provider")).orElseThrow().consumer();

        assertEquals(ChannelOutboundConsumerKind.REMOTE_EXTENSION, remote.consumerKind());
        assertEquals("registration-1", remote.consumerId());
        assertEquals("registration-1", remote.registrationId());
        assertEquals(ChannelOutboundConsumerKind.GATEWAY_NATIVE, nativeConsumer.consumerKind());
        assertEquals("native:native-provider", nativeConsumer.consumerId());
        assertEquals(null, nativeConsumer.registrationId());
    }

    @Test
    void highWaterCreditPreventsUnboundedFinalConsumption() {
        ChannelOutboundReplayCreditCalculator calculator = new ChannelOutboundReplayCreditCalculator();

        assertEquals(100, calculator.replayCredit(100, 0));
        assertEquals(1, calculator.replayCredit(100, 99));
        assertEquals(0, calculator.replayCredit(100, 100));
        assertFalse(calculator.shouldResume(21, 20));
        assertTrue(calculator.shouldResume(20, 20));
    }

    @Test
    void handoffRejectsFinalAtHighWaterButAllowsDraining() {
        InMemoryChannelOutboundFrameHandoff handoff = new InMemoryChannelOutboundFrameHandoff();
        ChannelOutboundProfileConsumer consumer = consumer();

        assertTrue(handoff.offer(consumer, finalFrame(1), 1, 0));
        assertFalse(handoff.offer(consumer, finalFrame(2), 1, 0));
        assertEquals(1, handoff.pendingFinals(consumer));
        assertEquals(List.of(finalFrame(1)), handoff.drain(consumer, 1));
        assertEquals(0, handoff.pendingFinals(consumer));
        assertTrue(handoff.offer(consumer, finalFrame(2), 1, 0));
    }

    @Test
    void handoffDrainOrWaitWakesWhenFrameIsOffered() throws Exception {
        InMemoryChannelOutboundFrameHandoff handoff = new InMemoryChannelOutboundFrameHandoff();
        ChannelOutboundProfileConsumer consumer = consumer();
        ChannelOutboundFrame frame = finalFrame(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<ChannelOutboundFrame>> waitingDrain = executor.submit(() -> {
                waiterStarted.countDown();
                return handoff.drainOrWait(consumer, 1, Duration.ofSeconds(5));
            });

            assertTrue(waiterStarted.await(1, TimeUnit.SECONDS));
            assertFalse(waitingDrain.isDone());
            assertTrue(handoff.offer(consumer, frame, 1, 0));

            assertEquals(List.of(frame), waitingDrain.get(500, TimeUnit.MILLISECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void handoffDrainOrWaitWakesWhenFrameIsRequeuedFirst() throws Exception {
        InMemoryChannelOutboundFrameHandoff handoff = new InMemoryChannelOutboundFrameHandoff();
        ChannelOutboundProfileConsumer consumer = consumer();
        ChannelOutboundFrame frame = finalFrame(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<ChannelOutboundFrame>> waitingDrain = executor.submit(() -> {
                waiterStarted.countDown();
                return handoff.drainOrWait(consumer, 1, Duration.ofSeconds(5));
            });

            assertTrue(waiterStarted.await(1, TimeUnit.SECONDS));
            assertFalse(waitingDrain.isDone());
            handoff.requeueFirst(consumer, frame);

            assertEquals(List.of(frame), waitingDrain.get(500, TimeUnit.MILLISECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void handoffKeepsConsumerStateStableAfterDrainToAvoidDetachedOffers() {
        InMemoryChannelOutboundFrameHandoff handoff = new InMemoryChannelOutboundFrameHandoff();
        ChannelOutboundProfileConsumer consumer = consumer();
        ChannelOutboundFrame first = finalFrame(1);
        ChannelOutboundFrame second = finalFrame(2);

        assertTrue(handoff.offer(consumer, first, 1, 0));
        assertEquals(List.of(first), handoff.drain(consumer, 1));

        assertTrue(handoff.offer(consumer, second, 1, 0));
        assertEquals(List.of(second), handoff.drain(consumer, 1));
    }

    @Test
    void ownerLockUsesFullProfileConsumerIdentity() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(valueOperations.get("test:lock:channel-outbound-api-stream-owner:profile-1:provider-1:REMOTE_EXTENSION:registration-1"))
            .thenReturn("owner-1");
        DefaultRedisScript<Long> anyScript = any();
        when(redisTemplate.execute(anyScript, anyStringList(), anyString(), anyString())).thenReturn(1L);
        ChannelOutboundStreamOwnerLockService lockService = new ChannelOutboundStreamOwnerLockService(
            redisTemplate,
            new RedisKeyspace("test")
        );

        ChannelOutboundProfileConsumer consumer = consumer();
        assertTrue(lockService.acquire(consumer, "owner-1", Duration.ofSeconds(30)));
        assertTrue(lockService.owns(consumer, "owner-1"));
        assertFalse(lockService.owns(consumer, "owner-2"));
        assertTrue(lockService.renew(consumer, "owner-1", Duration.ofSeconds(30)));
    }

    private static ChannelGatewayProfile profile(String profileId, String providerType) {
        Instant now = Instant.parse("2026-05-04T10:00:00Z");
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

    private static ChannelProviderDescriptor descriptor(
        String providerType,
        boolean gatewayNative,
        String registrationId,
        boolean supportsTyping,
        boolean supportsDraft,
        boolean supportsFinal
    ) {
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
            new ChannelProviderOutboundCapability(
                "FRAME_STREAM",
                supportsTyping,
                supportsDraft,
                supportsFinal,
                true
            ),
            Map.of()
        );
    }

    private static ChannelOutboundProfileConsumer consumer() {
        return new ChannelOutboundProfileConsumer(
            "profile-1",
            "provider-1",
            ChannelOutboundConsumerKind.REMOTE_EXTENSION,
            "registration-1",
            "registration-1"
        );
    }

    private static ChannelOutboundFrame typingFrame() {
        return transientFrame(ChannelOutboundFrameKind.TYPING_START, Map.of("replyMessageId", "message-1"));
    }

    private static ChannelOutboundFrame draftFrame() {
        return transientFrame(ChannelOutboundFrameKind.DRAFT_UPDATE, Map.of(
            "replyMessageId", "message-1",
            "blockId", "block-1",
            "blockType", "TEXT",
            "delta", "hello"
        ));
    }

    private static ChannelOutboundFrame transientFrame(ChannelOutboundFrameKind kind, Map<String, Object> payload) {
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            "profile-1:turn-execution-1:1:" + kind,
            "profile-1",
            "provider-1",
            "assistant-1",
            "conversation-1",
            "session-1",
            "turn-1",
            "turn-execution-1",
            1L,
            null,
            kind,
            Instant.parse("2026-05-04T10:00:00Z"),
            "profile-1:turn-execution-1:1:" + kind,
            payload,
            null
        );
    }

    private static ChannelOutboundFrame finalFrame(long finalSequence) {
        String frameId = "profile-1:session-1:message-" + finalSequence + ":FINAL_DELIVERY";
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            frameId,
            "profile-1",
            "provider-1",
            "assistant-1",
            "conversation-1",
            "session-1",
            null,
            null,
            null,
            finalSequence,
            ChannelOutboundFrameKind.FINAL_DELIVERY,
            Instant.parse("2026-05-04T10:00:00Z"),
            frameId,
            Map.of(
                "sessionMessageId", "message-" + finalSequence,
                "messageSequence", finalSequence,
                "messageBlocks", List.of(Map.of("type", "TEXT", "text", "hello"))
            ),
            null
        );
    }

    private static List<String> anyStringList() {
        return any();
    }

    private record StaticRegistry(ChannelProviderDescriptor descriptor) implements ChannelProviderRegistry {
        @Override
        public com.agentyard.channel.gateway.extension.ChannelProviderRegistryLoadResult snapshot() {
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
