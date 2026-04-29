package com.lynxus.channel.gateway.extension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public record ChannelProviderDescriptor(
    String providerType,
    Map<String, Object> descriptor,
    String definitionDigest,
    Map<String, Object> configSchema,
    Map<String, Object> defaultConfig,
    Map<String, ChannelProviderJobDefinition> jobDefinitionsByType
) {
    public ChannelProviderDescriptor {
        if (providerType == null || providerType.isBlank()) {
            throw new IllegalArgumentException("providerType is required");
        }
        providerType = providerType.trim();
        descriptor = immutableObject(descriptor);
        configSchema = immutableObject(configSchema);
        defaultConfig = immutableObject(defaultConfig);
        jobDefinitionsByType = jobDefinitionsByType == null || jobDefinitionsByType.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new TreeMap<>(jobDefinitionsByType));
    }

    public Optional<ChannelProviderJobDefinition> findJobDefinition(String jobType) {
        if (jobType == null || jobType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(jobDefinitionsByType.get(jobType.trim()));
    }

    private static Map<String, Object> immutableObject(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    @SuppressWarnings("unchecked")
    static Map<String, ChannelProviderJobDefinition> jobDefinitions(Map<String, Object> descriptor) {
        Object rawJobs = descriptor.get("jobDefinitions");
        if (!(rawJobs instanceof List<?> jobs) || jobs.isEmpty()) {
            return Map.of();
        }
        Map<String, ChannelProviderJobDefinition> result = new TreeMap<>();
        for (Object rawJob : jobs) {
            if (!(rawJob instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> job = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    job.put(key, entry.getValue());
                }
            }
            String jobType = job.get("jobType") instanceof String value ? value : null;
            if (jobType == null || jobType.isBlank()) {
                continue;
            }
            result.putIfAbsent(jobType.trim(), new ChannelProviderJobDefinition(
                jobType,
                objectValue(job.get("jobConfigSchema")),
                objectValue(job.get("defaultSchedule")),
                job.get("defaultEnabled") instanceof Boolean enabled ? enabled : null,
                job.get("defaultJobTimeoutSeconds") instanceof Number timeout ? timeout.intValue() : null
            ));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectValue(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    result.put(key, entry.getValue());
                }
            }
            return result;
        }
        return Map.of();
    }
}
