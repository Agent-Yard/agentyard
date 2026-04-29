package com.lynxus.channel.gateway.extension;

import com.lynxus.extension.sdk.common.LynxusCanonicalJson;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.Map;

final class ChannelProviderProfileConfigValidator {
    private static final String CONFIG_SCHEMA_ID = "https://lynxus.local/schemas/channel-provider-profile-config.schema.json";

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
            String schemaJson = LynxusCanonicalJson.canonicalizeValue(schema == null ? Map.of() : schema);
            String valueJson = LynxusCanonicalJson.canonicalizeValue(value == null ? Map.of() : value);
            SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(Map.of(CONFIG_SCHEMA_ID, schemaJson))
            );
            Schema objectSchema = schemaRegistry.getSchema(SchemaLocation.of(CONFIG_SCHEMA_ID));
            if (!objectSchema.validate(valueJson, InputFormat.JSON).isEmpty()) {
                throw new IllegalArgumentException("channel profile config does not satisfy provider configSchema");
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("channel profile config does not satisfy provider configSchema", error);
        }
    }
}
