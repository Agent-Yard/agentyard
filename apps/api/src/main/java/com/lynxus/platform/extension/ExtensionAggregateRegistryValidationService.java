package com.lynxus.platform.extension;

import com.lynxus.platform.extension.ExtensionDefinitionDtos.ChannelProviderDefinition;
import com.lynxus.platform.extension.ExtensionDefinitionDtos.ToolConnectorDefinition;
import com.lynxus.platform.extension.ExtensionDefinitionService.ExtensionDefinitionRegistry;
import com.lynxus.platform.extension.ExtensionRegistryValidation.RuntimeRegistryValidation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public final class ExtensionAggregateRegistryValidationService {
    private static final String READY = "READY";
    private static final String NOT_READY = "NOT_READY";
    private static final String SERVICE = "api";
    private static final String COMPONENT = "EXTENSION_REGISTRY";
    private static final String REGISTRY_TYPE = "AGGREGATE";
    private static final String ERROR = "ERROR";
    private static final String TOOL_CONNECTOR = "TOOL_CONNECTOR";
    private static final String CHANNEL_PROVIDER = "CHANNEL_PROVIDER";
    private static final String AGGREGATE_VALIDATION = "AGGREGATE_VALIDATION";
    private static final String APPLICATION_TASK_EXECUTOR = "applicationTaskExecutor";

    private final ExtensionDefinitionService definitionService;
    private final ExtensionRegistrationService registrationService;
    private final RuntimeRegistryValidationClient runtimeClient;
    private final Executor validationExecutor;

    public ExtensionAggregateRegistryValidationService(
        ExtensionDefinitionService definitionService,
        ExtensionRegistrationService registrationService,
        RuntimeRegistryValidationClient runtimeClient,
        @Qualifier(APPLICATION_TASK_EXECUTOR) Executor validationExecutor
    ) {
        this.definitionService = definitionService;
        this.registrationService = registrationService;
        this.runtimeClient = runtimeClient;
        this.validationExecutor = validationExecutor;
    }

    public ExtensionRegistryValidation validate() {
        String registrationConfigDigest = registrationService.registrationConfigDigest();
        ExtensionDefinitionRegistry localRegistry = definitionService.loadRegistry();
        if (!localRegistry.ready()) {
            return validation(
                NOT_READY,
                registrationConfigDigest,
                "API aggregate extension registry is not ready",
                localRegistry.errors()
            );
        }

        CompletableFuture<RuntimeRegistryValidationResult> agentRuntimeFuture =
            CompletableFuture.supplyAsync(
                () -> runtimeValidation("agent-runtime", runtimeClient::agentRuntimeValidation),
                validationExecutor
            );
        CompletableFuture<RuntimeRegistryValidationResult> channelGatewayFuture =
            CompletableFuture.supplyAsync(
                () -> runtimeValidation("channel-gateway", runtimeClient::channelGatewayValidation),
                validationExecutor
            );

        RuntimeRegistryValidationResult agentRuntime = agentRuntimeFuture.join();
        RuntimeRegistryValidationResult channelGateway = channelGatewayFuture.join();

        List<RegistryValidationError> errors = new ArrayList<>();
        addRuntimeFailure(agentRuntime, errors);
        addRuntimeFailure(channelGateway, errors);
        if (errors.isEmpty()) {
            compareRegistrationConfigDigest(registrationConfigDigest, agentRuntime.validation(), errors);
            compareRegistrationConfigDigest(registrationConfigDigest, channelGateway.validation(), errors);
            compareRuntimeRegistry(
                TOOL_CONNECTOR,
                "agent-runtime",
                toolConnectorDigests(localRegistry.toolConnectors()),
                agentRuntime.validation(),
                errors
            );
            compareRuntimeRegistry(
                CHANNEL_PROVIDER,
                "channel-gateway",
                channelProviderDigests(localRegistry.channelProviders()),
                channelGateway.validation(),
                errors
            );
        }

        boolean ready = errors.isEmpty();
        return validation(
            ready ? READY : NOT_READY,
            registrationConfigDigest,
            ready ? "API aggregate extension registry is ready" : "API aggregate extension registry is not ready",
            errors
        );
    }

    private static RuntimeRegistryValidationResult runtimeValidation(
        String runtimeService,
        Supplier<RuntimeRegistryValidationResult> validationCall
    ) {
        try {
            RuntimeRegistryValidationResult result = validationCall.get();
            return result == null
                ? RuntimeRegistryValidationResult.failure(runtimeService, "malformed response", null)
                : result;
        } catch (RuntimeException exception) {
            return RuntimeRegistryValidationResult.failure(runtimeService, "request failure", null);
        }
    }

    private static void compareRegistrationConfigDigest(
        String apiDigest,
        RuntimeRegistryValidation runtime,
        List<RegistryValidationError> errors
    ) {
        if (apiDigest.equals(runtime.registrationConfigDigest())) {
            return;
        }
        Map<String, Object> details = aggregateDetails(runtime.service());
        errors.add(error(
            "REGISTRATION_CONFIG_DIGEST_MISMATCH",
            null,
            null,
            "API registration config digest does not match runtime registry digest",
            false,
            details
        ));
    }

    private static void compareRuntimeRegistry(
        String descriptorType,
        String runtimeService,
        Map<String, String> apiDefinitionDigests,
        RuntimeRegistryValidation runtime,
        List<RegistryValidationError> errors
    ) {
        Set<String> apiDescriptorIds = new TreeSet<>(apiDefinitionDigests.keySet());
        Set<String> runtimeDescriptorIds = new TreeSet<>(runtime.loadedDescriptorIds());
        if (!apiDescriptorIds.equals(runtimeDescriptorIds)) {
            Map<String, Object> details = aggregateDetails(runtimeService);
            details.put("descriptorType", descriptorType);
            details.put("apiDescriptorIds", List.copyOf(apiDescriptorIds));
            details.put("runtimeDescriptorIds", List.copyOf(runtimeDescriptorIds));
            errors.add(error(
                "REGISTRY_DESCRIPTOR_MISMATCH",
                descriptorType,
                null,
                "API loaded descriptor ids do not match runtime loaded descriptor ids",
                false,
                details
            ));
            return;
        }

        for (String descriptorId : apiDescriptorIds) {
            String apiDigest = apiDefinitionDigests.get(descriptorId);
            String runtimeDigest = runtime.descriptorDefinitionDigests().get(descriptorId);
            if (apiDigest.equals(runtimeDigest)) {
                continue;
            }
            Map<String, Object> details = aggregateDetails(runtimeService);
            details.put("descriptorType", descriptorType);
            errors.add(error(
                "REGISTRY_DEFINITION_DIGEST_MISMATCH",
                descriptorType,
                descriptorId,
                "API descriptor definition digest does not match runtime descriptor definition digest",
                false,
                details
            ));
        }
    }

    private static void addRuntimeFailure(
        RuntimeRegistryValidationResult result,
        List<RegistryValidationError> errors
    ) {
        if (result.ready()) {
            return;
        }
        Map<String, Object> details = aggregateDetails(result.runtimeService());
        details.put("httpStatus", result.httpStatus());
        details.put("failureReason", result.failureReason());
        errors.add(error(
            "RUNTIME_REGISTRY_UNREACHABLE",
            null,
            null,
            "Runtime registry validation endpoint could not be used for aggregate validation",
            true,
            details
        ));
    }

    private static Map<String, String> toolConnectorDigests(List<ToolConnectorDefinition> definitions) {
        Map<String, String> digests = new TreeMap<>();
        for (ToolConnectorDefinition definition : definitions) {
            digests.put(definition.connectorType(), definition.definitionDigest());
        }
        return Collections.unmodifiableMap(digests);
    }

    private static Map<String, String> channelProviderDigests(List<ChannelProviderDefinition> definitions) {
        Map<String, String> digests = new TreeMap<>();
        for (ChannelProviderDefinition definition : definitions) {
            digests.put(definition.providerType(), definition.definitionDigest());
        }
        return Collections.unmodifiableMap(digests);
    }

    private static ExtensionRegistryValidation validation(
        String status,
        String registrationConfigDigest,
        String summary,
        List<RegistryValidationError> errors
    ) {
        return new ExtensionRegistryValidation(
            status,
            SERVICE,
            COMPONENT,
            REGISTRY_TYPE,
            registrationConfigDigest,
            summary,
            List.copyOf(errors)
        );
    }

    private static RegistryValidationError error(
        String code,
        String descriptorType,
        String descriptorId,
        String message,
        boolean retryable,
        Map<String, Object> details
    ) {
        return new RegistryValidationError(
            code,
            ERROR,
            null,
            descriptorType,
            descriptorId,
            message,
            retryable,
            Collections.unmodifiableMap(new LinkedHashMap<>(details))
        );
    }

    private static Map<String, Object> aggregateDetails(String runtimeService) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("phase", AGGREGATE_VALIDATION);
        details.put("service", SERVICE);
        details.put("runtimeService", runtimeService);
        return details;
    }
}
