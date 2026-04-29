package com.lynxus.channel.gateway.extension;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record ChannelProviderRegistryLoadResult(
    List<RegistryValidationError> errors,
    List<RegistryValidationError> manifestErrors,
    Set<String> expectedDescriptorIds,
    Set<String> missingDescriptorIds,
    Set<String> unexpectedDescriptorIds,
    List<String> duplicateDescriptorIds,
    Map<String, Integer> loadedCounts,
    Map<String, String> descriptorDefinitionDigests,
    Map<String, ChannelProviderDescriptor> descriptorsByProviderType
) {
    public boolean ready() {
        return errors.isEmpty();
    }
}
