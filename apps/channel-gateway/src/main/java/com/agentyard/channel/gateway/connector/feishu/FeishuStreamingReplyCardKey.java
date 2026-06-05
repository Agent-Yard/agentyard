package com.agentyard.channel.gateway.connector.feishu;

import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import java.util.Objects;

record FeishuStreamingReplyCardKey(
    String channelProfileId,
    String sessionId,
    String replyMessageId
) {
    FeishuStreamingReplyCardKey {
        channelProfileId = requireText(channelProfileId, "channelProfileId");
        sessionId = requireText(sessionId, "sessionId");
        replyMessageId = requireText(replyMessageId, "replyMessageId");
    }

    static FeishuStreamingReplyCardKey fromFrame(ChannelOutboundFrame frame) {
        Objects.requireNonNull(frame, "frame");
        Object rawReplyMessageId = frame.payload().get("replyMessageId");
        if (rawReplyMessageId == null) {
            rawReplyMessageId = frame.payload().get("sessionMessageId");
        }
        return new FeishuStreamingReplyCardKey(
            frame.channelProfileId(),
            frame.sessionId(),
            rawReplyMessageId == null ? null : String.valueOf(rawReplyMessageId)
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Feishu streaming reply card " + field + " is required");
        }
        return value;
    }
}
