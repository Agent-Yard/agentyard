package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.channel.gateway.extension.GatewayNativeChannelProviderAdapter;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundResponse;
import com.lynxus.extension.sdk.protocol.LynxusExtensionProtocol;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class FeishuGatewayNativeChannelProviderAdapter implements GatewayNativeChannelProviderAdapter {
    public static final String PROVIDER_TYPE = "feishu";

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

    @Override
    public ChannelOutboundResponse sendOutbound(ChannelOutboundRequest request) {
        throw new UnsupportedOperationException("gateway-native feishu sendOutbound is not implemented because no Feishu outbound API client is configured");
    }
}
