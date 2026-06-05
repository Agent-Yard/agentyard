package com.agentyard.channel.gateway.extension;

import com.agentyard.extension.sdk.validation.JsonSchemaValues;
import java.util.Map;

final class ChannelProviderProfileConfigValidator {
    private static final String CONFIG_SCHEMA_ID = "https://agentyard.local/schemas/channel-provider-profile-config.schema.json";

    private ChannelProviderProfileConfigValidator() {}

    static boolean valid(Map<String, Object> schema, Map<String, Object> value) {
        try {
            validate(schema, value);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    static void validate(Map<String, Object> schema, Map<String, Object> value) {
        try {
            JsonSchemaValues.validate(schema, value, CONFIG_SCHEMA_ID);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("channel profile config does not satisfy provider configSchema", error);
        }
    }
}
