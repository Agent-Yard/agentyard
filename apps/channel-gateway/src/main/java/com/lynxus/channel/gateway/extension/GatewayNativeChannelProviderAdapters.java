package com.lynxus.channel.gateway.extension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public final class GatewayNativeChannelProviderAdapters {
    private final Map<String, GatewayNativeChannelProviderAdapter> adaptersByProviderType;

    public GatewayNativeChannelProviderAdapters(List<GatewayNativeChannelProviderAdapter> adapters) {
        Map<String, GatewayNativeChannelProviderAdapter> indexed = new TreeMap<>();
        for (GatewayNativeChannelProviderAdapter adapter : adapters == null ? List.<GatewayNativeChannelProviderAdapter>of() : adapters) {
            indexed.putIfAbsent(adapter.providerType(), adapter);
        }
        this.adaptersByProviderType = Map.copyOf(indexed);
    }

    public List<GatewayNativeChannelProviderAdapter> adapters() {
        return List.copyOf(adaptersByProviderType.values());
    }

    public Optional<GatewayNativeChannelProviderAdapter> find(String providerType) {
        return Optional.ofNullable(adaptersByProviderType.get(providerType));
    }
}
