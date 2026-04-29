package com.lynxus.channel.gateway.extension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ChannelProviderDescriptor(
    String providerType,
    Map<String, Object> descriptor,
    String definitionDigest,
    Map<String, Object> configSchema,
    Map<String, Object> defaultConfig
) {
    public ChannelProviderDescriptor {
        if (providerType == null || providerType.isBlank()) {
            throw new IllegalArgumentException("providerType is required");
        }
        providerType = providerType.trim();
        descriptor = immutableObject(descriptor);
        configSchema = immutableObject(configSchema);
        defaultConfig = immutableObject(defaultConfig);
    }

    private static Map<String, Object> immutableObject(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
