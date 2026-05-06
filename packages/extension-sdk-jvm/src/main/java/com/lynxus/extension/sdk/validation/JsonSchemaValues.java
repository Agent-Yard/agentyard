package com.lynxus.extension.sdk.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.Map;
import java.util.Objects;

public final class JsonSchemaValues {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonSchemaValues() {}

    public static boolean valid(Map<String, Object> schema, Map<String, Object> value, String schemaId) {
        try {
            validate(schema, value, schemaId);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static void validate(Map<String, Object> schema, Map<String, Object> value, String schemaId) {
        String normalizedSchemaId = requireSchemaId(schemaId);
        try {
            String schemaJson = JSON.writeValueAsString(schema == null ? Map.of() : schema);
            String valueJson = JSON.writeValueAsString(value == null ? Map.of() : value);
            SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(Map.of(normalizedSchemaId, schemaJson))
            );
            Schema objectSchema = schemaRegistry.getSchema(SchemaLocation.of(normalizedSchemaId));
            if (!objectSchema.validate(valueJson, InputFormat.JSON).isEmpty()) {
                throw new IllegalArgumentException("JSON value does not satisfy schema");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("JSON value must be serializable", exception);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("JSON schema could not be evaluated", exception);
        }
    }

    private static String requireSchemaId(String schemaId) {
        String normalized = Objects.requireNonNull(schemaId, "schemaId must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("schemaId must not be blank");
        }
        return normalized;
    }
}
