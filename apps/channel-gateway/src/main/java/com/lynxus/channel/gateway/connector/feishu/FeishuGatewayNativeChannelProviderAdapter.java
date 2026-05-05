package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.channel.gateway.extension.GatewayNativeChannelProviderAdapter;
import com.lynxus.contracts.channel.ChannelContracts;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class FeishuGatewayNativeChannelProviderAdapter implements GatewayNativeChannelProviderAdapter {
    private static final Logger log = LoggerFactory.getLogger(FeishuGatewayNativeChannelProviderAdapter.class);
    public static final String PROVIDER_TYPE = "feishu";
    private static final String DEFAULT_RECEIVE_ID_TYPE = "chat_id";
    private static final int FEISHU_UUID_MAX_LENGTH = 50;

    private final FeishuCredentialProvider credentialProvider;
    private final FeishuMessageSender messageSender;

    public FeishuGatewayNativeChannelProviderAdapter() {
        this(null, null);
    }

    @Autowired
    public FeishuGatewayNativeChannelProviderAdapter(
        FeishuCredentialProvider credentialProvider,
        FeishuMessageSender messageSender
    ) {
        this.credentialProvider = credentialProvider;
        this.messageSender = messageSender;
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public Map<String, Object> descriptor() {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("providerType", PROVIDER_TYPE);
        descriptor.put("title", "Feishu");
        descriptor.put("description", "Gateway-native Feishu channel provider with SDK long-connection inbound and text outbound support.");
        descriptor.put("accountConfigSchema", accountConfigSchema());
        descriptor.put("accountConfigUiSchema", accountConfigUiSchema());
        descriptor.put("credentialSchema", credentialSchema());
        descriptor.put("credentialUiSchema", credentialUiSchema());
        descriptor.put("configSchema", configSchema());
        descriptor.put("configUiSchema", configUiSchema());
        descriptor.put("defaultConfig", Map.of("receiveIdType", DEFAULT_RECEIVE_ID_TYPE));
        descriptor.put("outbound", Map.of(
            "mode", "FRAME_STREAM",
            "supportsTyping", false,
            "supportsDraftUpdate", false,
            "supportsFinalDelivery", true,
            "requiresIdempotentFinalDelivery", true
        ));
        descriptor.put("jobDefinitions", List.of());
        descriptor.put("endpoints", Map.of());
        return Map.copyOf(descriptor);
    }

    @Override
    public void consumeOutboundFrame(ChannelGatewayProfile profile, ChannelOutboundFrame frame) {
        if (frame.kind() != ChannelOutboundFrameKind.FINAL_DELIVERY) {
            throw new IllegalArgumentException("Feishu gateway-native provider only supports FINAL_DELIVERY frames");
        }
        Optional<String> text = finalText(profile, frame);
        if (text.isEmpty()) {
            return;
        }
        if (credentialProvider == null || messageSender == null) {
            throw new IllegalStateException("Feishu gateway-native outbound dependencies are not configured");
        }
        FeishuAppCredential credential = credentialProvider.resolve(profile.accountId(), profile.config());
        messageSender.sendText(new FeishuMessageSender.FeishuSendTextCommand(
            credential,
            receiveIdType(profile.config()),
            frame.externalConversationId(),
            text.get(),
            feishuUuid(frame)
        ));
    }

    private static Map<String, Object> accountConfigSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "appId", Map.of("type", "string", "minLength", 1)
            ),
            "required", List.of("appId"),
            "additionalProperties", false
        );
    }

    private static List<Map<String, Object>> accountConfigUiSchema() {
        return List.of(Map.of(
            "key", "/appId",
            "label", "App ID",
            "component", "text",
            "required", true,
            "order", 10
        ));
    }

    private static Map<String, Object> credentialSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "appSecret", Map.of("type", "string", "minLength", 1)
            ),
            "required", List.of("appSecret"),
            "additionalProperties", false
        );
    }

    private static List<Map<String, Object>> credentialUiSchema() {
        return List.of(Map.of(
            "key", "/appSecret",
            "label", "App Secret",
            "component", "password",
            "required", true,
            "secret", true,
            "order", 10
        ));
    }

    private static Map<String, Object> configSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "receiveIdType", Map.of(
                    "type", "string",
                    "enum", List.of("chat_id", "open_id", "user_id", "union_id", "email"),
                    "default", DEFAULT_RECEIVE_ID_TYPE
                ),
                "appId", Map.of("type", "string"),
                "verificationToken", Map.of("type", "string")
            ),
            "additionalProperties", false
        );
    }

    private static List<Map<String, Object>> configUiSchema() {
        return List.of(Map.of(
            "key", "/receiveIdType",
            "label", "Receive ID类型",
            "component", "select",
            "description", "飞书如何解读出站请求中的 receive_id。保留 Chat ID 以用于正常对话回复。"
                + "仅当外部会话 ID 存储了该标识符类型时，再选择用户标识符。",
            "options", List.of(
                Map.of("label", "Chat ID", "value", "chat_id"),
                Map.of("label", "Open ID", "value", "open_id"),
                Map.of("label", "User ID", "value", "user_id"),
                Map.of("label", "Union ID", "value", "union_id"),
                Map.of("label", "Email", "value", "email")
            ),
            "order", 10
        ));
    }

    private static String receiveIdType(Map<String, Object> profileConfig) {
        Object value = profileConfig == null ? null : profileConfig.get("receiveIdType");
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return DEFAULT_RECEIVE_ID_TYPE;
    }

    private static String feishuUuid(ChannelOutboundFrame frame) {
        String seed = frame.idempotencyKey() == null || frame.idempotencyKey().isBlank()
            ? frame.frameId()
            : frame.idempotencyKey();
        if (seed.length() <= FEISHU_UUID_MAX_LENGTH) {
            return seed;
        }
        return ChannelContracts.channelOutboundFrameIdempotencyKey(seed);
    }

    private static Optional<String> finalText(ChannelGatewayProfile profile, ChannelOutboundFrame frame) {
        Object rawBlocks = frame.payload().get("messageBlocks");
        if (!(rawBlocks instanceof List<?> blocks) || blocks.isEmpty()) {
            log.info(
                "skipping Feishu native final delivery with no renderable message blocks: channelProfileId={}, frameId={}, sessionId={}, sessionMessageId={}",
                frame.channelProfileId(),
                frame.frameId(),
                frame.sessionId(),
                frame.payload().get("sessionMessageId")
            );
            return Optional.empty();
        }
        List<String> parts = new ArrayList<>();
        int index = 0;
        for (Object rawBlock : blocks) {
            int blockIndex = index++;
            if (!(rawBlock instanceof Map<?, ?> block)) {
                log.info(
                    "skipping unsupported Feishu native final block: channelProfileId={}, frameId={}, sessionId={}, sessionMessageId={}, blockIndex={}, blockType={}",
                    frame.channelProfileId(),
                    frame.frameId(),
                    frame.sessionId(),
                    frame.payload().get("sessionMessageId"),
                    blockIndex,
                    "UNKNOWN"
                );
                continue;
            }
            Object rawType = block.get("type");
            String type = rawType == null ? "TEXT" : String.valueOf(rawType);
            if ("TEXT".equals(type)) {
                Object text = block.get("text");
                if (text instanceof String value && !value.isBlank()) {
                    parts.add(value);
                }
                continue;
            }
            if ("RICH_TEXT".equals(type)) {
                Object content = block.get("content");
                if (content instanceof String value && !value.isBlank()) {
                    parts.add(value);
                }
                continue;
            }
            log.info(
                "skipping unsupported Feishu native final block: channelProfileId={}, providerType={}, frameId={}, sessionId={}, sessionMessageId={}, blockIndex={}, blockType={}",
                profile.id(),
                profile.providerType(),
                frame.frameId(),
                frame.sessionId(),
                frame.payload().get("sessionMessageId"),
                blockIndex,
                type
            );
        }
        if (parts.isEmpty()) {
            log.info(
                "skipping Feishu native final delivery with no text content: channelProfileId={}, providerType={}, frameId={}, sessionId={}, sessionMessageId={}",
                profile.id(),
                profile.providerType(),
                frame.frameId(),
                frame.sessionId(),
                frame.payload().get("sessionMessageId")
            );
            return Optional.empty();
        }
        return Optional.of(String.join("\n", parts));
    }
}
