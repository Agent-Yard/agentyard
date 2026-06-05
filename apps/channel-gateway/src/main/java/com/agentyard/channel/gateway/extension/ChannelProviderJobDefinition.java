package com.agentyard.channel.gateway.extension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ChannelProviderJobDefinition(
    String jobType,
    Map<String, Object> jobConfigSchema,
    Map<String, Object> defaultSchedule,
    Boolean defaultEnabled,
    Integer defaultJobTimeoutSeconds
) {
    public ChannelProviderJobDefinition {
        if (jobType == null || jobType.isBlank()) {
            throw new IllegalArgumentException("provider jobType is required");
        }
        jobType = jobType.trim();
        jobConfigSchema = immutableObject(jobConfigSchema);
        defaultSchedule = immutableObject(defaultSchedule);
    }

    private static Map<String, Object> immutableObject(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
