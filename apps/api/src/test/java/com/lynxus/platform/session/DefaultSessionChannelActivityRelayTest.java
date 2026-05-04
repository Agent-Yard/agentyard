package com.lynxus.platform.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBindingStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityResponse;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityResponseStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityType;
import com.lynxus.contracts.session.SessionContracts.AgentTurnTransientFrame;
import com.lynxus.contracts.session.SessionContracts.AgentTurnTransientFrameKind;
import com.lynxus.contracts.session.SessionContracts.AgentTurnTransientPayload;
import com.lynxus.contracts.session.SessionContracts.StreamVisibility;
import com.lynxus.platform.channel.ChannelGatewayClient;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultSessionChannelActivityRelayTest {
    @Test
    void relaysTypingStartOnTurnStarted() {
        ChannelGatewayClient client = mockClient();
        DefaultSessionChannelActivityRelay relay = new DefaultSessionChannelActivityRelay(client);

        relay.relay(frame(
            AgentTurnTransientFrameKind.TURN_STARTED,
            StreamVisibility.OPERATOR,
            1,
            Map.of("messageId", "session-message-reply-1", "triggerType", "USER_MESSAGE")
        ));

        ArgumentCaptor<ChannelOutboundActivityRequest> request = ArgumentCaptor.forClass(ChannelOutboundActivityRequest.class);
        verify(client).sendOutboundActivity(request.capture());
        assertEquals(ChannelOutboundActivityType.TYPING_START, request.getValue().activityType());
        assertEquals("stream-frame:exec-1:1:TYPING_START", request.getValue().idempotencyKey());
    }

    @Test
    void relaysDraftCompleteAndTypingStopOnReplyBlockCompleted() {
        ChannelGatewayClient client = mockClient();
        DefaultSessionChannelActivityRelay relay = new DefaultSessionChannelActivityRelay(client);

        relay.relay(frame(AgentTurnTransientFrameKind.REPLY_BLOCK_COMPLETED, StreamVisibility.CUSTOMER, 2, Map.of(
            "messageId",
            "session-message-reply-1",
            "blockId",
            "block-1",
            "block",
            Map.of("type", "TEXT", "text", "done")
        )));

        ArgumentCaptor<ChannelOutboundActivityRequest> request = ArgumentCaptor.forClass(ChannelOutboundActivityRequest.class);
        verify(client, org.mockito.Mockito.times(2)).sendOutboundActivity(request.capture());
        assertEquals(ChannelOutboundActivityType.DRAFT_COMPLETE, request.getAllValues().get(0).activityType());
        assertEquals("session-message-reply-1", request.getAllValues().get(0).payload().get("messageId"));
        assertEquals(ChannelOutboundActivityType.TYPING_STOP, request.getAllValues().get(1).activityType());
    }

    @Test
    void relaysDraftDiscardAndTypingStopOnError() {
        ChannelGatewayClient client = mockClient();
        DefaultSessionChannelActivityRelay relay = new DefaultSessionChannelActivityRelay(client);

        relay.relay(frame(AgentTurnTransientFrameKind.ERROR, StreamVisibility.OPERATOR, 3, Map.of(
            "code",
            "STREAM_ERROR",
            "messageId",
            "session-message-reply-1",
            "message",
            "stream error",
            "stage",
            "PROVIDER_STREAM",
            "retryable",
            false,
            "details",
            Map.of()
        )));

        ArgumentCaptor<ChannelOutboundActivityRequest> request = ArgumentCaptor.forClass(ChannelOutboundActivityRequest.class);
        verify(client, org.mockito.Mockito.times(2)).sendOutboundActivity(request.capture());
        assertEquals(ChannelOutboundActivityType.DRAFT_DISCARD, request.getAllValues().get(0).activityType());
        assertEquals("session-message-reply-1", request.getAllValues().get(0).payload().get("messageId"));
        assertEquals(ChannelOutboundActivityType.TYPING_STOP, request.getAllValues().get(1).activityType());
    }

    @Test
    void relaysTypingStopOnTurnCompletedWithoutFinalOutcomePayload() {
        ChannelGatewayClient client = mockClient();
        DefaultSessionChannelActivityRelay relay = new DefaultSessionChannelActivityRelay(client);

        relay.relay(frame(
            AgentTurnTransientFrameKind.TURN_STARTED,
            StreamVisibility.OPERATOR,
            7,
            Map.of("messageId", "session-message-reply-1", "triggerType", "USER_MESSAGE")
        ));
        relay.relay(frame(
            AgentTurnTransientFrameKind.TURN_COMPLETED,
            StreamVisibility.OPERATOR,
            8,
            Map.of("messageId", "session-message-reply-1", "status", "FAILED")
        ));

        ArgumentCaptor<ChannelOutboundActivityRequest> request = ArgumentCaptor.forClass(ChannelOutboundActivityRequest.class);
        verify(client, org.mockito.Mockito.times(2)).sendOutboundActivity(request.capture());
        assertEquals(ChannelOutboundActivityType.TYPING_START, request.getAllValues().get(0).activityType());
        assertEquals(ChannelOutboundActivityType.TYPING_STOP, request.getAllValues().get(1).activityType());
        assertEquals(
            Map.of("activityType", "TYPING_STOP", "frameId", "exec-1:8", "messageId", "session-message-reply-1"),
            request.getAllValues().get(1).payload()
        );
    }

    @Test
    void ignoresBindingLookupFailure() {
        ChannelGatewayClient client = mock(ChannelGatewayClient.class);
        when(client.getBindingBySession("session-1")).thenThrow(new IllegalStateException("gateway unavailable"));

        new DefaultSessionChannelActivityRelay(client)
            .relay(frame(
                AgentTurnTransientFrameKind.TURN_STARTED,
                StreamVisibility.OPERATOR,
                9,
                Map.of("messageId", "session-message-reply-1", "triggerType", "USER_MESSAGE")
            ));

        verify(client, never()).sendOutboundActivity(any());
    }

    @Test
    void doesNotRelayModelOrDeveloperFrames() {
        ChannelGatewayClient client = mockClient();
        DefaultSessionChannelActivityRelay relay = new DefaultSessionChannelActivityRelay(client);

        relay.relay(frame(AgentTurnTransientFrameKind.MODEL_STARTED, StreamVisibility.OPERATOR, 4, Map.of("modelRoundId", "round-1")));
        relay.relay(frame(AgentTurnTransientFrameKind.REPLY_BLOCK_DELTA, StreamVisibility.DEVELOPER, 5, Map.of(
            "messageId",
            "session-message-reply-1",
            "blockId",
            "block-1",
            "blockType",
            "TEXT",
            "delta",
            "secret"
        )));

        verify(client, never()).sendOutboundActivity(any());
    }

    @Test
    void draftDeltaUsesActivityEndpointOnly() {
        ChannelGatewayClient client = mockClient();
        DefaultSessionChannelActivityRelay relay = new DefaultSessionChannelActivityRelay(client);

        relay.relay(frame(AgentTurnTransientFrameKind.REPLY_BLOCK_DELTA, StreamVisibility.CUSTOMER, 6, Map.of(
            "messageId",
            "session-message-reply-1",
            "blockId",
            "block-1",
            "blockType",
            "TEXT",
            "delta",
            "hello"
        )));

        ArgumentCaptor<ChannelOutboundActivityRequest> request = ArgumentCaptor.forClass(ChannelOutboundActivityRequest.class);
        verify(client).sendOutboundActivity(request.capture());
        assertEquals("session-message-reply-1", request.getValue().payload().get("messageId"));
        verify(client, never()).deliverOutbound(any());
    }

    private static ChannelGatewayClient mockClient() {
        ChannelGatewayClient client = mock(ChannelGatewayClient.class);
        when(client.getBindingBySession("session-1")).thenReturn(new ChannelConversationBinding(
            "binding-1",
            "channel-profile-1",
            "chat-1",
            "user-1",
            "assistant-1",
            "customer-1",
            "session-1",
            ChannelConversationBindingStatus.ACTIVE,
            Map.of(),
            Instant.parse("2026-05-02T00:00:00Z"),
            Instant.parse("2026-05-02T00:00:00Z")
        ));
        when(client.sendOutboundActivity(any())).thenReturn(
            new ChannelOutboundActivityResponse(ChannelOutboundActivityResponseStatus.ACCEPTED, false, Map.of())
        );
        return client;
    }

    private static AgentTurnTransientFrame frame(
        AgentTurnTransientFrameKind kind,
        StreamVisibility visibility,
        long seq,
        Map<String, Object> payload
    ) {
        return new AgentTurnTransientFrame(
            AgentTurnTransientFrame.PROTOCOL,
            "exec-1:" + seq,
            "stream-1",
            "session-1",
            "turn-1",
            "exec-1",
            "agent-1",
            1,
            seq,
            kind,
            visibility,
            Instant.parse("2026-05-02T00:00:00Z"),
            payload
        );
    }

    private static AgentTurnTransientFrame frame(
        AgentTurnTransientFrameKind kind,
        StreamVisibility visibility,
        long seq,
        AgentTurnTransientPayload payload
    ) {
        return new AgentTurnTransientFrame(
            AgentTurnTransientFrame.PROTOCOL,
            "exec-1:" + seq,
            "stream-1",
            "session-1",
            "turn-1",
            "exec-1",
            "agent-1",
            1,
            seq,
            kind,
            visibility,
            Instant.parse("2026-05-02T00:00:00Z"),
            payload
        );
    }

}
