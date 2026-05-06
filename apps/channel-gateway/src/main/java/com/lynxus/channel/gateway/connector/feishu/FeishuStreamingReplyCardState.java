package com.lynxus.channel.gateway.connector.feishu;

import java.time.Instant;

record FeishuStreamingReplyCardState(
    FeishuStreamingReplyCardKey key,
    String externalConversationId,
    String cardId,
    String externalMessageId,
    String elementId,
    String content,
    int sequence,
    Long lastSourceSeq,
    boolean closed,
    Instant updatedAt
) {
    FeishuStreamingReplyCardState {
        if (key == null) {
            throw new IllegalArgumentException("Feishu streaming reply card key is required");
        }
        externalConversationId = requireText(externalConversationId, "externalConversationId");
        cardId = requireText(cardId, "cardId");
        elementId = requireText(elementId, "elementId");
        content = content == null ? "" : content;
        if (sequence < 0) {
            throw new IllegalArgumentException("Feishu streaming reply card sequence must be non-negative");
        }
        if (lastSourceSeq != null && lastSourceSeq <= 0) {
            throw new IllegalArgumentException("Feishu streaming reply card lastSourceSeq must be positive");
        }
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    FeishuStreamingReplyCardState withContent(String value, int sequence, Long lastSourceSeq, Instant updatedAt) {
        return new FeishuStreamingReplyCardState(
            key,
            externalConversationId,
            cardId,
            externalMessageId,
            elementId,
            value,
            sequence,
            lastSourceSeq,
            closed,
            updatedAt
        );
    }

    FeishuStreamingReplyCardState closed(int sequence, Long lastSourceSeq, Instant updatedAt) {
        return new FeishuStreamingReplyCardState(
            key,
            externalConversationId,
            cardId,
            externalMessageId,
            elementId,
            content,
            sequence,
            lastSourceSeq,
            true,
            updatedAt
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Feishu streaming reply card " + field + " is required");
        }
        return value;
    }
}
