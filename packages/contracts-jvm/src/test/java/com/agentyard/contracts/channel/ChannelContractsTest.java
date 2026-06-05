package com.agentyard.contracts.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChannelContractsTest {
    @Test
    void channelOutboundFrameIdempotencyKeyIsStableAndBounded() {
        String seed = "channel-profile-d28237bb:session-v2-b50aa165:session-message-4b8e6432-b10a-358c-a77e-197599f37579:FINAL_DELIVERY";

        String key = ChannelContracts.channelOutboundFrameIdempotencyKey(seed);

        assertEquals(key, ChannelContracts.channelOutboundFrameIdempotencyKey(seed));
        assertNotEquals(seed, key);
        assertTrue(key.matches("cof-[0-9a-f]{32}"));
        assertTrue(key.length() <= 50);
    }

    @Test
    void channelOutboundFrameIdempotencyKeyRejectsBlankSeed() {
        assertThrows(IllegalArgumentException.class, () -> ChannelContracts.channelOutboundFrameIdempotencyKey(" "));
    }

    @Test
    void channelOutboundDraftUpdateAllowsWhitespaceOnlyDelta() {
        ChannelOutboundFrame frame = draftUpdateFrame("\n ");

        assertEquals("\n ", frame.payload().get("delta"));
    }

    @Test
    void channelOutboundDraftUpdateRejectsEmptyDelta() {
        assertThrows(IllegalArgumentException.class, () -> draftUpdateFrame(""));
    }

    private static ChannelOutboundFrame draftUpdateFrame(String delta) {
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            "profile-1:exec-1:2:DRAFT_UPDATE",
            "profile-1",
            "provider.acme",
            "assistant-1",
            "chat-1",
            "session-1",
            "turn-1",
            "exec-1",
            2L,
            null,
            ChannelOutboundFrameKind.DRAFT_UPDATE,
            Instant.parse("2026-05-05T00:00:00Z"),
            "profile-1:exec-1:2:DRAFT_UPDATE",
            Map.of(
                "replyMessageId", "session-message-reply-1",
                "blockId", "reply-block-1",
                "blockType", "TEXT",
                "delta", delta
            ),
            null
        );
    }
}
