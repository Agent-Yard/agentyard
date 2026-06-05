package com.agentyard.platform.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentyard.contracts.channel.ChannelContracts;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshot;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.agentyard.contracts.session.SessionContracts.AgentTurnTransientFrame;
import com.agentyard.contracts.session.SessionContracts.AgentTurnTransientFrameKind;
import com.agentyard.contracts.session.SessionContracts.StreamVisibility;
import com.agentyard.platform.channel.ChannelBindingSnapshotLookupService;
import com.agentyard.platform.channel.ChannelOutboundFramePublisher;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultSessionChannelActivityRelayTest {
    @Test
    void publishesTransientDraftFramesThroughFramePublisher() {
        ChannelBindingSnapshotLookupService lookupService = mock(ChannelBindingSnapshotLookupService.class);
        ChannelOutboundFramePublisher framePublisher = mock(ChannelOutboundFramePublisher.class);
        when(lookupService.findActiveBySession("session-1")).thenReturn(Optional.of(snapshot()));
        DefaultSessionChannelActivityRelay relay = new DefaultSessionChannelActivityRelay(lookupService, framePublisher);

        relay.relay(frame(AgentTurnTransientFrameKind.REPLY_BLOCK_DELTA, StreamVisibility.CUSTOMER, 6, Map.of(
            "replyMessageId",
            "session-message-reply-1",
            "blockId",
            "block-1",
            "blockType",
            "TEXT",
            "delta",
            "hello"
        )));

        ArgumentCaptor<ChannelOutboundFrame> frame = ArgumentCaptor.forClass(ChannelOutboundFrame.class);
        verify(framePublisher).publishTransient(frame.capture());
        assertEquals(ChannelOutboundFrameKind.DRAFT_UPDATE, frame.getValue().kind());
        assertEquals("channel-profile-1:exec-1:6:DRAFT_UPDATE", frame.getValue().frameId());
        assertEquals(ChannelContracts.channelOutboundFrameIdempotencyKey(frame.getValue().frameId()), frame.getValue().idempotencyKey());
        assertNotEquals(frame.getValue().frameId(), frame.getValue().idempotencyKey());
        assertTrue(frame.getValue().idempotencyKey().length() <= 50);
        assertEquals("channel-profile-1", frame.getValue().channelProfileId());
        assertEquals("provider.acme", frame.getValue().providerType());
        assertEquals("chat-1", frame.getValue().externalConversationId());
        assertEquals("session-message-reply-1", frame.getValue().payload().get("replyMessageId"));
        assertEquals("hello", frame.getValue().payload().get("delta"));
    }

    @Test
    void publishesWhitespaceOnlyDraftDeltaThroughFramePublisher() {
        ChannelBindingSnapshotLookupService lookupService = mock(ChannelBindingSnapshotLookupService.class);
        ChannelOutboundFramePublisher framePublisher = mock(ChannelOutboundFramePublisher.class);
        when(lookupService.findActiveBySession("session-1")).thenReturn(Optional.of(snapshot()));
        DefaultSessionChannelActivityRelay relay = new DefaultSessionChannelActivityRelay(lookupService, framePublisher);

        relay.relay(frame(AgentTurnTransientFrameKind.REPLY_BLOCK_DELTA, StreamVisibility.CUSTOMER, 6, Map.of(
            "replyMessageId",
            "session-message-reply-1",
            "blockId",
            "block-1",
            "blockType",
            "TEXT",
            "delta",
            "\n "
        )));

        ArgumentCaptor<ChannelOutboundFrame> frame = ArgumentCaptor.forClass(ChannelOutboundFrame.class);
        verify(framePublisher).publishTransient(frame.capture());
        assertEquals(ChannelOutboundFrameKind.DRAFT_UPDATE, frame.getValue().kind());
        assertEquals("\n ", frame.getValue().payload().get("delta"));
    }

    @Test
    void publishesReplyBlockCompletedAsDraftCompleteOnly() {
        ChannelBindingSnapshotLookupService lookupService = mock(ChannelBindingSnapshotLookupService.class);
        ChannelOutboundFramePublisher framePublisher = mock(ChannelOutboundFramePublisher.class);
        when(lookupService.findActiveBySession("session-1")).thenReturn(Optional.of(snapshot()));
        DefaultSessionChannelActivityRelay relay = new DefaultSessionChannelActivityRelay(lookupService, framePublisher);

        relay.relay(frame(AgentTurnTransientFrameKind.REPLY_BLOCK_COMPLETED, StreamVisibility.CUSTOMER, 2, Map.of(
            "replyMessageId",
            "session-message-reply-1",
            "blockId",
            "block-1",
            "block",
            Map.of("type", "TEXT", "text", "done")
        )));

        ArgumentCaptor<ChannelOutboundFrame> frame = ArgumentCaptor.forClass(ChannelOutboundFrame.class);
        verify(framePublisher).publishTransient(frame.capture());
        assertEquals(ChannelOutboundFrameKind.DRAFT_COMPLETE, frame.getValue().kind());
        assertEquals("channel-profile-1:exec-1:2:DRAFT_COMPLETE", frame.getValue().frameId());
        assertEquals("session-message-reply-1", frame.getValue().payload().get("replyMessageId"));
        assertEquals("block-1", frame.getValue().payload().get("blockId"));
        assertEquals("TEXT", frame.getValue().payload().get("blockType"));
        assertEquals(Map.of("type", "TEXT", "text", "done"), frame.getValue().payload().get("block"));
    }

    @Test
    void publishesTurnCompletedAsTypingStop() {
        ChannelBindingSnapshotLookupService lookupService = mock(ChannelBindingSnapshotLookupService.class);
        ChannelOutboundFramePublisher framePublisher = mock(ChannelOutboundFramePublisher.class);
        when(lookupService.findActiveBySession("session-1")).thenReturn(Optional.of(snapshot()));
        DefaultSessionChannelActivityRelay relay = new DefaultSessionChannelActivityRelay(lookupService, framePublisher);

        relay.relay(frame(AgentTurnTransientFrameKind.TURN_COMPLETED, StreamVisibility.OPERATOR, 3, Map.of(
            "replyMessageId",
            "session-message-reply-1",
            "status",
            "SUCCEEDED"
        )));

        ArgumentCaptor<ChannelOutboundFrame> frame = ArgumentCaptor.forClass(ChannelOutboundFrame.class);
        verify(framePublisher).publishTransient(frame.capture());
        assertEquals(ChannelOutboundFrameKind.TYPING_STOP, frame.getValue().kind());
        assertEquals(Map.of("replyMessageId", "session-message-reply-1"), frame.getValue().payload());
    }

    @Test
    void publishesErrorAsDraftDiscardThenTypingStop() {
        ChannelBindingSnapshotLookupService lookupService = mock(ChannelBindingSnapshotLookupService.class);
        ChannelOutboundFramePublisher framePublisher = mock(ChannelOutboundFramePublisher.class);
        when(lookupService.findActiveBySession("session-1")).thenReturn(Optional.of(snapshot()));
        DefaultSessionChannelActivityRelay relay = new DefaultSessionChannelActivityRelay(lookupService, framePublisher);

        relay.relay(frame(AgentTurnTransientFrameKind.ERROR, StreamVisibility.OPERATOR, 4, Map.of(
            "code",
            "PROVIDER_STREAM_FAILED",
            "replyMessageId",
            "session-message-reply-1",
            "message",
            "boom",
            "stage",
            "PROVIDER_STREAM",
            "retryable",
            true
        )));

        ArgumentCaptor<ChannelOutboundFrame> frame = ArgumentCaptor.forClass(ChannelOutboundFrame.class);
        verify(framePublisher, org.mockito.Mockito.times(2)).publishTransient(frame.capture());
        assertEquals(ChannelOutboundFrameKind.DRAFT_DISCARD, frame.getAllValues().get(0).kind());
        assertEquals(
            Map.of("replyMessageId", "session-message-reply-1", "reason", "PROVIDER_STREAM_FAILED"),
            frame.getAllValues().get(0).payload()
        );
        assertEquals(ChannelOutboundFrameKind.TYPING_STOP, frame.getAllValues().get(1).kind());
        assertEquals(Map.of("replyMessageId", "session-message-reply-1"), frame.getAllValues().get(1).payload());
    }

    @Test
    void snapshotMissNoOps() {
        ChannelBindingSnapshotLookupService lookupService = mock(ChannelBindingSnapshotLookupService.class);
        ChannelOutboundFramePublisher framePublisher = mock(ChannelOutboundFramePublisher.class);
        when(lookupService.findActiveBySession("session-1")).thenReturn(Optional.empty());

        new DefaultSessionChannelActivityRelay(lookupService, framePublisher)
            .relay(frame(
                AgentTurnTransientFrameKind.TURN_STARTED,
                StreamVisibility.OPERATOR,
                1,
                Map.of("replyMessageId", "session-message-reply-1", "triggerType", "USER_MESSAGE", "inputMessageCount", 1)
            ));

        verify(framePublisher, never()).publishTransient(any());
    }

    @Test
    void ignoresNonCustomerDraftDelta() {
        ChannelBindingSnapshotLookupService lookupService = mock(ChannelBindingSnapshotLookupService.class);
        ChannelOutboundFramePublisher framePublisher = mock(ChannelOutboundFramePublisher.class);
        DefaultSessionChannelActivityRelay relay = new DefaultSessionChannelActivityRelay(lookupService, framePublisher);

        relay.relay(frame(AgentTurnTransientFrameKind.REPLY_BLOCK_DELTA, StreamVisibility.OPERATOR, 6, Map.of(
            "replyMessageId",
            "session-message-reply-1",
            "blockId",
            "block-1",
            "blockType",
            "TEXT",
            "delta",
            "hello"
        )));

        verify(lookupService, never()).findActiveBySession(any());
        verify(framePublisher, never()).publishTransient(any());
    }

    private static ChannelOutboundBindingSnapshot snapshot() {
        Instant now = Instant.parse("2026-05-02T00:00:00Z");
        return new ChannelOutboundBindingSnapshot(
            "binding-1",
            "session-1",
            "channel-profile-1",
            "provider.acme",
            "chat-1",
            "user-1",
            "assistant-1",
            "customer-1",
            "ACTIVE",
            ChannelProfileStatus.ACTIVE,
            1L,
            now,
            now,
            now
        );
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
}
