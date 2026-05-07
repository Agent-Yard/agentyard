package com.example.lynxus.extensiontemplate.extension;

import com.lynxus.extension.sdk.validation.ManifestValidationError;
import com.lynxus.extension.sdk.validation.ManifestValidationResult;
import com.lynxus.extension.sdk.validation.ManifestValidator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public final class ExtensionManifestFactory {
    private final ExtensionDescriptorRegistry descriptorRegistry;

    public ExtensionManifestFactory(ExtensionDescriptorRegistry descriptorRegistry) {
        this.descriptorRegistry = descriptorRegistry;
    }

    public Map<String, Object> manifest() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("extensionApiVersion", 1);
        manifest.put("coreMinVersion", "0.8.0");
        manifest.put("coreMaxVersion", "0.9.x");
        manifest.put("credentialLifecycleEndpointProfiles", credentialLifecycleEndpointProfiles());
        manifest.put("descriptors", descriptors());

        ManifestValidationResult validation = ManifestValidator.validate(manifest);
        if (!validation.valid()) {
            throw new IllegalStateException("extension manifest is invalid: " + formatErrors(validation));
        }
        return Map.copyOf(manifest);
    }

    private Map<String, Object> credentialLifecycleEndpointProfiles() {
        return Map.of(
            ExtensionEndpointPaths.DEFAULT_CREDENTIAL_LIFECYCLE_PROFILE,
            ExtensionEndpointPaths.defaultCredentialLifecycleEndpointProfile()
        );
    }

    private Map<String, Object> descriptors() {
        Map<String, Object> descriptors = new LinkedHashMap<>();
        descriptors.put(
            "channelProviders",
            descriptorRegistry.channelProviders()
                .stream()
                .map(ExtensionDescriptorRegistry.ChannelProviderDescriptor::toManifestDescriptor)
                .toList()
        );
        descriptors.put(
            "toolConnectors",
            descriptorRegistry.toolConnectors()
                .stream()
                .map(ExtensionDescriptorRegistry.ToolConnectorDescriptor::toManifestDescriptor)
                .toList()
        );
        return Map.copyOf(descriptors);
    }

    private static String formatErrors(ManifestValidationResult validation) {
        return validation.errors()
            .stream()
            .map(ExtensionManifestFactory::formatError)
            .collect(Collectors.joining("; "));
    }

    private static String formatError(ManifestValidationError error) {
        return error.code() + " at " + error.path() + ": " + error.message();
    }
}
