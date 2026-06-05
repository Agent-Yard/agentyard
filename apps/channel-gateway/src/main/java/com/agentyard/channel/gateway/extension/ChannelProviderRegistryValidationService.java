package com.agentyard.channel.gateway.extension;

import org.springframework.stereotype.Service;

@Service
public final class ChannelProviderRegistryValidationService {
    private static final String SERVICE = "channel-gateway";
    private static final String COMPONENT = "EXTENSION_REGISTRY";
    private static final String REGISTRY_TYPE = "CHANNEL_PROVIDER";

    private final ExtensionRegistrationService registrationService;
    private final ChannelProviderRegistry registry;

    public ChannelProviderRegistryValidationService(
        ExtensionRegistrationService registrationService,
        ChannelProviderRegistry registry
    ) {
        this.registrationService = registrationService;
        this.registry = registry;
    }

    public ChannelProviderRegistryValidation validate() {
        ChannelProviderRegistryLoadResult result = registry.snapshot();
        boolean ready = result.ready();
        return new ChannelProviderRegistryValidation(
            ready ? "READY" : "NOT_READY",
            SERVICE,
            COMPONENT,
            REGISTRY_TYPE,
            registrationService.registrationConfigDigest(),
            ready ? "Channel provider registry is ready" : "Channel provider registry is not ready",
            result.errors(),
            result.expectedDescriptorIds().stream().toList(),
            result.loadedCounts().keySet().stream().toList(),
            result.descriptorDefinitionDigests(),
            result.missingDescriptorIds().stream().toList(),
            result.unexpectedDescriptorIds().stream().toList(),
            result.duplicateDescriptorIds(),
            result.manifestErrors()
        );
    }
}
