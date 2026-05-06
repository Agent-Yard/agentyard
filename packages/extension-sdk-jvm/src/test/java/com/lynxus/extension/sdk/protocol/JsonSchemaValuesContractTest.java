package com.lynxus.extension.sdk.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.extension.sdk.validation.JsonSchemaValues;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class JsonSchemaValuesContractTest {
    private static final String SCHEMA_ID = "https://lynxus.local/schemas/contract-test-object.schema.json";

    @Test
    void validatesObjectValuesAgainstDraft202012Schema() {
        Map<String, Object> schema = Map.of(
            "type",
            "object",
            "required",
            List.of("name"),
            "additionalProperties",
            false,
            "properties",
            Map.of("name", Map.of("type", "string"))
        );

        assertTrue(JsonSchemaValues.valid(schema, Map.of("name", "demo"), SCHEMA_ID));
        assertFalse(JsonSchemaValues.valid(schema, Map.of("name", 42), SCHEMA_ID));
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaValues.validate(schema, Map.of("extra", true), SCHEMA_ID));
    }
}
