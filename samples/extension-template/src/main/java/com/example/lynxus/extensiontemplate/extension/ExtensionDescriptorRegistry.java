package com.example.lynxus.extensiontemplate.extension;

import com.lynxus.extension.sdk.protocol.LynxusExtensionProtocol;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class ExtensionDescriptorRegistry {
    public static final String CHANNEL_PROVIDER_TYPE = "example.extension.template.channel";
    public static final String TOOL_CONNECTOR_TYPE = "example.extension.template.tool";

    private static final Map<String, Object> TENANT_CONFIG_SCHEMA = object(
        "type", "object",
        "properties", object("tenantId", object("type", "string")),
        "required", List.of("tenantId"),
        "additionalProperties", false
    );
    private static final List<Map<String, Object>> TENANT_CONFIG_UI_SCHEMA = List.of(
        field("/tenantId", "Tenant", "text", true, false, 10)
    );
    private static final Map<String, Object> API_KEY_CREDENTIAL_SCHEMA = object(
        "type", "object",
        "properties", object("apiKey", object("type", "string")),
        "required", List.of("apiKey"),
        "additionalProperties", false
    );
    private static final List<Map<String, Object>> API_KEY_CREDENTIAL_UI_SCHEMA = List.of(
        field("/apiKey", "API key", "password", true, true, 10)
    );

    public List<ChannelProviderDescriptor> channelProviders() {
        return List.of(
            new ChannelProviderDescriptor(
                CHANNEL_PROVIDER_TYPE,
                "Example Channel Provider",
                "Template channel provider descriptor. Replace schemas, schedules, and endpoint implementation with your provider contract.",
                TENANT_CONFIG_SCHEMA,
                TENANT_CONFIG_UI_SCHEMA,
                API_KEY_CREDENTIAL_SCHEMA,
                API_KEY_CREDENTIAL_UI_SCHEMA,
                ExtensionEndpointPaths.DEFAULT_CREDENTIAL_LIFECYCLE_PROFILE,
                object(
                    "type", "object",
                    "properties", object("roomPrefix", object("type", "string")),
                    "required", List.of("roomPrefix"),
                    "additionalProperties", false
                ),
                List.of(field("/roomPrefix", "Room prefix", "text", true, false, 10)),
                object("roomPrefix", "support-"),
                List.of(
                    new ChannelJobDefinition(
                        "PULL_MESSAGES",
                        "Pull messages",
                        "Optional pull-style synchronization job. Remove this definition if your provider is webhook-only.",
                        object(
                            "type", "object",
                            "properties", object("lookbackMinutes", object("type", "integer", "minimum", 1)),
                            "required", List.of("lookbackMinutes"),
                            "additionalProperties", false
                        ),
                        List.of(field("/lookbackMinutes", "Lookback minutes", "number", true, false, 10)),
                        new ScheduleConfig("INTERVAL", 300, null, "UTC", object("lookbackMinutes", 15)),
                        false,
                        60
                    )
                ),
                new ChannelOutboundCapabilities("FRAME_STREAM", true, true, true, true)
            )
        );
    }

    public List<ToolConnectorDescriptor> toolConnectors() {
        return List.of(
            new ToolConnectorDescriptor(
                TOOL_CONNECTOR_TYPE,
                "Example Tool Connector",
                "Template tool connector descriptor. Replace schemas and invoke implementation with your business system contract.",
                TENANT_CONFIG_SCHEMA,
                TENANT_CONFIG_UI_SCHEMA,
                API_KEY_CREDENTIAL_SCHEMA,
                API_KEY_CREDENTIAL_UI_SCHEMA,
                ExtensionEndpointPaths.DEFAULT_CREDENTIAL_LIFECYCLE_PROFILE,
                object(
                    "type", "object",
                    "properties", object("basePath", object("type", "string")),
                    "required", List.of("basePath"),
                    "additionalProperties", false
                ),
                List.of(field("/basePath", "Base path", "text", true, false, 10)),
                object(
                    "type", "object",
                    "properties", object("remoteOperation", object("type", "string")),
                    "required", List.of("remoteOperation"),
                    "additionalProperties", false
                ),
                List.of(field("/remoteOperation", "Remote operation", "text", true, false, 10))
            )
        );
    }

    public record ChannelProviderDescriptor(
        String providerType,
        String title,
        String description,
        Map<String, Object> accountConfigSchema,
        List<Map<String, Object>> accountConfigUiSchema,
        Map<String, Object> credentialSchema,
        List<Map<String, Object>> credentialUiSchema,
        String credentialLifecycleEndpointProfile,
        Map<String, Object> configSchema,
        List<Map<String, Object>> configUiSchema,
        Map<String, Object> defaultConfig,
        List<ChannelJobDefinition> jobDefinitions,
        ChannelOutboundCapabilities outbound
    ) {
        Map<String, Object> toManifestDescriptor() {
            return object(
                "providerType", providerType,
                "title", title,
                "description", description,
                "accountConfigSchema", accountConfigSchema,
                "accountConfigUiSchema", accountConfigUiSchema,
                "credentialSchema", credentialSchema,
                "credentialUiSchema", credentialUiSchema,
                LynxusExtensionProtocol.CREDENTIAL_LIFECYCLE_ENDPOINT_PROFILE_FIELD, credentialLifecycleEndpointProfile,
                "configSchema", configSchema,
                "configUiSchema", configUiSchema,
                "defaultConfig", defaultConfig,
                "jobDefinitions", jobDefinitions.stream().map(ChannelJobDefinition::toManifestDefinition).toList(),
                "outbound", outbound.toManifestCapabilities(),
                "endpoints", channelProviderEndpoints(jobDefinitions)
            );
        }
    }

    public record ToolConnectorDescriptor(
        String connectorType,
        String title,
        String description,
        Map<String, Object> accountConfigSchema,
        List<Map<String, Object>> accountConfigUiSchema,
        Map<String, Object> credentialSchema,
        List<Map<String, Object>> credentialUiSchema,
        String credentialLifecycleEndpointProfile,
        Map<String, Object> configSchema,
        List<Map<String, Object>> configUiSchema,
        Map<String, Object> operationMappingSchema,
        List<Map<String, Object>> operationMappingUiSchema
    ) {
        Map<String, Object> toManifestDescriptor() {
            return object(
                "connectorType", connectorType,
                "title", title,
                "description", description,
                "accountConfigSchema", accountConfigSchema,
                "accountConfigUiSchema", accountConfigUiSchema,
                "credentialSchema", credentialSchema,
                "credentialUiSchema", credentialUiSchema,
                LynxusExtensionProtocol.CREDENTIAL_LIFECYCLE_ENDPOINT_PROFILE_FIELD, credentialLifecycleEndpointProfile,
                "configSchema", configSchema,
                "configUiSchema", configUiSchema,
                "operationMappingSchema", operationMappingSchema,
                "operationMappingUiSchema", operationMappingUiSchema,
                "endpoints", object(LynxusExtensionProtocol.TOOL_CONNECTOR_INVOKE_ENDPOINT, ExtensionEndpointPaths.TOOL_INVOKE)
            );
        }
    }

    public record ChannelJobDefinition(
        String jobType,
        String title,
        String description,
        Map<String, Object> jobConfigSchema,
        List<Map<String, Object>> jobConfigUiSchema,
        ScheduleConfig defaultSchedule,
        boolean defaultEnabled,
        int defaultJobTimeoutSeconds
    ) {
        Map<String, Object> toManifestDefinition() {
            return object(
                "jobType", jobType,
                "title", title,
                "description", description,
                "jobConfigSchema", jobConfigSchema,
                "jobConfigUiSchema", jobConfigUiSchema,
                "defaultSchedule", defaultSchedule.toManifestSchedule(),
                "defaultEnabled", defaultEnabled,
                "defaultJobTimeoutSeconds", defaultJobTimeoutSeconds
            );
        }
    }

    public record ScheduleConfig(
        String scheduleType,
        int intervalSeconds,
        String cronExpression,
        String timezone,
        Map<String, Object> jobConfig
    ) {
        Map<String, Object> toManifestSchedule() {
            return object(
                "scheduleType", scheduleType,
                "intervalSeconds", intervalSeconds,
                "cronExpression", cronExpression,
                "timezone", timezone,
                "jobConfig", jobConfig
            );
        }
    }

    public record ChannelOutboundCapabilities(
        String mode,
        boolean supportsTyping,
        boolean supportsDraftUpdate,
        boolean supportsFinalDelivery,
        boolean requiresIdempotentFinalDelivery
    ) {
        Map<String, Object> toManifestCapabilities() {
            return object(
                "mode", mode,
                "supportsTyping", supportsTyping,
                "supportsDraftUpdate", supportsDraftUpdate,
                "supportsFinalDelivery", supportsFinalDelivery,
                "requiresIdempotentFinalDelivery", requiresIdempotentFinalDelivery
            );
        }
    }

    private static Map<String, Object> channelProviderEndpoints(List<ChannelJobDefinition> jobDefinitions) {
        if (jobDefinitions.isEmpty()) {
            return Map.of();
        }
        return object(LynxusExtensionProtocol.CHANNEL_PROVIDER_RUN_JOB_ENDPOINT, ExtensionEndpointPaths.CHANNEL_RUN_JOB);
    }

    private static Map<String, Object> field(
        String key,
        String label,
        String component,
        boolean required,
        boolean secret,
        int order
    ) {
        MapBuilder builder = new MapBuilder()
            .put("key", key)
            .put("label", label)
            .put("component", component)
            .put("required", required);
        if (secret) {
            builder.put("secret", true);
        }
        return builder.put("order", order).build();
    }

    private static Map<String, Object> object(Object... entries) {
        MapBuilder builder = new MapBuilder();
        for (int index = 0; index < entries.length; index += 2) {
            builder.put((String) entries[index], entries[index + 1]);
        }
        return builder.build();
    }

    private static final class MapBuilder {
        private final java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();

        MapBuilder put(String key, Object value) {
            values.put(key, value);
            return this;
        }

        Map<String, Object> build() {
            return Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
        }
    }
}
