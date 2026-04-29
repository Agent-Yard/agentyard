package com.lynxus.channel.gateway.extension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public final class RuntimeChannelProviderRegistry implements ChannelProviderRegistry {
    private final ChannelProviderRegistryLoadResult snapshot;

    public RuntimeChannelProviderRegistry(ChannelProviderRegistryLoader loader) {
        this(loader.load());
    }

    RuntimeChannelProviderRegistry(ChannelProviderRegistryLoadResult snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public ChannelProviderRegistryLoadResult snapshot() {
        return snapshot;
    }

    @Override
    public ChannelProviderDescriptor requireProvider(String providerType) {
        String normalizedProviderType = requireProviderType(providerType);
        if (!snapshot.ready()) {
            throw new IllegalStateException("channel provider registry is not ready");
        }
        ChannelProviderDescriptor descriptor = snapshot.descriptorsByProviderType().get(normalizedProviderType);
        if (descriptor == null) {
            throw new IllegalArgumentException("unknown channelProfile.providerType: " + normalizedProviderType);
        }
        return descriptor;
    }

    @Override
    public Map<String, Object> materializeAndValidateProfileConfig(String providerType, Map<String, Object> config) {
        ChannelProviderDescriptor descriptor = requireProvider(providerType);
        Map<String, Object> effectiveConfig = config == null ? descriptor.defaultConfig() : config;
        ChannelProviderProfileConfigValidator.validate(descriptor.configSchema(), effectiveConfig);
        return immutableObject(effectiveConfig);
    }

    private static String requireProviderType(String providerType) {
        if (providerType == null || providerType.isBlank()) {
            throw new IllegalArgumentException("channelProfile.providerType is required");
        }
        return providerType.trim();
    }

    private static Map<String, Object> immutableObject(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
