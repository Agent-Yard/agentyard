package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.channel.gateway.channel.ChannelInboundSessionDispatcher;
import com.lynxus.channel.gateway.channel.NormalizedChannelEventHeaders;
import com.lynxus.channel.gateway.channel.NormalizedChannelTurnIngestService;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelConversation;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundTurn;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelMessageRole;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelMessageSender;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelSenderType;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelTraceContext;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelTurnMessage;
import com.lynxus.extension.sdk.protocol.DescriptorType;
import com.lynxus.extension.sdk.registration.ExtensionRegistrationLoader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
final class FeishuInboundEventService {
    private final NormalizedChannelTurnIngestService ingestService;
    private final ChannelInboundSessionDispatcher sessionDispatcher;
    private final FeishuTypingReactionService typingReactionService;

    FeishuInboundEventService(
        NormalizedChannelTurnIngestService ingestService,
        ChannelInboundSessionDispatcher sessionDispatcher,
        FeishuTypingReactionService typingReactionService
    ) {
        this.ingestService = ingestService;
        this.sessionDispatcher = sessionDispatcher;
        this.typingReactionService = typingReactionService;
    }

    void ingestTextMessage(FeishuInboundTextMessage message) {
        String messageId = requireText(message.messageId(), "feishu message.message_id");
        String chatId = requireText(message.chatId(), "feishu message.chat_id");
        String senderId = firstNonBlank(message.senderOpenId(), message.senderUserId(), message.senderUnionId());
        String dedupKey = "feishu:message:" + shortHash(message.channelProfileId()) + ":" + shortHash(messageId);
        String traceId = randomHex(32);
        String spanId = randomHex(16);
        Map<String, Object> normalizedPayload = normalizedPayload(message);
        NormalizedChannelInboundTurn turn = new NormalizedChannelInboundTurn(
            FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE,
            requireText(message.channelProfileId(), "channelProfileId"),
            dedupKey,
            chatId,
            senderId,
            new NormalizedChannelConversation(chatId, message.chatType(), null, conversationMetadata(message)),
            new NormalizedChannelMessageSender(
                NormalizedChannelSenderType.CUSTOMER,
                senderId,
                null,
                senderMetadata(message)
            ),
            List.of(new NormalizedChannelTurnMessage(
                firstNonBlank(message.eventId(), message.requestId(), messageId),
                messageId,
                occurredAt(message.createTime()),
                NormalizedChannelMessageRole.USER,
                null,
                "TEXT",
                requireText(message.text(), "feishu text"),
                List.of(),
                Map.of("messageType", "text")
            )),
            normalizedPayload,
            message.rawPayload(),
            new NormalizedChannelTraceContext("00-" + traceId + "-" + spanId + "-01", null),
            Map.of("source", "feishu-long-connection")
        );
        var ingestResult = ingestService.ingest(turn, new NormalizedChannelEventHeaders(
            ExtensionRegistrationLoader.CORE_CHANNEL_GATEWAY_REGISTRATION_ID,
            DescriptorType.CHANNEL_PROVIDER.wireValue(),
            FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE,
            traceId,
            firstNonBlank(message.requestId(), UUID.randomUUID().toString()),
            dedupKey
        ));
        if (!ingestResult.duplicateDedupKey() && typingReactionService != null) {
            typingReactionService.beginInboundTypingReaction(turn);
        }
        sessionDispatcher.dispatch(turn, ingestResult);
    }

    private static Map<String, Object> normalizedPayload(FeishuInboundTextMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("providerType", FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE);
        putIfPresent(payload, "appId", message.appId());
        putIfPresent(payload, "tenantKey", message.tenantKey());
        putIfPresent(payload, "requestId", message.requestId());
        putIfPresent(payload, "eventId", message.eventId());
        payload.put("eventType", "im.message.receive_v1");
        payload.put("messageType", "text");
        payload.put("externalConversationId", message.chatId());
        payload.put("externalMessageId", message.messageId());
        putIfPresent(payload, "externalUserId", firstNonBlank(message.senderOpenId(), message.senderUserId(), message.senderUnionId()));
        putIfPresent(payload, "chatType", message.chatType());
        putIfPresent(payload, "createTime", message.createTime());
        return Map.copyOf(payload);
    }

    private static Map<String, Object> conversationMetadata(FeishuInboundTextMessage message) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "chatType", message.chatType());
        putIfPresent(metadata, "tenantKey", message.tenantKey());
        return Map.copyOf(metadata);
    }

    private static Map<String, Object> senderMetadata(FeishuInboundTextMessage message) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "openId", message.senderOpenId());
        putIfPresent(metadata, "userId", message.senderUserId());
        putIfPresent(metadata, "unionId", message.senderUnionId());
        return Map.copyOf(metadata);
    }

    private static Instant occurredAt(String value) {
        String text = value == null ? null : value.trim();
        if (text == null || text.isEmpty()) {
            return Instant.now();
        }
        try {
            long epoch = Long.parseLong(text);
            return epoch > 100_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
        } catch (NumberFormatException error) {
            return Instant.now();
        }
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, 32);
        } catch (Exception error) {
            throw new IllegalStateException("failed to hash feishu id", error);
        }
    }

    private static String randomHex(int length) {
        String seed = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        return seed.substring(0, length);
    }
}
