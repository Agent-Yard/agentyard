package com.agentyard.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelAttachment;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelConversation;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelEventType;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelInboundEvent;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelMessage;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelSender;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelTraceContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NormalizedChannelEventValidatorTest {
    @Test
    void forbidsMessageOnConversationUpdated() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            NormalizedChannelEventValidator.validateEventTypeMatrix(new NormalizedChannelInboundEvent(
                "feishu",
                "channel-profile-1",
                NormalizedChannelEventType.CONVERSATION_UPDATED,
                "feishu:conversation:chat-1",
                "evt-1",
                "chat-1",
                null,
                null,
                null,
                conversation("chat-1"),
                null,
                message("msg-1", List.of()),
                Map.of(),
                Map.of(),
                traceContext(),
                Map.of()
            ))
        );

        assertEquals("normalizedEvent.message is forbidden for CONVERSATION_UPDATED", error.getMessage());
    }

    @Test
    void forbidsSenderAndMessageForWebhookVerified() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            NormalizedChannelEventValidator.validateEventTypeMatrix(new NormalizedChannelInboundEvent(
                "feishu",
                "channel-profile-1",
                NormalizedChannelEventType.WEBHOOK_VERIFIED,
                "feishu:webhook:verified",
                "evt-1",
                null,
                null,
                "user-1",
                null,
                null,
                sender("user-1"),
                null,
                Map.of(),
                Map.of(),
                traceContext(),
                Map.of()
            ))
        );

        assertEquals("normalizedEvent.sender is forbidden for WEBHOOK_VERIFIED", error.getMessage());
    }

    @Test
    void rejectsNestedExternalIdMismatch() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            NormalizedChannelEventValidator.validateEventTypeMatrix(new NormalizedChannelInboundEvent(
                "feishu",
                "channel-profile-1",
                NormalizedChannelEventType.CONVERSATION_UPDATED,
                "feishu:conversation:chat-1",
                "evt-1",
                "chat-1",
                null,
                null,
                null,
                conversation("chat-other"),
                null,
                null,
                Map.of(),
                Map.of(),
                traceContext(),
                Map.of()
            ))
        );

        assertEquals("normalizedEvent.conversation.externalConversationId must equal externalConversationId", error.getMessage());
    }

    @Test
    void rejectsDedupKeyOutsideProtocolCharsetAndLength() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            NormalizedChannelEventValidator.validateEventTypeMatrix(new NormalizedChannelInboundEvent(
                "feishu",
                "channel-profile-1",
                NormalizedChannelEventType.UNKNOWN,
                "bad key",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                traceContext(),
                Map.of()
            ))
        );

        assertEquals("normalizedEvent.dedupKey must match [A-Za-z0-9._:-]{1,128}", error.getMessage());
    }

    private static NormalizedChannelConversation conversation(String externalConversationId) {
        return new NormalizedChannelConversation(externalConversationId, "GROUP", "Support", Map.of());
    }

    private static NormalizedChannelSender sender(String externalUserId) {
        return new NormalizedChannelSender(externalUserId, "Alice", Map.of());
    }

    private static NormalizedChannelMessage message(String externalMessageId, List<NormalizedChannelAttachment> attachments) {
        return new NormalizedChannelMessage(externalMessageId, "TEXT", "hello", attachments, Map.of());
    }

    private static NormalizedChannelTraceContext traceContext() {
        return new NormalizedChannelTraceContext("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", null);
    }
}
