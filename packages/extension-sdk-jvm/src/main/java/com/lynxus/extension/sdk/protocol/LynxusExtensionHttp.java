package com.lynxus.extension.sdk.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LynxusExtensionHttp {
    private LynxusExtensionHttp() {}

    public static Map<String, String> serviceLevelHeaders(String authorization, String registrationId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(LynxusExtensionHeaders.AUTHORIZATION, requiredHeader(authorization, LynxusExtensionHeaders.AUTHORIZATION));
        if (registrationId != null && !registrationId.isBlank()) {
            headers.put(LynxusExtensionHeaders.REGISTRATION_ID, registrationId);
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
            throw new IllegalArgumentException(LynxusExtensionHeaders.DESCRIPTOR_TYPE + " must not be null");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(LynxusExtensionHeaders.AUTHORIZATION, requiredHeader(authorization, LynxusExtensionHeaders.AUTHORIZATION));
        headers.put(LynxusExtensionHeaders.REGISTRATION_ID, requiredHeader(registrationId, LynxusExtensionHeaders.REGISTRATION_ID));
        headers.put(LynxusExtensionHeaders.DESCRIPTOR_TYPE, descriptorType.wireValue());
        headers.put(LynxusExtensionHeaders.DESCRIPTOR_ID, requiredHeader(descriptorId, LynxusExtensionHeaders.DESCRIPTOR_ID));
        headers.put(LynxusExtensionHeaders.TRACE_ID, requiredHeader(traceId, LynxusExtensionHeaders.TRACE_ID));
        headers.put(LynxusExtensionHeaders.REQUEST_ID, requiredHeader(requestId, LynxusExtensionHeaders.REQUEST_ID));
        headers.put(LynxusExtensionHeaders.IDEMPOTENCY_KEY, requiredHeader(idempotencyKey, LynxusExtensionHeaders.IDEMPOTENCY_KEY));
        return Collections.unmodifiableMap(headers);
    }

    public static Map<String, String> credentialLifecycleHeaders(String authorization, String traceId, String requestId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(LynxusExtensionHeaders.AUTHORIZATION, requiredHeader(authorization, LynxusExtensionHeaders.AUTHORIZATION));
        headers.put(LynxusExtensionHeaders.TRACE_ID, requiredHeader(traceId, LynxusExtensionHeaders.TRACE_ID));
        headers.put(LynxusExtensionHeaders.REQUEST_ID, requiredHeader(requestId, LynxusExtensionHeaders.REQUEST_ID));
        return Collections.unmodifiableMap(headers);
    }

    public static String manifestUrl(String baseUrl) {
        String cleanBaseUrl = requiredHeader(baseUrl, "baseUrl");
        if (cleanBaseUrl.endsWith("/")) {
            return cleanBaseUrl.substring(0, cleanBaseUrl.length() - 1) + LynxusExtensionProtocol.EXTENSION_MANIFEST_PATH;
        }
        return cleanBaseUrl + LynxusExtensionProtocol.EXTENSION_MANIFEST_PATH;
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
