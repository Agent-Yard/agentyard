package com.lynxus.platform.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.session.SessionContracts.AgentTurnTransientFrame;
import com.lynxus.contracts.session.SessionContracts.AgentTurnTransientFrameKind;
import com.lynxus.contracts.session.SessionContracts.StreamVisibility;
import com.lynxus.platform.channel.ChannelBindingSnapshotLookupService;
import com.lynxus.platform.channel.ChannelOutboundFramePublisher;
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
            "messageId",
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
        assertEquals("channel-profile-1", frame.getValue().channelProfileId());
        assertEquals("provider.acme", frame.getValue().providerType());
        assertEquals("chat-1", frame.getValue().externalConversationId());
        assertEquals("session-message-reply-1", frame.getValue().payload().get("messageId"));
        assertEquals("hello", frame.getValue().payload().get("delta"));
    }

    @Test
    void publishesReplyBlockCompletedAsDraftCompleteThenTypingStop() {
        ChannelBindingSnapshotLookupService lookupService = mock(ChannelBindingSnapshotLookupService.class);
        ChannelOutboundFramePublisher framePublisher = mock(ChannelOutboundFramePublisher.class);
        when(lookupService.findActiveBySession("session-1")).thenReturn(Optional.of(snapshot()));
        DefaultSessionChannelActivityRelay relay = new DefaultSessionChannelActivityRelay(lookupService, framePublisher);

        relay.relay(frame(AgentTurnTransientFrameKind.REPLY_BLOCK_COMPLETED, StreamVisibility.CUSTOMER, 2, Map.of(
            "messageId",
            "session-message-reply-1",
            "blockId",
            "block-1",
            "block",
            Map.of("type", "TEXT", "text", "done")
        )));

        ArgumentCaptor<ChannelOutboundFrame> frame = ArgumentCaptor.forClass(ChannelOutboundFrame.class);
        verify(framePublisher, org.mockito.Mockito.times(2)).publishTransient(frame.capture());
        assertEquals(ChannelOutboundFrameKind.DRAFT_COMPLETE, frame.getAllValues().get(0).kind());
        assertEquals("TEXT", frame.getAllValues().get(0).payload().get("blockType"));
        assertEquals(ChannelOutboundFrameKind.TYPING_STOP, frame.getAllValues().get(1).kind());
        assertEquals(Map.of("messageId", "session-message-reply-1"), frame.getAllValues().get(1).payload());
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
                Map.of("messageId", "session-message-reply-1", "triggerType", "USER_MESSAGE")
            ));

        verify(framePublisher, never()).publishTransient(any());
    }

    @Test
    void ignoresNonCustomerDraftDelta() {
        ChannelBindingSnapshotLookupService lookupService = mock(ChannelBindingSnapshotLookupService.class);
        ChannelOutboundFramePublisher framePublisher = mock(ChannelOutboundFramePublisher.class);
        DefaultSessionChannelActivityRelay relay = new DefaultSessionChannelActivityRelay(lookupService, framePublisher);

        relay.relay(frame(AgentTurnTransientFrameKind.REPLY_BLOCK_DELTA, StreamVisibility.OPERATOR, 6, Map.of(
            "messageId",
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
