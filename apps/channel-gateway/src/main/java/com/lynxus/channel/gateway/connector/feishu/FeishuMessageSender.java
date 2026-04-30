package com.lynxus.channel.gateway.connector.feishu;

import java.util.Map;

public interface FeishuMessageSender {
    FeishuSendTextResult sendText(FeishuSendTextCommand command);

    record FeishuSendTextCommand(
        FeishuAppCredential credential,
        String receiveIdType,
        String receiveId,
        String text,
        String uuid
    ) {
    }

    record FeishuSendTextResult(
        String externalMessageId,
        Map<String, Object> metadata
    ) {
        public FeishuSendTextResult {
            metadata = metadata == null || metadata.isEmpty() ? Map.of() : Map.copyOf(metadata);
        }
    }
}
