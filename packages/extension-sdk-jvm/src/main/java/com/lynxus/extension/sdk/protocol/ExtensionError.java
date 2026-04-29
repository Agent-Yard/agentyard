package com.lynxus.extension.sdk.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record ExtensionError(
    String errorCode,
    String message,
    ExtensionErrorCategory category,
    boolean retryable,
    Map<String, Object> details
) {
    private static final Set<String> ALLOWED_FIELDS = Set.of("errorCode", "message", "category", "retryable", "details");

    public ExtensionError {
        if (errorCode == null || errorCode.isEmpty() || errorCode.length() > 128) {
            throw new ExtensionErrorParseException("ExtensionError.errorCode must be 1-128 characters");
        }
        if (message == null || message.isEmpty() || message.length() > 4096) {
            throw new ExtensionErrorParseException("ExtensionError.message must be 1-4096 characters");
        }
        if (category == null) {
            throw new ExtensionErrorParseException("ExtensionError.category is required");
        }
        if (details == null) {
            throw new ExtensionErrorParseException("ExtensionError.details is required");
        }
        details = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static ExtensionError parseJson(String rawJson) {
        try {
            return parseObject(JsonDocuments.parseObject(rawJson));
        } catch (ExtensionErrorParseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ExtensionErrorParseException("Invalid ExtensionError JSON", exception);
        }
    }

    public static ExtensionError parseObject(Map<String, Object> value) {
        for (String field : value.keySet()) {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new ExtensionErrorParseException("ExtensionError has unknown field " + field);
            }
        }

        String errorCode = requiredString(value, "errorCode", 1, 128);
        String message = requiredString(value, "message", 1, 4096);
        String rawCategory = requiredString(value, "category", 1, 128);
        ExtensionErrorCategory category = ExtensionErrorCategory.fromWireValue(rawCategory)
            .orElseThrow(() -> new ExtensionErrorParseException("Unknown ExtensionError.category " + rawCategory));

        Object retryableValue = required(value, "retryable");
        if (!(retryableValue instanceof Boolean retryable)) {
            throw new ExtensionErrorParseException("ExtensionError.retryable must be boolean");
        }

        Object detailsValue = required(value, "details");
        if (!(detailsValue instanceof Map<?, ?> details)) {
            throw new ExtensionErrorParseException("ExtensionError.details must be an object");
        }

        return new ExtensionError(errorCode, message, category, retryable, stringObjectMap(details));
    }

    private static Object required(Map<String, Object> value, String field) {
        if (!value.containsKey(field)) {
            throw new ExtensionErrorParseException("ExtensionError." + field + " is required");
        }
        return value.get(field);
    }

    private static String requiredString(Map<String, Object> value, String field, int minLength, int maxLength) {
        Object raw = required(value, field);
        if (!(raw instanceof String stringValue)) {
            throw new ExtensionErrorParseException("ExtensionError." + field + " must be string");
        }
        if (stringValue.length() < minLength || stringValue.length() > maxLength) {
            throw new ExtensionErrorParseException(
                "ExtensionError." + field + " must be " + minLength + "-" + maxLength + " characters"
            );
        }
        return stringValue;
    }

    private static Map<String, Object> stringObjectMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new ExtensionErrorParseException("ExtensionError.details keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }
}
