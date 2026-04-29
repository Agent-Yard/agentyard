package com.lynxus.channel.gateway.channel;

import com.lynxus.extension.sdk.common.LynxusCanonicalJson;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.Map;

final class ChannelTemplateBindingDataValidator {
    private static final String VARIABLE_SCHEMA_ID = "https://lynxus.local/schemas/channel-template-binding-variables.schema.json";

    private ChannelTemplateBindingDataValidator() {
    }

    static void validate(Map<String, Object> variableSchema, Map<String, Object> data) {
        try {
            String schemaJson = LynxusCanonicalJson.canonicalizeValue(variableSchema == null ? Map.of() : variableSchema);
            String valueJson = LynxusCanonicalJson.canonicalizeValue(data == null ? Map.of() : data);
            SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(Map.of(VARIABLE_SCHEMA_ID, schemaJson))
            );
            Schema objectSchema = schemaRegistry.getSchema(SchemaLocation.of(VARIABLE_SCHEMA_ID));
            if (!objectSchema.validate(valueJson, InputFormat.JSON).isEmpty()) {
                throw new IllegalArgumentException("card message data does not satisfy template variableSchema");
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("card message data does not satisfy template variableSchema", error);
        }
    }
}
