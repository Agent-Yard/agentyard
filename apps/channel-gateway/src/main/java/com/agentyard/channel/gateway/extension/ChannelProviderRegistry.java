package com.agentyard.channel.gateway.extension;

import java.util.Map;

public interface ChannelProviderRegistry {
    ChannelProviderRegistryLoadResult snapshot();

    ChannelProviderDescriptor requireProvider(String providerType);

    Map<String, Object> materializeAndValidateProfileConfig(String providerType, Map<String, Object> config);
}
