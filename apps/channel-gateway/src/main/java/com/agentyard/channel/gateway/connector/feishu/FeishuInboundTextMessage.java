package com.agentyard.channel.gateway.connector.feishu;

import java.util.Map;

record FeishuInboundTextMessage(
    String channelProfileId,
    String appId,
    String requestId,
    String eventId,
    String tenantKey,
    String messageId,
    String chatId,
    String chatType,
    String senderOpenId,
    String senderUserId,
    String senderUnionId,
    String text,
    String createTime,
    Map<String, Object> rawPayload
) {
    FeishuInboundTextMessage {
        rawPayload = rawPayload == null || rawPayload.isEmpty() ? Map.of() : Map.copyOf(rawPayload);
    }
}
