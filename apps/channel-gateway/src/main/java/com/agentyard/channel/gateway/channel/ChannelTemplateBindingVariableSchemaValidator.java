package com.agentyard.channel.gateway.channel;

import java.util.Locale;
import java.util.Map;

final class ChannelTemplateBindingVariableSchemaValidator {
    private ChannelTemplateBindingVariableSchemaValidator() {
    }

    static void validate(Map<String, Object> variableSchema) {
        rejectSecretMaterial(variableSchema == null ? Map.of() : variableSchema, "variableSchema");
    }

    private static void rejectSecretMaterial(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() instanceof String stringKey ? stringKey : String.valueOf(entry.getKey());
                if (isSecretMarker(key) || ("secret".equals(key) && Boolean.TRUE.equals(entry.getValue()))) {
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
                index += 1;
            }
        }
    }

    private static boolean isSecretMarker(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        String compact = normalized.replaceAll("[^a-z0-9]", "");
        return compact.equals("externalsecretref")
            || compact.equals("password")
            || compact.equals("apikey")
            || compact.equals("accesstoken")
            || compact.equals("refreshtoken")
            || compact.equals("privatekey")
            || compact.equals("webhooksigningsecret")
            || compact.contains("credential")
            || normalized.contains("secret");
    }
}
