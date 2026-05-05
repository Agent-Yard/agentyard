package com.lynxus.extension.sdk.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DescriptorDefinitionDigests {
    public static final String CHANNEL_PROVIDER_DESCRIPTOR_TYPE = "CHANNEL_PROVIDER";
    public static final String TOOL_CONNECTOR_DESCRIPTOR_TYPE = "TOOL_CONNECTOR";
    private static final Set<String> SCHEMA_ANNOTATION_KEYWORDS = Set.of("title", "description", "default");
    private static final Set<String> SCHEMA_MAP_KEYWORDS = Set.of(
        "properties",
        "$defs",
        "definitions",
        "patternProperties",
        "dependentSchemas"
    );

    private DescriptorDefinitionDigests() {}

    public static Map<String, Object> channelProviderDefinitionDigestInput(Map<String, Object> descriptor) {
        List<Map<String, Object>> jobDefinitions = new ArrayList<>();
        for (Map<String, Object> job : objects(descriptor.get("jobDefinitions"))) {
            Map<String, Object> normalizedJob = new LinkedHashMap<>();
            normalizedJob.put("jobType", job.get("jobType"));
            normalizedJob.put("jobConfigSchema", validationOnlySchema(job.get("jobConfigSchema")));
            jobDefinitions.add(normalizedJob);
        }
        jobDefinitions.sort(Comparator.comparing(job -> (String) job.get("jobType")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("descriptorType", CHANNEL_PROVIDER_DESCRIPTOR_TYPE);
        result.put("providerType", descriptor.get("providerType"));
        result.put("accountConfigSchema", validationOnlySchema(descriptor.get("accountConfigSchema")));
        result.put("credentialSchema", validationOnlySchema(descriptor.get("credentialSchema")));
        result.put("outbound", channelProviderOutbound(descriptor));
        result.put("endpoints", channelProviderEndpoints(descriptor));
        result.put("configSchema", validationOnlySchema(descriptor.get("configSchema")));
        result.put("jobDefinitions", jobDefinitions);
        return result;
    }

    public static String channelProviderDefinitionDigest(Map<String, Object> descriptor) {
        return LynxusCanonicalJson.sha256ValueDigest(channelProviderDefinitionDigestInput(descriptor));
    }

    public static String channelProviderDefinitionCanonicalJson(Map<String, Object> descriptor) {
        return LynxusCanonicalJson.canonicalizeValue(channelProviderDefinitionDigestInput(descriptor));
    }

    public static byte[] channelProviderDefinitionCanonicalBytes(Map<String, Object> descriptor) {
        return LynxusCanonicalJson.canonicalValueBytes(channelProviderDefinitionDigestInput(descriptor));
    }

    public static Map<String, Object> toolConnectorDefinitionDigestInput(Map<String, Object> descriptor) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("descriptorType", TOOL_CONNECTOR_DESCRIPTOR_TYPE);
        result.put("connectorType", descriptor.get("connectorType"));
        result.put("accountConfigSchema", validationOnlySchema(descriptor.get("accountConfigSchema")));
        result.put("credentialSchema", validationOnlySchema(descriptor.get("credentialSchema")));
        result.put("configSchema", validationOnlySchema(descriptor.get("configSchema")));
        result.put("operationMappingSchema", validationOnlySchema(descriptor.get("operationMappingSchema")));
        result.put("endpoints", toolConnectorEndpoints(descriptor));
        return result;
    }

    public static String toolConnectorDefinitionDigest(Map<String, Object> descriptor) {
        return LynxusCanonicalJson.sha256ValueDigest(toolConnectorDefinitionDigestInput(descriptor));
    }

    public static String toolConnectorDefinitionCanonicalJson(Map<String, Object> descriptor) {
        return LynxusCanonicalJson.canonicalizeValue(toolConnectorDefinitionDigestInput(descriptor));
    }

    public static byte[] toolConnectorDefinitionCanonicalBytes(Map<String, Object> descriptor) {
        return LynxusCanonicalJson.canonicalValueBytes(toolConnectorDefinitionDigestInput(descriptor));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> channelProviderOutbound(Map<String, Object> descriptor) {
        Object rawOutbound = descriptor.get("outbound");
        Map<String, Object> outbound = rawOutbound instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", outbound.get("mode"));
        result.put("requiresIdempotentFinalDelivery", Boolean.TRUE.equals(outbound.get("requiresIdempotentFinalDelivery")));
        result.put("supportsDraftUpdate", Boolean.TRUE.equals(outbound.get("supportsDraftUpdate")));
        result.put("supportsFinalDelivery", Boolean.TRUE.equals(outbound.get("supportsFinalDelivery")));
        result.put("supportsTyping", Boolean.TRUE.equals(outbound.get("supportsTyping")));
        return result;
    }

    private static Map<String, Object> channelProviderEndpoints(Map<String, Object> descriptor) {
        Map<String, Object> descriptorEndpoints = endpoints(descriptor);
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("runJob", descriptorEndpoints.get("runJob"));
        endpoints.put("createCredential", descriptorEndpoints.get("createCredential"));
        endpoints.put("rotateCredential", descriptorEndpoints.get("rotateCredential"));
        endpoints.put("revokeCredential", descriptorEndpoints.get("revokeCredential"));
        endpoints.put("validateCredential", descriptorEndpoints.get("validateCredential"));
        return endpoints;
    }

    private static Map<String, Object> toolConnectorEndpoints(Map<String, Object> descriptor) {
        Map<String, Object> descriptorEndpoints = endpoints(descriptor);
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("invoke", descriptorEndpoints.get("invoke"));
        endpoints.put("createCredential", descriptorEndpoints.get("createCredential"));
        endpoints.put("rotateCredential", descriptorEndpoints.get("rotateCredential"));
        endpoints.put("revokeCredential", descriptorEndpoints.get("revokeCredential"));
        endpoints.put("validateCredential", descriptorEndpoints.get("validateCredential"));
        return endpoints;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> endpoints(Map<String, Object> descriptor) {
        Object endpoints = descriptor.get("endpoints");
        if (endpoints instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return (List<Map<String, Object>>) list;
    }

    private static Object validationOnlySchema(Object value) {
        return validationOnlySchema(value, false);
    }

    @SuppressWarnings("unchecked")
    private static Object validationOnlySchema(Object value, boolean schemaMapEntries) {
        if (value instanceof List<?> list) {
            return list.stream().map(item -> validationOnlySchema(item, false)).toList();
        }
        if (!(value instanceof Map<?, ?> map)) {
            return value;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) map).entrySet()) {
            String key = entry.getKey();
            if (!schemaMapEntries && SCHEMA_ANNOTATION_KEYWORDS.contains(key)) {
                continue;
            }
            result.put(key, validationOnlySchema(entry.getValue(), !schemaMapEntries && SCHEMA_MAP_KEYWORDS.contains(key)));
        }
        return result;
    }
}
