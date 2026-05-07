package com.lynxus.platform.extension;

import java.util.List;
import java.util.Map;

public final class ExtensionDefinitionDtos {
    private ExtensionDefinitionDtos() {}

    public enum CredentialCapabilityMode {
        REMOTE_LIFECYCLE,
        CORE_ENCRYPTED_REFERENCE
    }

    public record CredentialCapability(
        boolean supported,
        CredentialCapabilityMode mode,
        Map<String, Object> credentialSchema,
        List<Object> credentialUiSchema,
        boolean supportsValidate
    ) {}

    public record ChannelProviderDefinition(
        String providerType,
        String title,
        String description,
        String definitionDigest,
        Map<String, Object> accountConfigSchema,
        List<Object> accountConfigUiSchema,
        CredentialCapability credentialCapability,
        Map<String, Object> configSchema,
        List<Object> configUiSchema,
        Map<String, Object> defaultConfig,
        List<ChannelProviderJobDefinition> jobDefinitions
    ) {}

    public record ChannelProviderJobDefinition(
        String jobType,
        String title,
        String description,
        Map<String, Object> jobConfigSchema,
        List<Object> jobConfigUiSchema,
        Map<String, Object> defaultSchedule,
        Boolean defaultEnabled,
        Integer defaultJobTimeoutSeconds
    ) {}

    public record ToolConnectorDefinition(
        String connectorType,
        String title,
        String description,
        String definitionDigest,
        Map<String, Object> accountConfigSchema,
        List<Object> accountConfigUiSchema,
        CredentialCapability credentialCapability,
        Map<String, Object> configSchema,
        List<Object> configUiSchema,
        Map<String, Object> operationMappingSchema,
        List<Object> operationMappingUiSchema
    ) {}
}
