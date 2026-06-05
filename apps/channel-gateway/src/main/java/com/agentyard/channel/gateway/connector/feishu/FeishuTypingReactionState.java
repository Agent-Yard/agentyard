package com.agentyard.channel.gateway.connector.feishu;

record FeishuTypingReactionState(
    String channelProfileId,
    String externalConversationId,
    String externalMessageId,
    String dedupKey,
    String sessionId,
    String providerReactionId
) {
    FeishuTypingReactionState {
        channelProfileId = requireText(channelProfileId, "channelProfileId");
        externalConversationId = requireText(externalConversationId, "externalConversationId");
        externalMessageId = requireText(externalMessageId, "externalMessageId");
        dedupKey = requireText(dedupKey, "dedupKey");
        sessionId = trimToNull(sessionId);
        providerReactionId = requireText(providerReactionId, "providerReactionId");
    }

    FeishuTypingReactionState withSessionId(String value) {
        return new FeishuTypingReactionState(
            channelProfileId,
            externalConversationId,
            externalMessageId,
            dedupKey,
            value,
            providerReactionId
        );
    }

    private static String requireText(String value, String field) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException("Feishu typing reaction " + field + " is required");
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
