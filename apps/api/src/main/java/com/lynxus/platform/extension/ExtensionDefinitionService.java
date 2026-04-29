package com.lynxus.platform.extension;

import com.lynxus.extension.sdk.common.DescriptorDefinitionDigests;
import com.lynxus.extension.sdk.protocol.JsonDocuments;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHttp;
import com.lynxus.extension.sdk.protocol.LynxusExtensionProtocol;
import com.lynxus.extension.sdk.registration.ExtensionRegistration;
import com.lynxus.extension.sdk.registration.RegistrationSource;
import com.lynxus.extension.sdk.validation.ManifestValidationError;
import com.lynxus.extension.sdk.validation.ManifestValidationResult;
import com.lynxus.extension.sdk.validation.ManifestValidator;
import com.lynxus.platform.extension.ExtensionDefinitionDtos.ChannelProviderDefinition;
import com.lynxus.platform.extension.ExtensionDefinitionDtos.ChannelProviderJobDefinition;
import com.lynxus.platform.extension.ExtensionDefinitionDtos.CredentialCapability;
import com.lynxus.platform.extension.ExtensionDefinitionDtos.CredentialCapabilityMode;
import com.lynxus.platform.extension.ExtensionDefinitionDtos.ToolConnectorDefinition;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public final class ExtensionDefinitionService {
    private static final String ERROR = "ERROR";
    private static final String CHANNEL_PROVIDER = "CHANNEL_PROVIDER";
    private static final String TOOL_CONNECTOR = "TOOL_CONNECTOR";
    private static final Set<String> CREDENTIAL_ENDPOINTS = Set.of(
        LynxusExtensionProtocol.CREATE_CREDENTIAL_ENDPOINT,
        LynxusExtensionProtocol.ROTATE_CREDENTIAL_ENDPOINT,
        LynxusExtensionProtocol.REVOKE_CREDENTIAL_ENDPOINT,
        LynxusExtensionProtocol.VALIDATE_CREDENTIAL_ENDPOINT
    );
    private static final Set<String> SENSITIVE_DEFAULT_KEYS = Set.of(
        "externalSecretRef",
        "password",
        "apiKey",
        "accessToken",
        "refreshToken",
        "privateKey",
        "webhookSigningSecret"
    );

    private final ExtensionRegistrationService registrationService;
    private final ExtensionManifestFetcher manifestFetcher;
    private final String internalAuthToken;

    public ExtensionDefinitionService(
        ExtensionRegistrationService registrationService,
        ExtensionManifestFetcher manifestFetcher,
        @Value("${lynxus.internal-auth.token}") String internalAuthToken
    ) {
        this.registrationService = registrationService;
        this.manifestFetcher = manifestFetcher;
        this.internalAuthToken = requireInternalAuthToken(internalAuthToken);
    }

    public List<ChannelProviderDefinition> channelProviders() {
        ExtensionDefinitionRegistry registry = loadRegistry();
        if (!registry.ready()) {
            throw new ExtensionDefinitionRegistryNotReadyException();
        }
        return registry.channelProviders();
    }

    public List<ToolConnectorDefinition> toolConnectors() {
        ExtensionDefinitionRegistry registry = loadRegistry();
        if (!registry.ready()) {
            throw new ExtensionDefinitionRegistryNotReadyException();
        }
        return registry.toolConnectors();
    }

    public ExtensionDefinitionRegistry loadRegistry() {
        List<RegistryValidationError> errors = new ArrayList<>();
        List<RegistryValidationError> manifestErrors = new ArrayList<>();
        Map<String, ChannelProviderDefinition> channelProviders = new TreeMap<>();
        Map<String, ToolConnectorDefinition> toolConnectors = new TreeMap<>();
        Map<String, InternalCredentialRoutingFacts> credentialRoutingFacts = new TreeMap<>();
        Map<String, Integer> channelProviderCounts = new TreeMap<>();
        Map<String, Integer> toolConnectorCounts = new TreeMap<>();

        for (ExtensionRegistration registration : registrationService.services().stream()
            .sorted(Comparator.comparing(ExtensionRegistration::registrationId))
            .toList()) {
            LoadedManifest loaded = loadManifest(registration, errors, manifestErrors);
            if (loaded.manifest() == null) {
                continue;
            }

            Set<String> expectedChannelProviders = new LinkedHashSet<>(registration.exposes().channelProviderTypes());
            Set<String> expectedToolConnectors = new LinkedHashSet<>(registration.exposes().toolConnectorTypes());
            Set<String> loadedChannelProviders = new LinkedHashSet<>();
            Set<String> loadedToolConnectors = new LinkedHashSet<>();

            for (Map<String, Object> descriptor : channelProviderDescriptors(loaded.manifest())) {
                Object rawProviderType = descriptor.get("providerType");
                if (!(rawProviderType instanceof String providerType) || providerType.isBlank()) {
                    continue;
                }
                loadedChannelProviders.add(providerType);
                channelProviderCounts.merge(providerType, 1, Integer::sum);
                if (!expectedChannelProviders.contains(providerType)) {
                    errors.add(registryError(
                        "UNEXPECTED_DESCRIPTOR",
                        registration.registrationId(),
                        CHANNEL_PROVIDER,
                        providerType,
                        "Manifest returned a channel provider descriptor not declared in registration config"
                    ));
                    continue;
                }
                channelProviders.putIfAbsent(providerType, channelProviderDefinition(descriptor, registration));
                credentialRoutingFacts.putIfAbsent(
                    credentialRoutingKey(CHANNEL_PROVIDER, providerType),
                    credentialRoutingFacts(CHANNEL_PROVIDER, providerType, descriptor, registration)
                );
            }
            for (String expected : expectedChannelProviders) {
                if (!loadedChannelProviders.contains(expected)) {
                    errors.add(registryError(
                        "MISSING_DESCRIPTOR",
                        registration.registrationId(),
                        CHANNEL_PROVIDER,
                        expected,
                        "Descriptor declared in registration config was not loaded from manifest"
                    ));
                }
            }

            for (Map<String, Object> descriptor : toolConnectorDescriptors(loaded.manifest())) {
                Object rawConnectorType = descriptor.get("connectorType");
                if (!(rawConnectorType instanceof String connectorType) || connectorType.isBlank()) {
                    continue;
                }
                loadedToolConnectors.add(connectorType);
                toolConnectorCounts.merge(connectorType, 1, Integer::sum);
                if (!expectedToolConnectors.contains(connectorType)) {
                    errors.add(registryError(
                        "UNEXPECTED_DESCRIPTOR",
                        registration.registrationId(),
                        TOOL_CONNECTOR,
                        connectorType,
                        "Manifest returned a tool connector descriptor not declared in registration config"
                    ));
                    continue;
                }
                toolConnectors.putIfAbsent(connectorType, toolConnectorDefinition(descriptor, registration));
                credentialRoutingFacts.putIfAbsent(
                    credentialRoutingKey(TOOL_CONNECTOR, connectorType),
                    credentialRoutingFacts(TOOL_CONNECTOR, connectorType, descriptor, registration)
                );
            }
            for (String expected : expectedToolConnectors) {
                if (!loadedToolConnectors.contains(expected)) {
                    errors.add(registryError(
                        "MISSING_DESCRIPTOR",
                        registration.registrationId(),
                        TOOL_CONNECTOR,
                        expected,
                        "Descriptor declared in registration config was not loaded from manifest"
                    ));
                }
            }
        }

        addDuplicateErrors(channelProviderCounts, CHANNEL_PROVIDER, errors);
        addDuplicateErrors(toolConnectorCounts, TOOL_CONNECTOR, errors);

        boolean ready = errors.isEmpty();
        return new ExtensionDefinitionRegistry(
            ready,
            List.copyOf(channelProviders.values()),
            List.copyOf(toolConnectors.values()),
            Collections.unmodifiableMap(new LinkedHashMap<>(credentialRoutingFacts)),
            List.copyOf(errors),
            List.copyOf(manifestErrors)
        );
    }

    public InternalCredentialRoutingFacts requireCredentialRoutingFacts(String descriptorType, String descriptorId) {
        ExtensionDefinitionRegistry registry = loadRegistry();
        if (!registry.ready()) {
            throw new ExtensionDefinitionRegistryNotReadyException();
        }
        InternalCredentialRoutingFacts facts = registry.credentialRoutingFacts().get(credentialRoutingKey(descriptorType, descriptorId));
        if (facts == null) {
            throw new IllegalArgumentException("extension descriptor does not exist: " + descriptorType + "/" + descriptorId);
        }
        return facts;
    }

    private LoadedManifest loadManifest(
        ExtensionRegistration registration,
        List<RegistryValidationError> errors,
        List<RegistryValidationError> manifestErrors
    ) {
        Object manifest;
        try {
            manifest = JsonDocuments.parseObject(manifestFetcher.fetch(
                LynxusExtensionHttp.manifestUrl(registration.baseUrl()),
                LynxusExtensionHttp.serviceLevelHeaders("Bearer " + internalAuthToken, registration.registrationId())
            ));
        } catch (IOException exception) {
            RegistryValidationError error = manifestFetchFailedError(registration.registrationId(), exception);
            errors.add(error);
            manifestErrors.add(error);
            return new LoadedManifest(null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            RegistryValidationError error = manifestFetchFailedError(registration.registrationId(), exception);
            errors.add(error);
            manifestErrors.add(error);
            return new LoadedManifest(null);
        } catch (RuntimeException exception) {
            RegistryValidationError error = manifestSchemaError(
                registration.registrationId(),
                null,
                null,
                new ManifestValidationError("MANIFEST_JSON_INVALID", "", "Manifest response must be a JSON object")
            );
            errors.add(error);
            manifestErrors.add(error);
            return new LoadedManifest(null);
        }

        ManifestValidationResult validation = ManifestValidator.validate(manifest);
        if (!validation.valid()) {
            for (ManifestValidationError violation : validation.errors()) {
                RegistryValidationError error = manifestSchemaError(registration.registrationId(), null, null, violation);
                errors.add(error);
                manifestErrors.add(error);
            }
            return new LoadedManifest(null);
        }
        ManifestValidationError sensitiveFieldViolation = sensitiveDefaultViolation(manifest);
        if (sensitiveFieldViolation != null) {
            RegistryValidationError error = manifestSchemaError(registration.registrationId(), null, null, sensitiveFieldViolation);
            errors.add(error);
            manifestErrors.add(error);
            return new LoadedManifest(null);
        }
        return new LoadedManifest(manifest);
    }

    private static ChannelProviderDefinition channelProviderDefinition(
        Map<String, Object> descriptor,
        ExtensionRegistration registration
    ) {
        return new ChannelProviderDefinition(
            stringValue(descriptor, "providerType"),
            stringValue(descriptor, "title"),
            nullableStringValue(descriptor, "description"),
            DescriptorDefinitionDigests.channelProviderDefinitionDigest(descriptor),
            objectValue(descriptor, "accountConfigSchema"),
            arrayValue(descriptor, "accountConfigUiSchema"),
            credentialCapability(descriptor, registration),
            objectValue(descriptor, "configSchema"),
            arrayValue(descriptor, "configUiSchema"),
            objectValue(descriptor, "defaultConfig"),
            jobDefinitions(descriptor)
        );
    }

    private static ToolConnectorDefinition toolConnectorDefinition(
        Map<String, Object> descriptor,
        ExtensionRegistration registration
    ) {
        return new ToolConnectorDefinition(
            stringValue(descriptor, "connectorType"),
            stringValue(descriptor, "title"),
            nullableStringValue(descriptor, "description"),
            DescriptorDefinitionDigests.toolConnectorDefinitionDigest(descriptor),
            objectValue(descriptor, "accountConfigSchema"),
            arrayValue(descriptor, "accountConfigUiSchema"),
            credentialCapability(descriptor, registration),
            objectValue(descriptor, "configSchema"),
            arrayValue(descriptor, "configUiSchema"),
            objectValue(descriptor, "operationMappingSchema"),
            arrayValue(descriptor, "operationMappingUiSchema")
        );
    }

    private static CredentialCapability credentialCapability(Map<String, Object> descriptor, ExtensionRegistration registration) {
        Map<String, Object> credentialSchema = nullableObjectValue(descriptor, "credentialSchema");
        if (credentialSchema == null) {
            return new CredentialCapability(false, null, null, List.of());
        }

        Map<String, Object> endpoints = objectValue(descriptor, "endpoints");
        boolean hasAllCredentialEndpoints = endpoints.keySet().containsAll(CREDENTIAL_ENDPOINTS);
        if (hasAllCredentialEndpoints) {
            return new CredentialCapability(
                true,
                CredentialCapabilityMode.REMOTE_LIFECYCLE,
                credentialSchema,
                arrayValue(descriptor, "credentialUiSchema")
            );
        }
        if (registration.source() == RegistrationSource.CORE_PRESET) {
            return new CredentialCapability(
                true,
                CredentialCapabilityMode.CORE_ENCRYPTED_REFERENCE,
                credentialSchema,
                arrayValue(descriptor, "credentialUiSchema")
            );
        }
        return new CredentialCapability(false, null, null, List.of());
    }

    private static InternalCredentialRoutingFacts credentialRoutingFacts(
        String descriptorType,
        String descriptorId,
        Map<String, Object> descriptor,
        ExtensionRegistration registration
    ) {
        CredentialCapability capability = credentialCapability(descriptor, registration);
        Map<String, Object> endpoints = objectValue(descriptor, "endpoints");
        return new InternalCredentialRoutingFacts(
            descriptorType,
            descriptorId,
            capability.mode(),
            capability.credentialSchema(),
            registration.baseUrl(),
            stringEndpoint(endpoints, LynxusExtensionProtocol.CREATE_CREDENTIAL_ENDPOINT),
            stringEndpoint(endpoints, LynxusExtensionProtocol.ROTATE_CREDENTIAL_ENDPOINT),
            stringEndpoint(endpoints, LynxusExtensionProtocol.VALIDATE_CREDENTIAL_ENDPOINT),
            stringEndpoint(endpoints, LynxusExtensionProtocol.REVOKE_CREDENTIAL_ENDPOINT)
        );
    }

    private static String stringEndpoint(Map<String, Object> endpoints, String endpointName) {
        Object value = endpoints.get(endpointName);
        return value instanceof String path && !path.isBlank() ? path : null;
    }

    @SuppressWarnings("unchecked")
    private static List<ChannelProviderJobDefinition> jobDefinitions(Map<String, Object> descriptor) {
        Object rawJobs = descriptor.get("jobDefinitions");
        if (!(rawJobs instanceof List<?> jobs)) {
            return List.of();
        }
        List<ChannelProviderJobDefinition> result = new ArrayList<>();
        for (Object rawJob : jobs) {
            Map<String, Object> job = (Map<String, Object>) rawJob;
            result.add(new ChannelProviderJobDefinition(
                stringValue(job, "jobType"),
                stringValue(job, "title"),
                nullableStringValue(job, "description"),
                objectValue(job, "jobConfigSchema"),
                arrayValue(job, "jobConfigUiSchema"),
                nullableObjectValue(job, "defaultSchedule"),
                booleanValue(job, "defaultEnabled"),
                integerValue(job, "defaultJobTimeoutSeconds")
            ));
        }
        result.sort(Comparator.comparing(ChannelProviderJobDefinition::jobType));
        return List.copyOf(result);
    }

    private static void addDuplicateErrors(
        Map<String, Integer> counts,
        String descriptorType,
        List<RegistryValidationError> errors
    ) {
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() <= 1) {
                continue;
            }
            errors.add(registryError(
                "DUPLICATE_DESCRIPTOR",
                null,
                descriptorType,
                entry.getKey(),
                "Descriptor was loaded from more than one registration"
            ));
        }
    }

    private static RegistryValidationError manifestFetchFailedError(String registrationId, Exception exception) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("phase", "MANIFEST_FETCH");
        details.put("httpStatus", null);
        details.put("failureReason", exception.getClass().getSimpleName());
        return new RegistryValidationError(
            "MANIFEST_FETCH_FAILED",
            ERROR,
            registrationId,
            null,
            null,
            "Failed to fetch extension manifest",
            true,
            Collections.unmodifiableMap(details)
        );
    }

    private static RegistryValidationError manifestSchemaError(
        String registrationId,
        String descriptorType,
        String descriptorId,
        ManifestValidationError violation
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("phase", "MANIFEST_FETCH");
        details.put("schemaPath", violation.path());
        details.put("violation", violation.code());
        return new RegistryValidationError(
            "MANIFEST_SCHEMA_INVALID",
            ERROR,
            registrationId,
            descriptorType,
            descriptorId,
            "Extension manifest failed schema validation",
            false,
            Collections.unmodifiableMap(details)
        );
    }

    private static RegistryValidationError registryError(
        String code,
        String registrationId,
        String descriptorType,
        String descriptorId,
        String message
    ) {
        return new RegistryValidationError(
            code,
            ERROR,
            registrationId,
            descriptorType,
            descriptorId,
            message,
            false,
            Map.of("phase", "REGISTRY_LOAD")
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> channelProviderDescriptors(Object manifest) {
        Map<String, Object> manifestMap = (Map<String, Object>) manifest;
        Map<String, Object> descriptors = (Map<String, Object>) manifestMap.get("descriptors");
        return (List<Map<String, Object>>) descriptors.get("channelProviders");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toolConnectorDescriptors(Object manifest) {
        Map<String, Object> manifestMap = (Map<String, Object>) manifest;
        Map<String, Object> descriptors = (Map<String, Object>) manifestMap.get("descriptors");
        return (List<Map<String, Object>>) descriptors.get("toolConnectors");
    }

    private static String requireInternalAuthToken(String internalAuthToken) {
        if (internalAuthToken == null || internalAuthToken.isBlank()) {
            throw new IllegalStateException("lynxus.internal-auth.token must be configured");
        }
        return internalAuthToken.trim();
    }

    private static String credentialRoutingKey(String descriptorType, String descriptorId) {
        return descriptorType + "/" + descriptorId;
    }

    private static String stringValue(Map<String, Object> source, String field) {
        Object value = source.get(field);
        return value instanceof String stringValue ? stringValue : null;
    }

    private static String nullableStringValue(Map<String, Object> source, String field) {
        return stringValue(source, field);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectValue(Map<String, Object> source, String field) {
        Object value = source.get(field);
        if (value instanceof Map<?, ?> map) {
            return Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) map));
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nullableObjectValue(Map<String, Object> source, String field) {
        Object value = source.get(field);
        if (value instanceof Map<?, ?> map) {
            return Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) map));
        }
        return null;
    }

    private static List<Object> arrayValue(Map<String, Object> source, String field) {
        Object value = source.get(field);
        if (value instanceof List<?> list) {
            return List.copyOf(list);
        }
        return List.of();
    }

    private static Boolean booleanValue(Map<String, Object> source, String field) {
        Object value = source.get(field);
        return value instanceof Boolean booleanValue ? booleanValue : null;
    }

    private static Integer integerValue(Map<String, Object> source, String field) {
        Object value = source.get(field);
        return value instanceof Number numberValue ? numberValue.intValue() : null;
    }

    private static ManifestValidationError sensitiveDefaultViolation(Object manifest) {
        List<Map<String, Object>> descriptors = channelProviderDescriptors(manifest);
        for (int descriptorIndex = 0; descriptorIndex < descriptors.size(); descriptorIndex += 1) {
            Map<String, Object> descriptor = descriptors.get(descriptorIndex);
            ManifestValidationError defaultConfigViolation = sensitiveDefaultViolation(
                descriptor.get("defaultConfig"),
                "/descriptors/channelProviders/" + descriptorIndex + "/defaultConfig"
            );
            if (defaultConfigViolation != null) {
                return defaultConfigViolation;
            }

            Object rawJobs = descriptor.get("jobDefinitions");
            if (!(rawJobs instanceof List<?> jobs)) {
                continue;
            }
            for (int jobIndex = 0; jobIndex < jobs.size(); jobIndex += 1) {
                if (!(jobs.get(jobIndex) instanceof Map<?, ?> job)) {
                    continue;
                }
                Object defaultSchedule = job.get("defaultSchedule");
                if (!(defaultSchedule instanceof Map<?, ?> schedule)) {
                    continue;
                }
                ManifestValidationError jobConfigViolation = sensitiveDefaultViolation(
                    schedule.get("jobConfig"),
                    "/descriptors/channelProviders/" + descriptorIndex + "/jobDefinitions/" + jobIndex + "/defaultSchedule/jobConfig"
                );
                if (jobConfigViolation != null) {
                    return jobConfigViolation;
                }
            }
        }
        return null;
    }

    private static ManifestValidationError sensitiveDefaultViolation(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() instanceof String stringKey ? stringKey : "";
                String childPath = path + "/" + key;
                if (SENSITIVE_DEFAULT_KEYS.contains(key) || ("secret".equals(key) && Boolean.TRUE.equals(entry.getValue()))) {
                    return sensitiveDefaultViolationAt(childPath);
                }
                ManifestValidationError nested = sensitiveDefaultViolation(entry.getValue(), childPath);
                if (nested != null) {
                    return nested;
                }
            }
        }
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index += 1) {
                ManifestValidationError nested = sensitiveDefaultViolation(list.get(index), path + "/" + index);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static ManifestValidationError sensitiveDefaultViolationAt(String path) {
        return new ManifestValidationError(
            "CONFIG_SECRET_MATERIAL_NOT_ALLOWED",
            path,
            "Normal config defaults must not contain secret material"
        );
    }

    public record ExtensionDefinitionRegistry(
        boolean ready,
        List<ChannelProviderDefinition> channelProviders,
        List<ToolConnectorDefinition> toolConnectors,
        Map<String, InternalCredentialRoutingFacts> credentialRoutingFacts,
        List<RegistryValidationError> errors,
        List<RegistryValidationError> manifestErrors
    ) {}

    public record InternalCredentialRoutingFacts(
        String descriptorType,
        String descriptorId,
        CredentialCapabilityMode credentialMode,
        Map<String, Object> credentialSchema,
        String baseUrl,
        String createCredentialPath,
        String rotateCredentialPath,
        String validateCredentialPath,
        String revokeCredentialPath
    ) {}

    private record LoadedManifest(Object manifest) {}
}
