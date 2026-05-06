package com.lynxus.channel.gateway.channel;

import com.lynxus.extension.sdk.validation.JsonSchemaValues;
import java.util.Map;

final class ChannelTemplateBindingDataValidator {
    private static final String VARIABLE_SCHEMA_ID = "https://lynxus.local/schemas/channel-template-binding-variables.schema.json";

    private ChannelTemplateBindingDataValidator() {
    }

    static void validate(Map<String, Object> variableSchema, Map<String, Object> data) {
        try {
            JsonSchemaValues.validate(variableSchema, data, VARIABLE_SCHEMA_ID);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("card message data does not satisfy template variableSchema", error);
        }
    }
}
