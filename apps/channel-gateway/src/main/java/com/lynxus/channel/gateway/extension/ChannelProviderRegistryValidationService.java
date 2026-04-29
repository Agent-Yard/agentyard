package com.lynxus.channel.gateway.extension;

import com.lynxus.extension.sdk.common.DescriptorDefinitionDigests;
import com.lynxus.extension.sdk.protocol.JsonDocuments;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHttp;
import com.lynxus.extension.sdk.registration.ExtensionRegistration;
import com.lynxus.extension.sdk.registration.ExtensionRegistrationLoader;
import com.lynxus.extension.sdk.validation.ManifestValidationError;
import com.lynxus.extension.sdk.validation.ManifestValidationResult;
import com.lynxus.extension.sdk.validation.ManifestValidator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public final class ChannelProviderRegistryValidationService {
    private static final String SERVICE = "channel-gateway";
    private static final String COMPONENT = "EXTENSION_REGISTRY";
    private static final String REGISTRY_TYPE = "CHANNEL_PROVIDER";
    private static final String DESCRIPTOR_TYPE = "CHANNEL_PROVIDER";
    private static final String ERROR = "ERROR";
    private static final String MANIFEST_SCHEMA_INVALID = "MANIFEST_SCHEMA_INVALID";

    private final ExtensionRegistrationService registrationService;
    private final ChannelGatewayDescriptorProvider descriptorProvider;
    private final ExtensionManifestFetcher manifestFetcher;
    private final String internalAuthToken;

    public ChannelProviderRegistryValidationService(
        ExtensionRegistrationService registrationService,
        ChannelGatewayDescriptorProvider descriptorProvider,
        ExtensionManifestFetcher manifestFetcher,
        @Qualifier("internalAuthToken") String internalAuthToken
    ) {
        this.registrationService = registrationService;
        this.descriptorProvider = descriptorProvider;
        this.manifestFetcher = manifestFetcher;
        this.internalAuthToken = internalAuthToken;
    }

    public ChannelProviderRegistryValidation validate() {
        List<RegistryValidationError> errors = new ArrayList<>();
        List<RegistryValidationError> manifestErrors = new ArrayList<>();
        Map<String, String> descriptorDefinitionDigests = new TreeMap<>();
        Map<String, Integer> loadedCounts = new TreeMap<>();
        Set<String> expectedDescriptorIds = new TreeSet<>();
        Set<String> missingDescriptorIds = new TreeSet<>();
        Set<String> unexpectedDescriptorIds = new TreeSet<>();

        for (ExtensionRegistration registration : channelProviderRegistrations()) {
            Set<String> expectedForRegistration = new LinkedHashSet<>(registration.exposes().channelProviderTypes());
            expectedDescriptorIds.addAll(expectedForRegistration);
            List<Map<String, Object>> descriptors = loadChannelProviderDescriptors(registration, errors, manifestErrors);
            Set<String> loadedForRegistration = new LinkedHashSet<>();

            for (Map<String, Object> descriptor : descriptors) {
                Object rawProviderType = descriptor.get("providerType");
                if (!(rawProviderType instanceof String providerType) || providerType.isBlank()) {
                    continue;
                }
                loadedForRegistration.add(providerType);
                loadedCounts.merge(providerType, 1, Integer::sum);

                if (!expectedForRegistration.contains(providerType)) {
                    unexpectedDescriptorIds.add(providerType);
                    errors.add(error(
                        "UNEXPECTED_DESCRIPTOR",
                        registration.registrationId(),
                        providerType,
                        "Manifest returned a channel provider descriptor not declared in registration config",
                        false,
                        Map.of("phase", "REGISTRY_LOAD")
                    ));
                    continue;
                }

                descriptorDefinitionDigests.putIfAbsent(
                    providerType,
                    DescriptorDefinitionDigests.channelProviderDefinitionDigest(descriptor)
                );
            }

            for (String expected : expectedForRegistration) {
                if (!loadedForRegistration.contains(expected)) {
                    missingDescriptorIds.add(expected);
                    errors.add(error(
                        "MISSING_DESCRIPTOR",
                        registration.registrationId(),
                        expected,
                        "Descriptor declared in registration config was not loaded from manifest",
                        false,
                        Map.of("phase", "REGISTRY_LOAD")
                    ));
                }
            }
        }

        List<String> duplicateDescriptorIds = loadedCounts.entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .map(Map.Entry::getKey)
            .toList();
        for (String duplicateDescriptorId : duplicateDescriptorIds) {
            errors.add(error(
                "DUPLICATE_DESCRIPTOR",
                null,
                duplicateDescriptorId,
                "Channel provider descriptor was loaded from more than one registration",
                false,
                Map.of("phase", "REGISTRY_LOAD")
            ));
        }

        boolean ready = errors.isEmpty();
        return new ChannelProviderRegistryValidation(
            ready ? "READY" : "NOT_READY",
            SERVICE,
            COMPONENT,
            REGISTRY_TYPE,
            registrationService.registrationConfigDigest(),
            ready ? "Channel provider registry is ready" : "Channel provider registry is not ready",
            List.copyOf(errors),
            List.copyOf(expectedDescriptorIds),
            loadedCounts.keySet().stream().toList(),
            Map.copyOf(descriptorDefinitionDigests),
            List.copyOf(missingDescriptorIds),
            List.copyOf(unexpectedDescriptorIds),
            List.copyOf(duplicateDescriptorIds),
            List.copyOf(manifestErrors)
        );
    }

    private List<ExtensionRegistration> channelProviderRegistrations() {
        return registrationService.services().stream()
            .filter(registration -> !registration.exposes().channelProviderTypes().isEmpty())
            .sorted(Comparator.comparing(ExtensionRegistration::registrationId))
            .toList();
    }

    private List<Map<String, Object>> loadChannelProviderDescriptors(
        ExtensionRegistration registration,
        List<RegistryValidationError> errors,
        List<RegistryValidationError> manifestErrors
    ) {
        Object manifest;
        if (ExtensionRegistrationLoader.CORE_CHANNEL_GATEWAY_REGISTRATION_ID.equals(registration.registrationId())) {
            manifest = descriptorProvider.manifest();
        } else {
            try {
                manifest = JsonDocuments.parseObject(manifestFetcher.fetch(
                    LynxusExtensionHttp.manifestUrl(registration.baseUrl()),
                    LynxusExtensionHttp.serviceLevelHeaders("Bearer " + internalAuthToken, registration.registrationId())
                ));
            } catch (IOException exception) {
                RegistryValidationError error = manifestFetchFailedError(registration.registrationId(), exception);
                errors.add(error);
                manifestErrors.add(error);
                return List.of();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                RegistryValidationError error = manifestFetchFailedError(registration.registrationId(), exception);
                errors.add(error);
                manifestErrors.add(error);
                return List.of();
            } catch (RuntimeException exception) {
                RegistryValidationError error = manifestSchemaError(
                    registration.registrationId(),
                    null,
                    new ManifestValidationError("MANIFEST_JSON_INVALID", "", "Manifest response must be a JSON object")
                );
                errors.add(error);
                manifestErrors.add(error);
                return List.of();
            }
        }

        ManifestValidationResult validation = ManifestValidator.validate(manifest);
        if (!validation.valid()) {
            for (ManifestValidationError violation : validation.errors()) {
                RegistryValidationError error = manifestSchemaError(registration.registrationId(), null, violation);
                errors.add(error);
                manifestErrors.add(error);
            }
            return List.of();
        }

        return channelProviders(manifest);
    }

    private static RegistryValidationError manifestFetchFailedError(String registrationId, Exception exception) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("phase", "MANIFEST_FETCH");
        details.put("httpStatus", null);
        details.put("failureReason", exception.getClass().getSimpleName());
        return error(
            "MANIFEST_FETCH_FAILED",
            registrationId,
            null,
            "Failed to fetch extension manifest",
            true,
            details
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> channelProviders(Object manifest) {
        Map<String, Object> manifestMap = (Map<String, Object>) manifest;
        Map<String, Object> descriptors = (Map<String, Object>) manifestMap.get("descriptors");
        return (List<Map<String, Object>>) descriptors.get("channelProviders");
    }

    private static RegistryValidationError manifestSchemaError(
        String registrationId,
        String descriptorId,
        ManifestValidationError violation
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("phase", "MANIFEST_FETCH");
        details.put("schemaPath", violation.path());
        details.put("violation", violation.code());
        return error(
            MANIFEST_SCHEMA_INVALID,
            registrationId,
            descriptorId,
            "Extension manifest failed schema validation",
            false,
            details
        );
    }

    private static RegistryValidationError error(
        String code,
        String registrationId,
        String descriptorId,
        String message,
        boolean retryable,
        Map<String, Object> details
    ) {
        return new RegistryValidationError(
            code,
            ERROR,
            registrationId,
            DESCRIPTOR_TYPE,
            descriptorId,
            message,
            retryable,
            Collections.unmodifiableMap(new LinkedHashMap<>(details))
        );
    }

}
