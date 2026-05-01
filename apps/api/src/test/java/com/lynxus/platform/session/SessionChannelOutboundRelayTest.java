package com.lynxus.platform.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBindingStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDeliveryRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDeliveryStatus;
import com.lynxus.contracts.session.SessionContracts.SessionMessage;
import com.lynxus.contracts.session.SessionContracts.SessionMessageRole;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSender;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSenderType;
import com.lynxus.contracts.session.SessionContracts.SessionMessageStatus;
import com.lynxus.platform.channel.ChannelGatewayClient;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class SessionChannelOutboundRelayTest {
    @Test
    void relaysAssistantMessagesForBoundChannelSession() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        ChannelGatewayClient channelGatewayClient = mock(ChannelGatewayClient.class);
        SessionChannelOutboundRelay relay = new SessionChannelOutboundRelay(
            repository,
            channelGatewayClient,
            mock(RedisPubSubBus.class),
            new RedisKeyspace(),
            new RedisJsonCodec(new ObjectMapper())
        );
        when(channelGatewayClient.getBindingBySession("session-1")).thenReturn(binding());
        when(repository.findSession("session-1")).thenReturn(Optional.of(session()));
        when(repository.listMessages("session-1")).thenReturn(List.of(
            message("user-message-1", SessionMessageRole.USER, Map.of("type", "TEXT", "text", "hello")),
            message("assistant-message-1", SessionMessageRole.ASSISTANT, Map.of("type", "TEXT", "text", "hi"))
        ));
        when(channelGatewayClient.deliverOutbound(any())).thenReturn(delivery());

        relay.relaySession("session-1");

        ArgumentCaptor<ChannelOutboundDeliveryRequest> request = ArgumentCaptor.forClass(ChannelOutboundDeliveryRequest.class);
        verify(channelGatewayClient).deliverOutbound(request.capture());
        assertEquals("channel-profile-1", request.getValue().channelProfileId());
        assertEquals("assistant-1", request.getValue().assistantId());
        assertEquals("chat-1", request.getValue().externalConversationId());
        assertEquals("session-1", request.getValue().sessionId());
        assertEquals("assistant-message-1", request.getValue().sessionMessageId());
        assertEquals("hi", request.getValue().messageBlock().get("text"));
    }

    @Test
    void relaysSystemFallbackMessagesForBoundChannelSession() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        ChannelGatewayClient channelGatewayClient = mock(ChannelGatewayClient.class);
        SessionChannelOutboundRelay relay = new SessionChannelOutboundRelay(
            repository,
            channelGatewayClient,
            mock(RedisPubSubBus.class),
            new RedisKeyspace(),
            new RedisJsonCodec(new ObjectMapper())
        );
        when(channelGatewayClient.getBindingBySession("session-1")).thenReturn(binding());
        when(repository.findSession("session-1")).thenReturn(Optional.of(session()));
        when(repository.listMessages("session-1")).thenReturn(List.of(
            message("system-message-1", SessionMessageRole.SYSTEM, Map.of("type", "TEXT", "text", "当前处理遇到问题，请稍后再试"))
        ));
        when(channelGatewayClient.deliverOutbound(any())).thenReturn(delivery());

        relay.relaySession("session-1");

        ArgumentCaptor<ChannelOutboundDeliveryRequest> request = ArgumentCaptor.forClass(ChannelOutboundDeliveryRequest.class);
        verify(channelGatewayClient).deliverOutbound(request.capture());
        assertEquals("system-message-1", request.getValue().sessionMessageId());
        assertEquals("当前处理遇到问题，请稍后再试", request.getValue().messageBlock().get("text"));
    }

    @Test
    void skipsSessionsWithoutChannelBinding() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        ChannelGatewayClient channelGatewayClient = mock(ChannelGatewayClient.class);
        SessionChannelOutboundRelay relay = new SessionChannelOutboundRelay(
            repository,
            channelGatewayClient,
            mock(RedisPubSubBus.class),
            new RedisKeyspace(),
            new RedisJsonCodec(new ObjectMapper())
        );
        when(channelGatewayClient.getBindingBySession("session-1")).thenThrow(new java.util.NoSuchElementException("missing"));

        relay.relaySession("session-1");

        verify(repository, never()).listMessages(any());
        verify(channelGatewayClient, never()).deliverOutbound(any());
    }

    private static ChannelConversationBinding binding() {
        Instant now = Instant.parse("2026-04-30T00:00:00Z");
        return new ChannelConversationBinding(
            "channel-binding-1",
            "channel-profile-1",
            "chat-1",
            "user-1",
            "assistant-1",
            "customer-1",
            "session-1",
            ChannelConversationBindingStatus.ACTIVE,
            Map.of(),
            now,
            now
        );
    }

    private static SessionRuntimeDtos.SessionRuntimeSessionDto session() {
        Instant now = Instant.parse("2026-04-30T00:00:00Z");
        return new SessionRuntimeDtos.SessionRuntimeSessionDto(
            "session-1",
            "scenario-1",
            "hello",
            "customer-1",
            "assistant-1",
            "Assistant",
            "v1",
            "IDLE",
            "agent-1",
            "agent-1",
            null,
            false,
            false,
            false,
            false,
            Map.of(),
            now.plus(Duration.ofMinutes(30)),
            now,
            now,
            null,
            2L,
            0L
        );
    }

    private static SessionMessage message(String messageId, SessionMessageRole role, Map<String, Object> block) {
        Instant now = Instant.parse("2026-04-30T00:00:00Z");
        return new SessionMessage(
            messageId,
            "session-1",
            1L,
            role,
            new SessionMessageSender(SessionMessageSenderType.CUSTOMER, "sender-1", "sender-1"),
            SessionMessageStatus.SENT,
            List.of(block),
            Map.of(),
            null,
            "agent-1",
            null,
            now,
            now
        );
    }

    private static ChannelOutboundDelivery delivery() {
        Instant now = Instant.parse("2026-04-30T00:00:00Z");
        return new ChannelOutboundDelivery(
            "channel-outbound-delivery-1",
            "channel-profile-1",
            "feishu",
            "session-1",
            "assistant-message-1",
            "chat-1",
            "idempotency-1",
            Map.of("messageBlock", Map.of("type", "TEXT", "text", "hi")),
            ChannelOutboundDeliveryStatus.SENT,
            1,
            null,
            now,
            now
        );
    }
}
