package com.agentyard.channel.gateway.connector.feishu;

import java.util.Map;

public interface FeishuMessageSender {
    FeishuCreateCardResult createCard(FeishuCreateCardCommand command);

    FeishuSendInteractiveCardResult sendInteractiveCard(FeishuSendInteractiveCardCommand command);

    void deleteMessage(FeishuDeleteMessageCommand command);

    void updateCardText(FeishuUpdateCardTextCommand command);

    void updateCardSettings(FeishuUpdateCardSettingsCommand command);

    record FeishuCreateCardCommand(
        FeishuAppCredential credential,
        String cardJson
    ) {
    }

    record FeishuCreateCardResult(
        String cardId,
        Map<String, Object> metadata
    ) {
        public FeishuCreateCardResult {
            metadata = metadata == null || metadata.isEmpty() ? Map.of() : Map.copyOf(metadata);
        }
    }

    record FeishuSendInteractiveCardCommand(
        FeishuAppCredential credential,
        String receiveIdType,
        String receiveId,
        String cardId,
        String uuid
    ) {
    }

    record FeishuSendInteractiveCardResult(
        String externalMessageId,
        Map<String, Object> metadata
    ) {
        public FeishuSendInteractiveCardResult {
            metadata = metadata == null || metadata.isEmpty() ? Map.of() : Map.copyOf(metadata);
        }
    }

    record FeishuDeleteMessageCommand(
        FeishuAppCredential credential,
        String externalMessageId
    ) {
    }

    record FeishuUpdateCardTextCommand(
        FeishuAppCredential credential,
        String cardId,
        String elementId,
        String content,
        int sequence,
        String uuid
    ) {
    }

    record FeishuUpdateCardSettingsCommand(
        FeishuAppCredential credential,
        String cardId,
        String settings,
        int sequence,
        String uuid
    ) {
    }
}
