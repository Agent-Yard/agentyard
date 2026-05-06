package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import java.util.Objects;

record FeishuStreamingReplyCardKey(
    String channelProfileId,
    String sessionId,
    String messageId
) {
    FeishuStreamingReplyCardKey {
        channelProfileId = requireText(channelProfileId, "channelProfileId");
        sessionId = requireText(sessionId, "sessionId");
        messageId = requireText(messageId, "messageId");
    }

    static FeishuStreamingReplyCardKey fromFrame(ChannelOutboundFrame frame) {
        Objects.requireNonNull(frame, "frame");
        Object rawMessageId = frame.payload().get("messageId");
        if (rawMessageId == null) {
            rawMessageId = frame.payload().get("sessionMessageId");
        }
        return new FeishuStreamingReplyCardKey(
            frame.channelProfileId(),
            frame.sessionId(),
            rawMessageId == null ? null : String.valueOf(rawMessageId)
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Feishu streaming reply card " + field + " is required");
        }
        return value;
    }
}
