package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.channel.gateway.channel.ChannelAdminRepository;
import com.lynxus.channel.gateway.extension.GatewayNativeChannelProviderAdapter;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundResponse;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundResponseStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.extension.sdk.protocol.LynxusExtensionProtocol;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class FeishuGatewayNativeChannelProviderAdapter implements GatewayNativeChannelProviderAdapter {
    public static final String PROVIDER_TYPE = "feishu";
    private static final String DEFAULT_RECEIVE_ID_TYPE = "chat_id";

    private final ChannelAdminRepository repository;
    private final FeishuCredentialProvider credentialProvider;
    private final FeishuMessageSender messageSender;

    public FeishuGatewayNativeChannelProviderAdapter() {
        this(null, null, null);
    }

    @Autowired
    public FeishuGatewayNativeChannelProviderAdapter(
        ChannelAdminRepository repository,
        FeishuCredentialProvider credentialProvider,
        FeishuMessageSender messageSender
    ) {
        this.repository = repository;
        this.credentialProvider = credentialProvider;
        this.messageSender = messageSender;
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public Map<String, Object> descriptor() {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put(LynxusExtensionProtocol.CHANNEL_PROVIDER_SEND_OUTBOUND_ENDPOINT, "/connectors/feishu/send-outbound");

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
        descriptor.put("jobDefinitions", List.of());
        descriptor.put("endpoints", endpoints);
        return Map.copyOf(descriptor);
    }

    @Override
    public ChannelOutboundResponse sendOutbound(ChannelOutboundRequest request) {
        ensureRuntimeConfigured();
        if (!PROVIDER_TYPE.equals(request.providerType())) {
            throw new IllegalArgumentException("channel outbound providerType must be feishu");
        }
        ChannelGatewayProfile profile = repository.findProfile(requireText(request.channelProfileId(), "channelProfileId"))
            .orElseThrow(() -> new NoSuchElementException("channel profile not found: " + request.channelProfileId()));
        if (!PROVIDER_TYPE.equals(profile.providerType())) {
            throw new IllegalArgumentException("channel profile providerType must be feishu");
        }
        if (profile.status() != ChannelProfileStatus.ACTIVE) {
            throw new IllegalArgumentException("channel profile is not ACTIVE: " + profile.id());
        }
        String accountId = requireText(profile.accountId(), "channel profile integration account");
        Map<String, Object> messageBlock = request.payload().messageBlock();
        String messageType = requireText(readString(messageBlock.get("type")), "messageBlock.type").toUpperCase(Locale.ROOT);
        if (!"TEXT".equals(messageType)) {
            throw new IllegalArgumentException("feishu gateway-native outbound currently supports TEXT messageBlock only");
        }
        String text = requireText(readString(messageBlock.get("text")), "messageBlock.text");
        String receiveId = requireText(request.payload().externalConversationId(), "payload.externalConversationId");
        String receiveIdType = receiveIdType(request.config());
        FeishuAppCredential credential = credentialProvider.resolve(accountId, profile.config());
        FeishuMessageSender.FeishuSendTextResult result = messageSender.sendText(new FeishuMessageSender.FeishuSendTextCommand(
            credential,
            receiveIdType,
            receiveId,
            text,
            requireText(request.idempotencyKey(), "idempotencyKey")
        ));
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("providerType", PROVIDER_TYPE);
        metadata.put("channelProfileId", profile.id());
        return new ChannelOutboundResponse(
            ChannelOutboundResponseStatus.SENT,
            result.externalMessageId(),
            false,
            metadata
        );
    }

    private void ensureRuntimeConfigured() {
        if (repository == null || credentialProvider == null || messageSender == null) {
            throw new UnsupportedOperationException("gateway-native feishu sendOutbound is not configured");
        }
    }

    private static String receiveIdType(Map<String, Object> config) {
        String configured = readString(config == null ? null : config.get("receiveIdType"));
        String receiveIdType = configured == null ? DEFAULT_RECEIVE_ID_TYPE : configured;
        return switch (receiveIdType) {
            case "chat_id", "open_id", "user_id", "union_id", "email" -> receiveIdType;
            default -> throw new IllegalArgumentException("unsupported feishu receiveIdType: " + receiveIdType);
        };
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String readString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
