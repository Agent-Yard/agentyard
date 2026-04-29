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
import org.springframework.stereotype.Component;

@Component
public final class ChannelProviderRegistryLoader {
    private static final String DESCRIPTOR_TYPE = "CHANNEL_PROVIDER";
    private static final String ERROR = "ERROR";
    private static final String MANIFEST_SCHEMA_INVALID = "MANIFEST_SCHEMA_INVALID";

    private final ExtensionRegistrationService registrationService;
    private final ChannelGatewayDescriptorProvider descriptorProvider;
    private final ExtensionManifestFetcher manifestFetcher;
    private final String internalAuthToken;

    public ChannelProviderRegistryLoader(
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

    public ChannelProviderRegistryLoadResult load() {
        List<RegistryValidationError> errors = new ArrayList<>();
        List<RegistryValidationError> manifestErrors = new ArrayList<>();
        Map<String, String> descriptorDefinitionDigests = new TreeMap<>();
        Map<String, ChannelProviderDescriptor> descriptorsByProviderType = new TreeMap<>();
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

                Map<String, Object> configSchema = objectValue(
                    descriptor,
                    "configSchema",
                    registration.registrationId(),
                    providerType,
                    errors,
                    manifestErrors
                );
                Map<String, Object> defaultConfig = objectValue(
                    descriptor,
                    "defaultConfig",
                    registration.registrationId(),
                    providerType,
                    errors,
                    manifestErrors
                );
                if (configSchema == null || defaultConfig == null) {
                    continue;
                }
                if (!ChannelProviderProfileConfigValidator.valid(configSchema, defaultConfig)) {
                    RegistryValidationError error = manifestSchemaError(
                        registration.registrationId(),
                        providerType,
                        new ManifestValidationError(
                            "DEFAULT_CONFIG_INVALID",
                            "/descriptors/channelProviders/" + providerType + "/defaultConfig",
                            "Channel provider defaultConfig must satisfy configSchema"
                        )
                    );
                    errors.add(error);
                    manifestErrors.add(error);
                    continue;
                }

                String definitionDigest = DescriptorDefinitionDigests.channelProviderDefinitionDigest(descriptor);
                descriptorDefinitionDigests.putIfAbsent(providerType, definitionDigest);
                descriptorsByProviderType.putIfAbsent(
                    providerType,
                    new ChannelProviderDescriptor(
                        providerType,
                        registration.registrationId(),
                        registration.baseUrl(),
                        runJobPath(descriptor),
                        descriptor,
                        definitionDigest,
                        configSchema,
                        defaultConfig,
                        ChannelProviderDescriptor.jobDefinitions(descriptor)
                    )
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

        return new ChannelProviderRegistryLoadResult(
            List.copyOf(errors),
            List.copyOf(manifestErrors),
            Collections.unmodifiableSet(new TreeSet<>(expectedDescriptorIds)),
            Collections.unmodifiableSet(new TreeSet<>(missingDescriptorIds)),
            Collections.unmodifiableSet(new TreeSet<>(unexpectedDescriptorIds)),
            List.copyOf(duplicateDescriptorIds),
            Collections.unmodifiableMap(new TreeMap<>(loadedCounts)),
            Collections.unmodifiableMap(new TreeMap<>(descriptorDefinitionDigests)),
            Collections.unmodifiableMap(new TreeMap<>(descriptorsByProviderType))
        );
    }

    @SuppressWarnings("unchecked")
    private static String runJobPath(Map<String, Object> descriptor) {
        Object endpoints = descriptor.get("endpoints");
        if (!(endpoints instanceof Map<?, ?> rawEndpoints)) {
            return null;
        }
        Object value = rawEndpoints.get("runJob");
        return value instanceof String path && !path.isBlank() ? path.trim() : null;
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

    private static Map<String, Object> objectValue(
        Map<String, Object> source,
        String key,
        String registrationId,
        String providerType,
        List<RegistryValidationError> errors,
        List<RegistryValidationError> manifestErrors
    ) {
        Object value = source.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            RegistryValidationError error = manifestSchemaError(
                registrationId,
                providerType,
                new ManifestValidationError(
                    MANIFEST_SCHEMA_INVALID,
                    "/descriptors/channelProviders/" + providerType + "/" + key,
                    key + " must be a JSON object"
                )
            );
            errors.add(error);
            manifestErrors.add(error);
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String stringKey)) {
                RegistryValidationError error = manifestSchemaError(
                    registrationId,
                    providerType,
                    new ManifestValidationError(
                        MANIFEST_SCHEMA_INVALID,
                        "/descriptors/channelProviders/" + providerType + "/" + key,
                        key + " keys must be strings"
                    )
                );
                errors.add(error);
                manifestErrors.add(error);
                return null;
            }
            result.put(stringKey, entry.getValue());
        }
        return Collections.unmodifiableMap(result);
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
