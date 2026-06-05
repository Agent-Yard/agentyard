package com.agentyard.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentyard.contracts.channel.ChannelContracts;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameAck;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import com.agentyard.shared.redis.RedisKeyspace;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.ObjectMapper;

class ChannelOutboundExtensionRedisStateTest {
    @Test
    void forwardedPendingMarkerAndListUseBoundedTtl() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        ChannelOutboundRelayProperties properties = new ChannelOutboundRelayProperties();
        properties.setExtensionForwardedPendingTtl(Duration.ofMinutes(20));
        ChannelOutboundForwardedPendingStore store = new ChannelOutboundForwardedPendingStore(
            redisTemplate,
            new RedisKeyspace("test"),
            new ObjectMapper(),
            properties
        );

        store.markForwarded(consumer(), finalFrame(101));

        String pendingKey = "test:channel-outbound:extension-pending-finals:profile-1:provider-1:REMOTE_EXTENSION:registration-1";
        String markerKey = "test:channel-outbound:extension-forwarded-final:profile-1:provider-1:REMOTE_EXTENSION:registration-1:101";
        verify(valueOperations).setIfAbsent(eq(markerKey), anyString(), eq(Duration.ofMinutes(15)));
        verify(listOperations).rightPush(eq(pendingKey), eq(ChannelOutboundForwardedPendingStore.pendingEntry(101, frameId(101))));
        verify(redisTemplate).expire(pendingKey, Duration.ofMinutes(15));
        verify(redisTemplate).expire(markerKey, Duration.ofMinutes(15));
    }

    @Test
    void ackRejectsNonHeadPendingFinal() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        ObjectMapper objectMapper = new ObjectMapper();
        ChannelOutboundRelayProperties properties = new ChannelOutboundRelayProperties();
        ChannelOutboundForwardedPendingStore store = new ChannelOutboundForwardedPendingStore(
            redisTemplate,
            new RedisKeyspace("test"),
            objectMapper,
            properties
        );
        String pendingEntry = ChannelOutboundForwardedPendingStore.pendingEntry(102, frameId(102));
        when(valueOperations.get(store.markerKey(consumer(), 102))).thenReturn(objectMapper.writeValueAsString(
            new ChannelOutboundForwardedPendingStore.ForwardedFinalFrame(
                frameId(102),
                102,
                "session-1",
                "message-102",
                pendingEntry
            )
        ));
        DefaultRedisScript<Long> anyScript = any();
        when(redisTemplate.execute(anyScript, anyStringList(), eq(pendingEntry))).thenReturn(4L);

        assertEquals(
            ChannelOutboundForwardedPendingStore.AckPendingResult.NON_HEAD,
            store.ackForwarded(consumer(), ack(102))
        );
    }

    @Test
    void duplicateExtensionStreamLeaseIsRejected() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        ChannelOutboundExtensionStreamLeaseService leaseService = new ChannelOutboundExtensionStreamLeaseService(
            redisTemplate,
            new RedisKeyspace("test")
        );

        assertFalse(leaseService.acquire(consumer(), "lease-2", Duration.ofSeconds(30)));
    }

    private static ChannelOutboundFrameAck ack(long finalSequence) {
        return new ChannelOutboundFrameAck(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_ACK_PROTOCOL,
            "profile-1",
            "provider-1",
            frameId(finalSequence),
            finalSequence,
            "session-1",
            "message-" + finalSequence,
            Map.of()
        );
    }

    private static ChannelOutboundFrame finalFrame(long finalSequence) {
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            frameId(finalSequence),
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
            Instant.parse("2026-05-05T00:00:00Z"),
            frameId(finalSequence),
            Map.of(
                "sessionMessageId", "message-" + finalSequence,
                "messageSequence", finalSequence,
                "messageBlocks", List.of(Map.of("type", "TEXT", "text", "hello"))
            ),
            null
        );
    }

    private static String frameId(long finalSequence) {
        return "profile-1:session-1:message-" + finalSequence + ":FINAL_DELIVERY";
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

    private static List<String> anyStringList() {
        return any();
    }
}
