package com.agentyard.worker.session;

import io.temporal.common.RetryOptions;
import java.time.Duration;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

final class TemporalPolicySupport {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private TemporalPolicySupport() {
    }

    static Duration parseTimeoutPolicy(String rawPolicy, Duration fallback) {
        if (rawPolicy == null || rawPolicy.isBlank()) {
            return fallback;
        }
        String trimmed = rawPolicy.trim();
        try {
            if (trimmed.startsWith("PT") || trimmed.startsWith("P")) {
                return Duration.parse(trimmed);
            }
            return Duration.ofSeconds(Long.parseLong(trimmed));
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    static RetryOptions parseRetryPolicy(String rawPolicy, RetryOptions fallback, ObjectMapper objectMapper) {
        if (rawPolicy == null || rawPolicy.isBlank()) {
            return fallback;
        }
        String trimmed = rawPolicy.trim();
        if ("NONE".equalsIgnoreCase(trimmed)) {
            return RetryOptions.newBuilder().setMaximumAttempts(1).build();
        }
        if (trimmed.toUpperCase().startsWith("EXPONENTIAL_BACKOFF")) {
            int maximumAttempts = parseAttemptsSuffix(trimmed, 3);
            return RetryOptions.newBuilder()
                .setMaximumAttempts(maximumAttempts)
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumInterval(Duration.ofSeconds(10))
                .setBackoffCoefficient(2.0)
                .build();
        }
        if (trimmed.chars().allMatch(Character::isDigit)) {
            return RetryOptions.newBuilder().setMaximumAttempts(Integer.parseInt(trimmed)).build();
        }
        if (trimmed.startsWith("{")) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(trimmed, OBJECT_MAP);
                RetryOptions.Builder builder = RetryOptions.newBuilder();
                builder.setMaximumAttempts(intValue(parsed.get("maximumAttempts"), fallback.getMaximumAttempts()));
                builder.setBackoffCoefficient(doubleValue(parsed.get("backoffCoefficient"), 2.0));
                builder.setInitialInterval(Duration.ofSeconds(intValue(parsed.get("initialIntervalSeconds"), 1)));
                builder.setMaximumInterval(Duration.ofSeconds(intValue(parsed.get("maximumIntervalSeconds"), 10)));
                return builder.build();
            } catch (JacksonException error) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int parseAttemptsSuffix(String rawPolicy, int defaultValue) {
        int separator = rawPolicy.indexOf(':');
        if (separator < 0 || separator == rawPolicy.length() - 1) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(rawPolicy.substring(separator + 1).trim());
        } catch (RuntimeException error) {
            return defaultValue;
        }
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Integer.parseInt(string.trim());
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Double.parseDouble(string.trim());
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
