package com.agentyard.channel.gateway.extension;

import com.agentyard.extension.sdk.validation.JsonSchemaValues;
import java.util.Locale;
import java.util.Map;

public final class ChannelProviderJobConfigValidator {
    private static final String JOB_CONFIG_SCHEMA_ID = "https://agentyard.local/schemas/channel-provider-job-config.schema.json";

    private ChannelProviderJobConfigValidator() {}

    public static void validate(Map<String, Object> schema, Map<String, Object> value) {
        rejectSecretMaterial(value, "scheduleConfig.jobConfig");
        try {
            JsonSchemaValues.validate(schema, value, JOB_CONFIG_SCHEMA_ID);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("provider job config does not satisfy jobConfigSchema", error);
        }
    }

    private static void rejectSecretMaterial(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() instanceof String stringKey ? stringKey : String.valueOf(entry.getKey());
                if (isSecretMarker(key)) {
                    throw new IllegalArgumentException(path + "." + key + " must not contain secret material");
                }
                rejectSecretMaterial(entry.getValue(), path + "." + key);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object item : iterable) {
                rejectSecretMaterial(item, path + "[" + index + "]");
                index++;
            }
        }
    }

    private static boolean isSecretMarker(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.equals("externalsecretref")
            || normalized.equals("password")
            || normalized.equals("apikey")
            || normalized.equals("access_token")
            || normalized.equals("accesstoken")
            || normalized.equals("refresh_token")
            || normalized.equals("refreshtoken")
            || normalized.equals("private_key")
            || normalized.equals("privatekey")
            || normalized.equals("webhook_signing_secret")
            || normalized.equals("webhooksigningsecret")
            || normalized.contains("secret");
    }
}
