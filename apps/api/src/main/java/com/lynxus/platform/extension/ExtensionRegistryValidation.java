package com.lynxus.platform.extension;

import java.util.List;
import java.util.Map;

public record ExtensionRegistryValidation(
    String status,
    String service,
    String component,
    String registryType,
    String registrationConfigDigest,
    String summary,
    List<RegistryValidationError> errors
) {
    public record RuntimeRegistryValidation(
        String status,
        String service,
        String component,
        String registryType,
        String registrationConfigDigest,
        String summary,
        List<RegistryValidationError> errors,
        List<String> expectedDescriptorIds,
        List<String> loadedDescriptorIds,
        Map<String, String> descriptorDefinitionDigests,
        List<String> missingDescriptorIds,
        List<String> unexpectedDescriptorIds,
        List<String> duplicateDescriptorIds,
        List<RegistryValidationError> manifestErrors
    ) {}
}
