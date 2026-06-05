package com.agentyard.extension.sdk.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentYardExtensionHttp {
    private AgentYardExtensionHttp() {}

    public static Map<String, String> serviceLevelHeaders(String authorization, String registrationId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(AgentYardExtensionHeaders.AUTHORIZATION, requiredHeader(authorization, AgentYardExtensionHeaders.AUTHORIZATION));
        if (registrationId != null && !registrationId.isBlank()) {
            headers.put(AgentYardExtensionHeaders.REGISTRATION_ID, registrationId);
        }
        return Collections.unmodifiableMap(headers);
    }

    public static Map<String, String> descriptorLevelHeaders(
        String authorization,
        String registrationId,
        DescriptorType descriptorType,
        String descriptorId,
        String traceId,
        String requestId,
        String idempotencyKey
    ) {
        if (descriptorType == null) {
            throw new IllegalArgumentException(AgentYardExtensionHeaders.DESCRIPTOR_TYPE + " must not be null");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(AgentYardExtensionHeaders.AUTHORIZATION, requiredHeader(authorization, AgentYardExtensionHeaders.AUTHORIZATION));
        headers.put(AgentYardExtensionHeaders.REGISTRATION_ID, requiredHeader(registrationId, AgentYardExtensionHeaders.REGISTRATION_ID));
        headers.put(AgentYardExtensionHeaders.DESCRIPTOR_TYPE, descriptorType.wireValue());
        headers.put(AgentYardExtensionHeaders.DESCRIPTOR_ID, requiredHeader(descriptorId, AgentYardExtensionHeaders.DESCRIPTOR_ID));
        headers.put(AgentYardExtensionHeaders.TRACE_ID, requiredHeader(traceId, AgentYardExtensionHeaders.TRACE_ID));
        headers.put(AgentYardExtensionHeaders.REQUEST_ID, requiredHeader(requestId, AgentYardExtensionHeaders.REQUEST_ID));
        headers.put(AgentYardExtensionHeaders.IDEMPOTENCY_KEY, requiredHeader(idempotencyKey, AgentYardExtensionHeaders.IDEMPOTENCY_KEY));
        return Collections.unmodifiableMap(headers);
    }

    public static Map<String, String> credentialLifecycleHeaders(String authorization, String traceId, String requestId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(AgentYardExtensionHeaders.AUTHORIZATION, requiredHeader(authorization, AgentYardExtensionHeaders.AUTHORIZATION));
        headers.put(AgentYardExtensionHeaders.TRACE_ID, requiredHeader(traceId, AgentYardExtensionHeaders.TRACE_ID));
        headers.put(AgentYardExtensionHeaders.REQUEST_ID, requiredHeader(requestId, AgentYardExtensionHeaders.REQUEST_ID));
        return Collections.unmodifiableMap(headers);
    }

    public static String manifestUrl(String baseUrl) {
        String cleanBaseUrl = requiredHeader(baseUrl, "baseUrl");
        if (cleanBaseUrl.endsWith("/")) {
            return cleanBaseUrl.substring(0, cleanBaseUrl.length() - 1) + AgentYardExtensionProtocol.EXTENSION_MANIFEST_PATH;
        }
        return cleanBaseUrl + AgentYardExtensionProtocol.EXTENSION_MANIFEST_PATH;
    }

    public static ExtensionError parseNon2xxExtensionError(int statusCode, String rawBody) {
        if (statusCode >= 200 && statusCode <= 299) {
            throw new ExtensionErrorParseException("ExtensionError response requires a non-2xx status code");
        }
        return ExtensionError.parseJson(rawBody);
    }

    private static String requiredHeader(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
