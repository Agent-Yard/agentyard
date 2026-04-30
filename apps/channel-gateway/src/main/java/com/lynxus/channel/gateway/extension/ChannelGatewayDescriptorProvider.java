package com.lynxus.channel.gateway.extension;

import com.lynxus.channel.gateway.connector.feishu.FeishuGatewayNativeChannelProviderAdapter;
import com.lynxus.extension.sdk.common.LynxusCanonicalJson;
import com.lynxus.extension.sdk.protocol.LynxusExtensionProtocol;
import com.lynxus.extension.sdk.validation.ManifestValidationResult;
import com.lynxus.extension.sdk.validation.ManifestValidator;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class ChannelGatewayDescriptorProvider {
    private final Map<String, Object> manifest;
    private final byte[] canonicalManifestBytes;

    public ChannelGatewayDescriptorProvider() {
        this(new GatewayNativeChannelProviderAdapters(List.of(new FeishuGatewayNativeChannelProviderAdapter())));
    }

    @Autowired
    public ChannelGatewayDescriptorProvider(GatewayNativeChannelProviderAdapters gatewayNativeAdapters) {
        this.manifest = serviceManifest(gatewayNativeAdapters.adapters());
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

    private static Map<String, Object> serviceManifest(List<GatewayNativeChannelProviderAdapter> adapters) {
        List<Map<String, Object>> channelProviders = adapters.stream()
            .sorted(Comparator.comparing(GatewayNativeChannelProviderAdapter::providerType))
            .map(GatewayNativeChannelProviderAdapter::descriptor)
            .toList();

        java.util.LinkedHashMap<String, Object> descriptors = new java.util.LinkedHashMap<>();
        descriptors.put("channelProviders", channelProviders);
        descriptors.put("toolConnectors", List.of());

        java.util.LinkedHashMap<String, Object> manifest = new java.util.LinkedHashMap<>();
        manifest.put("extensionApiVersion", LynxusExtensionProtocol.EXTENSION_API_VERSION);
        manifest.put("coreMinVersion", "0.8.0");
        manifest.put("coreMaxVersion", "0.9.x");
        manifest.put("descriptors", descriptors);
        return Map.copyOf(manifest);
    }
}
