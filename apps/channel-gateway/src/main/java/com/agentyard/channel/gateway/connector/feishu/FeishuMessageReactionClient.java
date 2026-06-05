package com.agentyard.channel.gateway.connector.feishu;

interface FeishuMessageReactionClient {
    FeishuAddReactionResult addReaction(FeishuAddReactionCommand command);

    void deleteReaction(FeishuDeleteReactionCommand command);

    record FeishuAddReactionCommand(
        FeishuAppCredential credential,
        String messageId,
        String emojiType
    ) {
    }

    record FeishuAddReactionResult(
        String reactionId
    ) {
    }

    record FeishuDeleteReactionCommand(
        FeishuAppCredential credential,
        String messageId,
        String reactionId
    ) {
    }
}
