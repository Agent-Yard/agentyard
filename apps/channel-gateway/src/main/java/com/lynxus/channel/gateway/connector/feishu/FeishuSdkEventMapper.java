package com.lynxus.channel.gateway.connector.feishu;

import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.UserId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

final class FeishuSdkEventMapper {
    private static final Logger log = LoggerFactory.getLogger(FeishuSdkEventMapper.class);
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private FeishuSdkEventMapper() {
    }

    static Optional<FeishuInboundTextMessage> toTextMessage(
        String channelProfileId,
        String appId,
        P2MessageReceiveV1 event,
        ObjectMapper objectMapper
    ) {
        if (event == null || event.getEvent() == null || event.getEvent().getMessage() == null) {
            return Optional.empty();
        }
        EventMessage message = event.getEvent().getMessage();
        String messageType = readString(message.getMessageType());
        if (!"text".equals(messageType)) {
            log.debug("skip non-text feishu inbound message: profileId={}, messageType={}", channelProfileId, messageType);
            return Optional.empty();
        }
        String text = parseText(message.getContent(), objectMapper);
        if (text == null || text.isBlank()) {
            log.debug("skip blank feishu inbound text message: profileId={}, messageId={}", channelProfileId, message.getMessageId());
            return Optional.empty();
        }
        EventSender sender = event.getEvent().getSender();
        UserId senderId = sender == null ? null : sender.getSenderId();
        return Optional.of(new FeishuInboundTextMessage(
            channelProfileId,
            appId,
            event.getRequestId(),
            null,
            firstNonBlank(event.getTenantKey(), sender == null ? null : sender.getTenantKey()),
            message.getMessageId(),
            message.getChatId(),
            message.getChatType(),
            senderId == null ? null : senderId.getOpenId(),
            senderId == null ? null : senderId.getUserId(),
            senderId == null ? null : senderId.getUnionId(),
            text,
            message.getCreateTime(),
            rawPayload(event, appId)
        ));
    }

    private static String parseText(String content, ObjectMapper objectMapper) {
        String raw = readString(content);
        if (raw == null) {
            return null;
        }
        try {
            Map<String, Object> contentObject = objectMapper.readValue(raw, OBJECT_MAP);
            return readString(contentObject.get("text"));
        } catch (Exception error) {
            log.debug("failed to parse feishu text message content", error);
            return null;
        }
    }

    private static Map<String, Object> rawPayload(P2MessageReceiveV1 event, String appId) {
        Map<String, Object> raw = new LinkedHashMap<>();
        putIfPresent(raw, "appId", appId);
        putIfPresent(raw, "requestId", event.getRequestId());
        putIfPresent(raw, "tenantKey", event.getTenantKey());
        if (event.getEvent() != null && event.getEvent().getMessage() != null) {
            EventMessage message = event.getEvent().getMessage();
            Map<String, Object> rawMessage = new LinkedHashMap<>();
            putIfPresent(rawMessage, "messageId", message.getMessageId());
            putIfPresent(rawMessage, "chatId", message.getChatId());
            putIfPresent(rawMessage, "chatType", message.getChatType());
            putIfPresent(rawMessage, "messageType", message.getMessageType());
            putIfPresent(rawMessage, "createTime", message.getCreateTime());
            raw.put("message", Map.copyOf(rawMessage));
        }
        return Map.copyOf(raw);
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            map.put(key, value);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String readString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
