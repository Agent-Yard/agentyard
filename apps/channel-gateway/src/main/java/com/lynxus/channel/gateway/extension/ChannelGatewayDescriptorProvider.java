package com.lynxus.channel.gateway.extension;

import com.lynxus.extension.sdk.common.LynxusCanonicalJson;
import com.lynxus.extension.sdk.protocol.LynxusExtensionProtocol;
import com.lynxus.extension.sdk.validation.ManifestValidationResult;
import com.lynxus.extension.sdk.validation.ManifestValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class ChannelGatewayDescriptorProvider {
    public static final String FEISHU_PROVIDER_TYPE = "feishu";
    private final Map<String, Object> manifest;
    private final byte[] canonicalManifestBytes;

    public ChannelGatewayDescriptorProvider() {
        this.manifest = serviceManifest();
        ManifestValidationResult validation = ManifestValidator.validate(manifest);
        if (!validation.valid()) {
            throw new IllegalStateException("channel-gateway extension manifest is invalid: " + validation.errors());
        }
        this.canonicalManifestBytes = LynxusCanonicalJson.canonicalValueBytes(manifest);
    }

    public Map<String, Object> manifest() {
        return manifest;
    }

    public byte[] canonicalManifestBytes() {
        return canonicalManifestBytes.clone();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> channelProviderDescriptors() {
        Map<String, Object> descriptors = (Map<String, Object>) manifest.get("descriptors");
        return (List<Map<String, Object>>) descriptors.get("channelProviders");
    }

    private static Map<String, Object> serviceManifest() {
        Map<String, Object> descriptors = new LinkedHashMap<>();
        descriptors.put("channelProviders", List.of(feishuDescriptor()));
        descriptors.put("toolConnectors", List.of());

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("extensionApiVersion", LynxusExtensionProtocol.EXTENSION_API_VERSION);
        manifest.put("coreMinVersion", "0.8.0");
        manifest.put("coreMaxVersion", "0.9.x");
        manifest.put("descriptors", descriptors);
        return Map.copyOf(manifest);
    }

    private static Map<String, Object> feishuDescriptor() {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put(LynxusExtensionProtocol.CHANNEL_PROVIDER_SEND_OUTBOUND_ENDPOINT, "/connectors/feishu/send-outbound");

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("providerType", FEISHU_PROVIDER_TYPE);
        descriptor.put("title", "Feishu");
        descriptor.put("description", "Gateway-native Feishu channel provider.");
        descriptor.put("accountConfigSchema", Map.of());
        descriptor.put("accountConfigUiSchema", List.of());
        descriptor.put("configSchema", Map.of());
        descriptor.put("configUiSchema", List.of());
        descriptor.put("defaultConfig", Map.of());
        descriptor.put("jobDefinitions", List.of());
        descriptor.put("endpoints", endpoints);
        return Map.copyOf(descriptor);
    }
}
