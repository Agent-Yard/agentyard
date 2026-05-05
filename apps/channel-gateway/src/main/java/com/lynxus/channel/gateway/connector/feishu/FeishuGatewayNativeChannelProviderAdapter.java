package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.channel.gateway.extension.GatewayNativeChannelProviderAdapter;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class FeishuGatewayNativeChannelProviderAdapter implements GatewayNativeChannelProviderAdapter {
    public static final String PROVIDER_TYPE = "feishu";
    private static final String DEFAULT_RECEIVE_ID_TYPE = "chat_id";

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
            "supportsCredentialRef", false,
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
        if (credentialProvider == null || messageSender == null) {
            throw new IllegalStateException("Feishu gateway-native outbound dependencies are not configured");
        }
        FeishuAppCredential credential = credentialProvider.resolve(profile.accountId(), profile.config());
        messageSender.sendText(new FeishuMessageSender.FeishuSendTextCommand(
            credential,
            receiveIdType(profile.config()),
            frame.externalConversationId(),
            finalText(frame),
            frame.idempotencyKey()
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

    private static String finalText(ChannelOutboundFrame frame) {
        Object rawBlocks = frame.payload().get("messageBlocks");
        if (!(rawBlocks instanceof List<?> blocks) || blocks.isEmpty()) {
            throw new IllegalArgumentException("FINAL_DELIVERY payload.messageBlocks must contain at least one text block for Feishu");
        }
        List<String> parts = new ArrayList<>();
        for (Object rawBlock : blocks) {
            if (!(rawBlock instanceof Map<?, ?> block)) {
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
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("FINAL_DELIVERY payload.messageBlocks has no text content Feishu can send");
        }
        return String.join("\n", parts);
    }
}
