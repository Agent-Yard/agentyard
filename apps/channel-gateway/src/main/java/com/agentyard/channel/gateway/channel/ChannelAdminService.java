package com.agentyard.channel.gateway.channel;

import com.agentyard.channel.gateway.extension.ChannelProviderDescriptor;
import com.agentyard.channel.gateway.extension.ChannelProviderJobConfigValidator;
import com.agentyard.channel.gateway.extension.ChannelProviderJobDefinition;
import com.agentyard.channel.gateway.extension.ChannelProviderRegistry;
import com.agentyard.channel.gateway.shared.ConflictException;
import com.agentyard.channel.gateway.shared.UnprocessableEntityException;
import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileAccountSnapshot;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.agentyard.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshot;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshotPage;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameCheckpoint;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderJobConfig;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderJobConfigWriteRequest;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderJobRun;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderJobScheduleConfig;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderJobScheduleType;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderJobScheduleWriteConfig;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderJobStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelTemplateBinding;
import com.agentyard.contracts.channel.ChannelContracts.ChannelTemplateBindingKey;
import com.agentyard.contracts.channel.ChannelContracts.ChannelTemplateBindingWriteRequest;
import com.agentyard.contracts.channel.ChannelContracts.CreateChannelProfileInternalRequest;
import com.agentyard.contracts.channel.ChannelContracts.UpdateChannelProfileInternalRequest;
import java.time.ZoneId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ChannelAdminService {
    private static final int DEFAULT_JOB_TIMEOUT_SECONDS = 60;

    private final ChannelAdminRepository repository;
    private final ChannelProviderRegistry channelProviderRegistry;

    public ChannelAdminService(ChannelAdminRepository repository, ChannelProviderRegistry channelProviderRegistry) {
        this.repository = repository;
        this.channelProviderRegistry = channelProviderRegistry;
    }

    public List<ChannelGatewayProfile> listProfiles() {
        return repository.listProfiles();
    }

    public List<ChannelGatewayProfile> listProfilesByProvider(String providerType) {
        return repository.listProfilesByProvider(requireText(providerType, "channelProfile.providerType"));
    }

    public ChannelGatewayProfile createProfile(CreateChannelProfileInternalRequest request) {
        Instant now = Instant.now();
        ChannelProfileAccountSnapshot accountSnapshot = request.accountSnapshot();
        ChannelProviderDescriptor descriptor = channelProviderRegistry.requireProvider(request.providerType());
        Map<String, Object> config = channelProviderRegistry.materializeAndValidateProfileConfig(
            descriptor.providerType(),
            request.config()
        );
        ChannelGatewayProfile profile = new ChannelGatewayProfile(
            nextId("channel-profile"),
            descriptor.providerType(),
            requireText(request.displayName(), "channelProfile.displayName"),
            request.status() == null ? ChannelProfileStatus.ACTIVE : request.status(),
            request.inboundEnabled() == null || request.inboundEnabled(),
            config,
            request.assistantBinding(),
            accountSnapshot == null ? null : normalizeOptionalText(accountSnapshot.accountId()),
            accountSnapshot != null && hasText(accountSnapshot.externalSecretRef()),
            1,
            now,
            now
        );
        repository.createProfile(profile, accountSnapshot == null ? null : normalizeOptionalText(accountSnapshot.externalSecretRef()));
        return profile;
    }

    public ChannelGatewayProfile getProfile(String channelProfileId) {
        return repository.findProfile(channelProfileId)
            .orElseThrow(() -> new NoSuchElementException("channel profile not found: " + channelProfileId));
    }

    public ChannelGatewayProfile updateProfile(String channelProfileId, UpdateChannelProfileInternalRequest request) {
        ChannelGatewayProfile existing = getProfile(channelProfileId);
        long expectedRevision = requireExpectedRevision(request.expectedRevision());
        ChannelProfileAccountSnapshot accountSnapshot = request.accountSnapshot();
        ChannelProviderDescriptor descriptor = channelProviderRegistry.requireProvider(request.providerType());
        Map<String, Object> config = channelProviderRegistry.materializeAndValidateProfileConfig(
            descriptor.providerType(),
            request.config()
        );
        ChannelGatewayProfile updated = new ChannelGatewayProfile(
            existing.id(),
            descriptor.providerType(),
            requireText(request.displayName(), "channelProfile.displayName"),
            request.status() == null ? existing.status() : request.status(),
            request.inboundEnabled() == null ? existing.inboundEnabled() : request.inboundEnabled(),
            config,
            request.assistantBinding() == null ? existing.assistantBinding() : request.assistantBinding(),
            accountSnapshot == null ? null : normalizeOptionalText(accountSnapshot.accountId()),
            accountSnapshot != null && hasText(accountSnapshot.externalSecretRef()),
            existing.revision() + 1,
            existing.createdAt(),
            Instant.now()
        );
        boolean updatedRow = repository.updateProfile(
            updated,
            expectedRevision,
            accountSnapshot == null ? null : normalizeOptionalText(accountSnapshot.externalSecretRef())
        );
        if (!updatedRow) {
            throw new ConflictException("channel profile revision conflict: " + channelProfileId);
        }
        return updated;
    }

    public ChannelGatewayProfile deleteProfile(String channelProfileId, Long expectedRevisionValue) {
        ChannelGatewayProfile existing = getProfile(channelProfileId);
        long expectedRevision = requireExpectedRevision(expectedRevisionValue);
        ChannelGatewayProfile disabled = new ChannelGatewayProfile(
            existing.id(),
            existing.providerType(),
            existing.displayName(),
            ChannelProfileStatus.INACTIVE,
            existing.inboundEnabled(),
            existing.config(),
            existing.assistantBinding(),
            existing.accountId(),
            existing.hasExternalSecretRef(),
            existing.revision() + 1,
            existing.createdAt(),
            Instant.now()
        );
        boolean updatedRow = repository.disableProfile(
            disabled.id(),
            expectedRevision,
            disabled.revision(),
            disabled.updatedAt()
        );
        if (!updatedRow) {
            throw new ConflictException("channel profile revision conflict: " + channelProfileId);
        }
        return disabled;
    }

    public List<ChannelConversationBinding> listBindings(String channelProfileId) {
        getProfile(channelProfileId);
        return repository.listBindings(channelProfileId);
    }

    public ChannelOutboundBindingSnapshotPage listBindingSnapshots(Instant updatedAfter, String cursor, Integer limit) {
        return repository.listBindingSnapshots(updatedAfter, cursor, limit == null ? 500 : limit);
    }

    public List<ChannelOutboundBindingSnapshot> listBindingSnapshotsByProfile(String channelProfileId) {
        getProfile(channelProfileId);
        return repository.listBindingSnapshotsByProfile(channelProfileId);
    }

    public ChannelConversationBinding getBindingBySession(String sessionId) {
        String normalizedSessionId = requireText(sessionId, "channelBinding.sessionId");
        return repository.findBindingBySessionId(normalizedSessionId)
            .orElseThrow(() -> new NoSuchElementException("channel conversation binding not found for session: " + normalizedSessionId));
    }

    public ChannelOutboundBindingSnapshot getActiveBindingSnapshotBySession(String sessionId) {
        String normalizedSessionId = requireText(sessionId, "channelBinding.sessionId");
        List<ChannelOutboundBindingSnapshot> snapshots = repository.listActiveBindingSnapshotsBySessionId(normalizedSessionId);
        if (snapshots.size() > 1) {
            throw new ConflictException("duplicate ACTIVE channel conversation bindings for session: " + normalizedSessionId);
        }
        return snapshots.stream()
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("channel conversation binding not found for session: " + normalizedSessionId));
    }

    public List<ChannelInboundEvent> listInboundEvents(String channelProfileId) {
        getProfile(channelProfileId);
        return repository.listInboundEvents(channelProfileId);
    }

    public List<ChannelOutboundFrameCheckpoint> listOutboundFinalCheckpoints(String channelProfileId) {
        getProfile(channelProfileId);
        return repository.listOutboundFinalCheckpoints(channelProfileId);
    }

    public List<ChannelTemplateBinding> listTemplateBindings(String channelProfileId) {
        getProfile(channelProfileId);
        return repository.listTemplateBindings(channelProfileId);
    }

    public ChannelTemplateBinding upsertTemplateBinding(
        String channelProfileId,
        String assistantId,
        String messageType,
        String messageSubtype,
        String messageVersion,
        ChannelTemplateBindingWriteRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("templateBinding is required");
        }
        getProfile(channelProfileId);
        ChannelTemplateBindingKey key = templateBindingKey(channelProfileId, assistantId, messageType, messageSubtype, messageVersion);
        ChannelTemplateBinding existing = repository.findTemplateBinding(key).orElse(null);
        Instant now = Instant.now();
        String externalTemplateId = requireText(request.externalTemplateId(), "templateBinding.externalTemplateId");
        Map<String, Object> variableSchema = request.variableSchema();
        ChannelTemplateBindingVariableSchemaValidator.validate(variableSchema);

        if (existing == null) {
            Long expectedRevision = request.expectedRevision();
            if (expectedRevision != null && expectedRevision > 0) {
                throw new ConflictException("channel template binding revision conflict: " + templateBindingLabel(key));
            }
            ChannelTemplateBinding created = new ChannelTemplateBinding(
                nextId("channel-template-binding"),
                key.channelProfileId(),
                key.assistantId(),
                key.messageType(),
                key.messageSubtype(),
                key.messageVersion(),
                externalTemplateId,
                normalizeOptionalText(request.externalTemplateVersion()),
                request.enabled() == null || request.enabled(),
                variableSchema,
                normalizeDisplayName(request.displayName(), externalTemplateId),
                normalizeOptionalText(request.externalEditUrl()),
                1,
                now,
                now
            );
            repository.createTemplateBinding(created);
            return created;
        }

        long expectedRevision = requireExpectedRevision(request.expectedRevision(), "templateBinding.expectedRevision");
        ChannelTemplateBinding updated = new ChannelTemplateBinding(
            existing.id(),
            existing.channelProfileId(),
            existing.assistantId(),
            existing.messageType(),
            existing.messageSubtype(),
            existing.messageVersion(),
            externalTemplateId,
            normalizeOptionalText(request.externalTemplateVersion()),
            request.enabled() == null || request.enabled(),
            variableSchema,
            normalizeDisplayName(request.displayName(), externalTemplateId),
            normalizeOptionalText(request.externalEditUrl()),
            existing.revision() + 1,
            existing.createdAt(),
            now
        );
        if (!repository.updateTemplateBinding(updated, expectedRevision)) {
            throw new ConflictException("channel template binding revision conflict: " + templateBindingLabel(key));
        }
        return updated;
    }

    public ChannelTemplateBinding deleteTemplateBinding(
        String channelProfileId,
        String assistantId,
        String messageType,
        String messageSubtype,
        String messageVersion,
        Long expectedRevisionValue
    ) {
        getProfile(channelProfileId);
        ChannelTemplateBindingKey key = templateBindingKey(channelProfileId, assistantId, messageType, messageSubtype, messageVersion);
        ChannelTemplateBinding existing = repository.findTemplateBinding(key)
            .orElseThrow(() -> new NoSuchElementException("channel template binding not found: " + templateBindingLabel(key)));
        long expectedRevision = requireExpectedRevision(expectedRevisionValue, "templateBinding.expectedRevision");
        Instant now = Instant.now();
        if (!repository.disableTemplateBinding(existing.id(), expectedRevision, existing.revision() + 1, now)) {
            throw new ConflictException("channel template binding revision conflict: " + templateBindingLabel(key));
        }
        return new ChannelTemplateBinding(
            existing.id(),
            existing.channelProfileId(),
            existing.assistantId(),
            existing.messageType(),
            existing.messageSubtype(),
            existing.messageVersion(),
            existing.externalTemplateId(),
            existing.externalTemplateVersion(),
            false,
            existing.variableSchema(),
            existing.displayName(),
            existing.externalEditUrl(),
            existing.revision() + 1,
            existing.createdAt(),
            now
        );
    }

    public List<ChannelProviderJobConfig> listJobs(String channelProfileId) {
        ChannelGatewayProfile profile = getProfile(channelProfileId);
        ChannelProviderDescriptor descriptor = channelProviderRegistry.requireProvider(profile.providerType());
        return repository.listJobs(channelProfileId, descriptor.jobDefinitionsByType().keySet().stream().toList());
    }

    public ChannelProviderJobConfig upsertJob(
        String channelProfileId,
        String jobType,
        ChannelProviderJobConfigWriteRequest request
    ) {
        ChannelGatewayProfile profile = getProfile(channelProfileId);
        ChannelProviderJobDefinition jobDefinition = requireJobDefinition(profile.providerType(), jobType);
        ChannelProviderJobConfig existing = repository.findJob(channelProfileId, jobDefinition.jobType()).orElse(null);
        if (existing != null && existing.status() == ChannelProviderJobStatus.RUNNING) {
            throw new ConflictException("channel provider job is running: " + jobDefinition.jobType());
        }

        Instant now = Instant.now();
        ChannelProviderJobScheduleConfig scheduleConfig = normalizeScheduleConfig(jobDefinition, request == null ? null : request.scheduleConfig());
        ChannelProviderJobStatus status = enabled(request == null ? null : request.scheduleConfig(), jobDefinition)
            ? ChannelProviderJobStatus.ACTIVE
            : ChannelProviderJobStatus.DISABLED;
        Instant nextRunAt = status == ChannelProviderJobStatus.ACTIVE
            ? ChannelProviderJobScheduleCalculator.nextRunAt(scheduleConfig, now)
            : null;

        if (existing == null) {
            Long expectedRevision = request == null ? null : request.expectedRevision();
            if (expectedRevision != null && expectedRevision > 0) {
                throw new ConflictException("channel provider job revision conflict: " + jobDefinition.jobType());
            }
            ChannelProviderJobConfig created = new ChannelProviderJobConfig(
                nextId("channel-job"),
                jobDefinition.jobType(),
                status,
                scheduleConfig,
                nextRunAt,
                null,
                null,
                null,
                0,
                1,
                now,
                now
            );
            repository.createJob(channelProfileId, created);
            return created;
        }

        long expectedRevision = requireExpectedRevision(request == null ? null : request.expectedRevision(), "channelProviderJob.expectedRevision");
        ChannelProviderJobConfig updated = new ChannelProviderJobConfig(
            existing.jobId(),
            existing.jobType(),
            status,
            scheduleConfig,
            nextRunAt,
            existing.lastRunAt(),
            existing.lastSuccessAt(),
            existing.lastError(),
            existing.failureCount(),
            existing.revision() + 1,
            existing.createdAt(),
            now
        );
        if (!repository.updateJob(updated, expectedRevision)) {
            throw new ConflictException("channel provider job revision conflict: " + jobDefinition.jobType());
        }
        return updated;
    }

    public ChannelProviderJobConfig deleteJob(String channelProfileId, String jobType, Long expectedRevisionValue) {
        ChannelGatewayProfile profile = getProfile(channelProfileId);
        ChannelProviderJobDefinition jobDefinition = requireJobDefinition(profile.providerType(), jobType);
        ChannelProviderJobConfig existing = repository.findJob(channelProfileId, jobDefinition.jobType())
            .orElseThrow(() -> new NoSuchElementException("channel provider job not found: " + jobDefinition.jobType()));
        if (existing.status() == ChannelProviderJobStatus.RUNNING) {
            throw new ConflictException("channel provider job is running: " + jobDefinition.jobType());
        }
        long expectedRevision = requireExpectedRevision(expectedRevisionValue, "channelProviderJob.expectedRevision");
        ChannelProviderJobConfig disabled = new ChannelProviderJobConfig(
            existing.jobId(),
            existing.jobType(),
            ChannelProviderJobStatus.DISABLED,
            existing.scheduleConfig(),
            null,
            existing.lastRunAt(),
            existing.lastSuccessAt(),
            existing.lastError(),
            existing.failureCount(),
            existing.revision() + 1,
            existing.createdAt(),
            Instant.now()
        );
        if (!repository.disableJob(disabled.jobId(), expectedRevision, disabled.revision(), disabled.updatedAt())) {
            throw new ConflictException("channel provider job revision conflict: " + jobDefinition.jobType());
        }
        return disabled;
    }

    public List<ChannelProviderJobRun> listJobRuns(String channelProfileId, String jobType) {
        ChannelGatewayProfile profile = getProfile(channelProfileId);
        ChannelProviderJobDefinition jobDefinition = requireJobDefinition(profile.providerType(), jobType);
        ChannelProviderJobConfig job = repository.findJob(channelProfileId, jobDefinition.jobType())
            .orElseThrow(() -> new NoSuchElementException("channel provider job not found: " + jobDefinition.jobType()));
        return repository.listJobRuns(job.jobId());
    }

    public void saveInboundEvent(ChannelInboundEvent event) {
        repository.saveInboundEvent(event);
    }

    public ChannelInboundEvent findInboundEventByDedupKey(String dedupKey) {
        return repository.findInboundEventByDedupKey(dedupKey).orElse(null);
    }

    public String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static long requireExpectedRevision(Long expectedRevision) {
        return requireExpectedRevision(expectedRevision, "channelProfile.expectedRevision");
    }

    private static long requireExpectedRevision(Long expectedRevision, String field) {
        if (expectedRevision == null || expectedRevision < 1) {
            throw new IllegalArgumentException(field + " is required");
        }
        return expectedRevision;
    }

    private static String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeDisplayName(String value, String externalTemplateId) {
        String normalized = normalizeOptionalText(value);
        return normalized == null ? externalTemplateId : normalized;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ChannelProviderJobDefinition requireJobDefinition(String providerType, String jobType) {
        ChannelProviderDescriptor descriptor = channelProviderRegistry.requireProvider(providerType);
        String normalizedJobType = requireText(jobType, "channelProviderJob.jobType");
        return descriptor.findJobDefinition(normalizedJobType)
            .orElseThrow(() -> new UnprocessableEntityException("unknown channel provider jobType: " + normalizedJobType));
    }

    private static ChannelTemplateBindingKey templateBindingKey(
        String channelProfileId,
        String assistantId,
        String messageType,
        String messageSubtype,
        String messageVersion
    ) {
        return new ChannelTemplateBindingKey(
            requireText(channelProfileId, "templateBinding.channelProfileId"),
            requireText(assistantId, "templateBinding.assistantId"),
            requireText(messageType, "templateBinding.messageType"),
            requireText(messageSubtype, "templateBinding.messageSubtype"),
            requireText(messageVersion, "templateBinding.messageVersion")
        );
    }

    private static String templateBindingLabel(ChannelTemplateBindingKey key) {
        return key.channelProfileId() + "/" + key.assistantId() + "/" + key.messageType() + "/" + key.messageSubtype() + "/" + key.messageVersion();
    }

    private ChannelProviderJobScheduleConfig normalizeScheduleConfig(
        ChannelProviderJobDefinition jobDefinition,
        ChannelProviderJobScheduleWriteConfig writeConfig
    ) {
        Map<String, Object> defaultSchedule = jobDefinition.defaultSchedule();
        ChannelProviderJobScheduleType scheduleType = writeConfig != null && writeConfig.scheduleType() != null
            ? writeConfig.scheduleType()
            : scheduleType(defaultSchedule.get("scheduleType"));
        if (scheduleType == null) {
            throw new UnprocessableEntityException("scheduleConfig.scheduleType is required");
        }
        Integer intervalSeconds = writeConfig != null && writeConfig.intervalSeconds() != null
            ? writeConfig.intervalSeconds()
            : integerValue(defaultSchedule.get("intervalSeconds"));
        String cronExpression = writeConfig != null && hasText(writeConfig.cronExpression())
            ? writeConfig.cronExpression().trim()
            : stringValue(defaultSchedule.get("cronExpression"));
        String timezone = writeConfig != null && hasText(writeConfig.timezone())
            ? writeConfig.timezone().trim()
            : stringValue(defaultSchedule.get("timezone"));
        Integer jobTimeoutSeconds = writeConfig != null && writeConfig.jobTimeoutSeconds() != null
            ? writeConfig.jobTimeoutSeconds()
            : integerValue(defaultSchedule.get("jobTimeoutSeconds"));
        Map<String, Object> jobConfig = writeConfig != null && writeConfig.jobConfig() != null
            ? writeConfig.jobConfig()
            : objectValue(defaultSchedule.get("jobConfig"));

        timezone = hasText(timezone) ? timezone : "UTC";
        jobTimeoutSeconds = jobTimeoutSeconds == null ? timeoutDefault(jobDefinition) : jobTimeoutSeconds;
        validateScheduleFields(scheduleType, intervalSeconds, cronExpression, timezone, jobTimeoutSeconds);

        ChannelProviderJobScheduleConfig normalized = switch (scheduleType) {
            case INTERVAL -> new ChannelProviderJobScheduleConfig(
                scheduleType,
                intervalSeconds,
                null,
                timezone,
                jobTimeoutSeconds,
                jobConfig
            );
            case CRON -> new ChannelProviderJobScheduleConfig(
                scheduleType,
                null,
                normalizeCronExpression(cronExpression),
                timezone,
                jobTimeoutSeconds,
                jobConfig
            );
            case MANUAL -> new ChannelProviderJobScheduleConfig(
                scheduleType,
                null,
                null,
                timezone,
                jobTimeoutSeconds,
                jobConfig
            );
        };
        try {
            ChannelProviderJobConfigValidator.validate(jobDefinition.jobConfigSchema(), normalized.jobConfig());
        } catch (IllegalArgumentException error) {
            throw new UnprocessableEntityException(error.getMessage());
        }
        return normalized;
    }

    private static boolean enabled(ChannelProviderJobScheduleWriteConfig writeConfig, ChannelProviderJobDefinition jobDefinition) {
        if (writeConfig == null) {
            return Boolean.TRUE.equals(jobDefinition.defaultEnabled());
        }
        if (writeConfig.enabled() == null) {
            throw new UnprocessableEntityException("scheduleConfig.enabled is required");
        }
        return writeConfig.enabled();
    }

    private static void validateScheduleFields(
        ChannelProviderJobScheduleType scheduleType,
        Integer intervalSeconds,
        String cronExpression,
        String timezone,
        Integer jobTimeoutSeconds
    ) {
        if (scheduleType == ChannelProviderJobScheduleType.INTERVAL && (intervalSeconds == null || intervalSeconds < 1)) {
            throw new UnprocessableEntityException("scheduleConfig.intervalSeconds is required for INTERVAL schedules");
        }
        if (scheduleType == ChannelProviderJobScheduleType.CRON && !hasText(cronExpression)) {
            throw new UnprocessableEntityException("scheduleConfig.cronExpression is required for CRON schedules");
        }
        if (jobTimeoutSeconds == null || jobTimeoutSeconds < 1) {
            throw new UnprocessableEntityException("scheduleConfig.jobTimeoutSeconds must be positive");
        }
        try {
            ZoneId.of(timezone);
        } catch (RuntimeException error) {
            throw new UnprocessableEntityException("scheduleConfig.timezone is invalid");
        }
    }

    private static ChannelProviderJobScheduleType scheduleType(Object value) {
        if (value instanceof ChannelProviderJobScheduleType type) {
            return type;
        }
        if (value instanceof String string && hasText(string)) {
            try {
                return ChannelProviderJobScheduleType.valueOf(string.trim());
            } catch (IllegalArgumentException ignored) {
                throw new UnprocessableEntityException("scheduleConfig.scheduleType is invalid");
            }
        }
        return null;
    }

    private static Integer integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string : null;
    }

    private static Map<String, Object> objectValue(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    result.put(key, entry.getValue());
                }
            }
            return result;
        }
        return Map.of();
    }

    private static int timeoutDefault(ChannelProviderJobDefinition jobDefinition) {
        Integer defaultTimeout = jobDefinition.defaultJobTimeoutSeconds();
        return defaultTimeout == null || defaultTimeout < 1 ? DEFAULT_JOB_TIMEOUT_SECONDS : defaultTimeout;
    }

    private static String normalizeCronExpression(String cronExpression) {
        String normalized = cronExpression.trim().replaceAll("\\s+", " ");
        String[] parts = normalized.split(" ");
        if (parts.length == 5) {
            normalized = "0 " + normalized;
        }
        try {
            org.springframework.scheduling.support.CronExpression.parse(normalized);
            return normalized;
        } catch (RuntimeException error) {
            throw new UnprocessableEntityException("scheduleConfig.cronExpression is invalid");
        }
    }
}
