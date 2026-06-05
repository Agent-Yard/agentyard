package com.agentyard.worker.session;

import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

final class JsonSchemaValidator {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    JsonSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void validate(String schemaJson, Object value, String fieldName) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return;
        }
        Map<String, Object> schema;
        try {
            schema = objectMapper.readValue(schemaJson, OBJECT_MAP);
        } catch (JacksonException error) {
            throw new IllegalStateException("invalid json schema for " + fieldName, error);
        }
        validateNode(schema, value, fieldName);
    }

    @SuppressWarnings("unchecked")
    private void validateNode(Map<String, Object> schema, Object value, String path) {
        if (schema == null || schema.isEmpty()) {
            return;
        }
        validateType(schema.get("type"), value, path);
        validateEnum(schema.get("enum"), value, path);
        if (value instanceof Map<?, ?> mapValue) {
            validateObject(schema, (Map<String, Object>) mapValue, path);
        } else if (value instanceof List<?> listValue) {
            validateArray(schema.get("items"), listValue, path);
        } else if (value instanceof Number numberValue) {
            validateNumber(schema, numberValue, path);
        }
    }

    private void validateType(Object rawType, Object value, String path) {
        if (!(rawType instanceof String type) || type.isBlank()) {
            return;
        }
        boolean matches = switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> true;
        };
        if (!matches) {
            throw new IllegalStateException(path + " must be " + type);
        }
    }

    private void validateEnum(Object rawEnum, Object value, String path) {
        if (!(rawEnum instanceof List<?> enumValues) || value == null) {
            return;
        }
        for (Object candidate : enumValues) {
            if (String.valueOf(candidate).equals(String.valueOf(value))) {
                return;
            }
        }
        throw new IllegalStateException(path + " must match one of the configured enum values");
    }

    @SuppressWarnings("unchecked")
    private void validateObject(Map<String, Object> schema, Map<String, Object> value, String path) {
        Object rawRequired = schema.get("required");
        if (rawRequired instanceof List<?> required) {
            for (Object item : required) {
                String field = String.valueOf(item);
                if (!value.containsKey(field)) {
                    throw new IllegalStateException(path + "." + field + " is required");
                }
            }
        }
        Map<String, Object> properties = Map.of();
        Object rawProperties = schema.get("properties");
        if (rawProperties instanceof Map<?, ?> propertyMap) {
            properties = (Map<String, Object>) propertyMap;
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (value.containsKey(entry.getKey()) && entry.getValue() instanceof Map<?, ?> childSchema) {
                    validateNode((Map<String, Object>) childSchema, value.get(entry.getKey()), path + "." + entry.getKey());
                }
            }
        }
        Object rawAdditionalProperties = schema.get("additionalProperties");
        if (rawAdditionalProperties instanceof Boolean additionalProperties && !additionalProperties) {
            for (String key : value.keySet()) {
                if (!properties.containsKey(key)) {
                    throw new IllegalStateException(path + "." + key + " is not allowed");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateArray(Object rawItems, List<?> value, String path) {
        if (!(rawItems instanceof Map<?, ?> items)) {
            return;
        }
        for (int index = 0; index < value.size(); index += 1) {
            validateNode((Map<String, Object>) items, value.get(index), path + "[" + index + "]");
        }
    }

    private void validateNumber(Map<String, Object> schema, Number value, String path) {
        Object rawMinimum = schema.get("minimum");
        if (rawMinimum instanceof Number minimum && value.doubleValue() < minimum.doubleValue()) {
            throw new IllegalStateException(path + " must be >= " + minimum.doubleValue());
        }
        Object rawMaximum = schema.get("maximum");
        if (rawMaximum instanceof Number maximum && value.doubleValue() > maximum.doubleValue()) {
            throw new IllegalStateException(path + " must be <= " + maximum.doubleValue());
        }
    }
}
