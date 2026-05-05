package com.lynxus.contracts.channel;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChannelContracts {
    private ChannelContracts() {
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, Object> nullableImmutableObjectMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        return immutableObjectMap(source);
    }

    private static Map<String, Object> requiredImmutableObjectMap(Map<String, Object> source, String field) {
        if (source == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return immutableObjectMap(source);
    }

    public enum ChannelProfileStatus {
        ACTIVE,
        INACTIVE
    }

    public enum ChannelConversationBindingStatus {
        ACTIVE,
        ARCHIVED,
        DISABLED,
        DELETED,
        DETACHED,
        INACTIVE
    }

    public enum ChannelInboundEventStatus {
        RECEIVED,
        REJECTED
    }

    public static final String CHANNEL_OUTBOUND_FRAME_PROTOCOL = "lynxus.channel-outbound-frame.v1";
    public static final String CHANNEL_OUTBOUND_FRAME_ACK_PROTOCOL = "lynxus.channel-outbound-frame-ack.v1";

    public enum ChannelOutboundFrameKind {
        TYPING_START,
        TYPING_STOP,
        DRAFT_UPDATE,
        DRAFT_COMPLETE,
        DRAFT_DISCARD,
        FINAL_DELIVERY
    }

    public enum ChannelOutboundConsumerKind {
        REMOTE_EXTENSION,
        GATEWAY_NATIVE
    }

    public enum ChannelProviderJobScheduleType {
        INTERVAL,
        CRON,
        MANUAL
    }

    public enum ChannelProviderJobStatus {
        ACTIVE,
        RUNNING,
        PAUSED,
        DISABLED
    }

    public enum ChannelProviderJobRunStatus {
        RUNNING,
        SUCCEEDED,
        FAILED,
        TIMED_OUT
    }

    public enum NormalizedChannelEventType {
        MESSAGE_RECEIVED,
        MESSAGE_UPDATED,
        MESSAGE_DELETED,
        CONVERSATION_UPDATED,
        MEMBER_JOINED,
        MEMBER_LEFT,
        REACTION_ADDED,
        FILE_RECEIVED,
        WEBHOOK_VERIFIED,
        UNKNOWN
    }

    public record ChannelProfile(
        String id,
        String providerType,
        String displayName,
        ChannelProfileStatus status,
        boolean inboundEnabled,
        Map<String, Object> config,
        ChannelAssistantBinding assistantBinding,
        String accountId,
        boolean hasExternalSecretRef,
        long revision,
        ChannelProfileIntegrationAccountSummary integrationAccount,
        Instant createdAt,
        Instant updatedAt
    ) {
        public ChannelProfile {
            config = immutableObjectMap(config);
        }
    }

    public record ChannelGatewayProfile(
        String id,
        String providerType,
        String displayName,
        ChannelProfileStatus status,
        boolean inboundEnabled,
        Map<String, Object> config,
        ChannelAssistantBinding assistantBinding,
        String accountId,
        boolean hasExternalSecretRef,
        long revision,
        Instant createdAt,
        Instant updatedAt
    ) {
        public ChannelGatewayProfile {
            config = immutableObjectMap(config);
        }
    }

    public record ChannelAssistantBinding(String assistantId, String scenarioId) {
    }

    public record ChannelProfileIntegrationAccountSummary(
        String id,
        String name,
        String status,
        String credentialStatus,
        boolean credentialConfigured,
        String availabilityHardBlock,
        List<String> risks
    ) {
        public ChannelProfileIntegrationAccountSummary {
            risks = risks == null || risks.isEmpty() ? List.of() : List.copyOf(risks);
        }
    }

    public record ChannelProfileAccountSnapshot(
        String accountId,
        String externalSecretRef
    ) {
    }

    public record NormalizedChannelConversation(
        String externalConversationId,
        String type,
        String title,
        Map<String, Object> metadata
    ) {
        public NormalizedChannelConversation {
            metadata = immutableObjectMap(metadata);
        }
    }

    public record NormalizedChannelSender(
        String externalUserId,
        String displayName,
        Map<String, Object> metadata
    ) {
        public NormalizedChannelSender {
            metadata = immutableObjectMap(metadata);
        }
    }

    public record NormalizedChannelAttachment(
        String externalAttachmentId,
        String externalFileId,
        String fileName,
        String mimeType,
        String url,
        Long sizeBytes,
        Map<String, Object> metadata
    ) {
        public NormalizedChannelAttachment {
            metadata = immutableObjectMap(metadata);
        }
    }

    public record NormalizedChannelMessage(
        String externalMessageId,
        String type,
        String text,
        List<NormalizedChannelAttachment> attachments,
        Map<String, Object> metadata
    ) {
        public NormalizedChannelMessage {
            attachments = attachments == null || attachments.isEmpty() ? List.of() : List.copyOf(attachments);
            metadata = immutableObjectMap(metadata);
        }
    }

    public record NormalizedChannelTraceContext(
        String traceparent,
        String tracestate
    ) {
    }

    public record NormalizedChannelInboundEvent(
        String providerType,
        String channelProfileId,
        NormalizedChannelEventType eventType,
        String dedupKey,
        String externalEventId,
        String externalConversationId,
        String externalMessageId,
        String externalUserId,
        Instant occurredAt,
        NormalizedChannelConversation conversation,
        NormalizedChannelSender sender,
        NormalizedChannelMessage message,
        Map<String, Object> normalizedPayload,
        Map<String, Object> rawPayload,
        NormalizedChannelTraceContext traceContext,
        Map<String, Object> metadata
    ) {
        public NormalizedChannelInboundEvent {
            normalizedPayload = requiredImmutableObjectMap(normalizedPayload, "normalizedPayload");
            rawPayload = immutableObjectMap(rawPayload);
            metadata = immutableObjectMap(metadata);
        }
    }

    public record NormalizedChannelInboundEventResult(
        String eventId,
        boolean duplicate
    ) {
    }

    public record ChannelConversationBinding(
        String id,
        String channelProfileId,
        String externalConversationId,
        String externalUserId,
        String assistantId,
        String customerId,
        String sessionId,
        ChannelConversationBindingStatus status,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
    ) {
        public ChannelConversationBinding {
            metadata = immutableObjectMap(metadata);
        }
    }

    public record ChannelOutboundBindingSnapshot(
        String bindingId,
        String sessionId,
        String channelProfileId,
        String providerType,
        String externalConversationId,
        String externalUserId,
        String assistantId,
        String customerId,
        String bindingStatus,
        ChannelProfileStatus profileStatus,
        long profileRevision,
        Instant bindingUpdatedAt,
        Instant profileUpdatedAt,
        Instant updatedAt
    ) {
        public ChannelOutboundBindingSnapshot {
            requireText(bindingId, "bindingId");
            requireText(channelProfileId, "channelProfileId");
            requireText(providerType, "providerType");
            requireText(bindingStatus, "bindingStatus");
            if (profileStatus == null) {
                throw new IllegalArgumentException("profileStatus is required");
            }
            if (profileRevision <= 0) {
                throw new IllegalArgumentException("profileRevision must be positive");
            }
            if (updatedAt == null) {
                throw new IllegalArgumentException("updatedAt is required");
            }
        }
    }

    public record ChannelOutboundBindingSnapshotPage(
        List<ChannelOutboundBindingSnapshot> items,
        String nextCursor
    ) {
        public ChannelOutboundBindingSnapshotPage {
            items = items == null || items.isEmpty() ? List.of() : List.copyOf(items);
        }
    }

    public record ChannelInboundEvent(
        String eventId,
        String channelProfileId,
        String providerType,
        String eventType,
        String externalEventId,
        String externalConversationId,
        String externalMessageId,
        String dedupKey,
        Map<String, Object> rawPayload,
        Map<String, Object> normalizedPayload,
        ChannelInboundEventStatus status,
        Instant createdAt,
        Instant updatedAt
    ) {
        public ChannelInboundEvent {
            rawPayload = immutableObjectMap(rawPayload);
            normalizedPayload = immutableObjectMap(normalizedPayload);
        }
    }

    public record ChannelOutboundResolvedTemplate(
        String messageType,
        String messageSubtype,
        String messageVersion,
        String externalTemplateId,
        String externalTemplateVersion
    ) {
    }

    public record ChannelProviderOutboundCapability(
        String mode,
        boolean supportsTyping,
        boolean supportsDraftUpdate,
        boolean supportsFinalDelivery,
        boolean supportsCredentialRef,
        boolean requiresIdempotentFinalDelivery
    ) {
        public static final String FRAME_STREAM_MODE = "FRAME_STREAM";

        public ChannelProviderOutboundCapability {
            if (!FRAME_STREAM_MODE.equals(mode)) {
                throw new IllegalArgumentException("outbound.mode must be FRAME_STREAM");
            }
            if (!supportsFinalDelivery) {
                throw new IllegalArgumentException("outbound.supportsFinalDelivery must be true");
            }
            if (!requiresIdempotentFinalDelivery) {
                throw new IllegalArgumentException("outbound.requiresIdempotentFinalDelivery must be true");
            }
        }
    }

    public record ChannelOutboundFrame(
        String protocol,
        String frameId,
        String channelProfileId,
        String providerType,
        String assistantId,
        String externalConversationId,
        String sessionId,
        String turnId,
        String turnExecutionId,
        Long sourceSeq,
        Long finalSequence,
        ChannelOutboundFrameKind kind,
        Instant occurredAt,
        String idempotencyKey,
        String credentialRef,
        Map<String, Object> payload,
        NormalizedChannelTraceContext traceContext
    ) {
        public ChannelOutboundFrame {
            if (!CHANNEL_OUTBOUND_FRAME_PROTOCOL.equals(protocol)) {
                throw new IllegalArgumentException("protocol must be " + CHANNEL_OUTBOUND_FRAME_PROTOCOL);
            }
            requireText(frameId, "frameId");
            requireText(channelProfileId, "channelProfileId");
            requireText(providerType, "providerType");
            requireText(externalConversationId, "externalConversationId");
            requireText(sessionId, "sessionId");
            if (kind == null) {
                throw new IllegalArgumentException("kind is required");
            }
            if (occurredAt == null) {
                throw new IllegalArgumentException("occurredAt is required");
            }
            requireText(idempotencyKey, "idempotencyKey");
            payload = requiredImmutableObjectMap(payload, "payload");
            validateOutboundFrameSemantics(kind, turnId, turnExecutionId, sourceSeq, finalSequence, payload);
        }
    }

    public record ChannelOutboundFinalSequence(long value) {
        public ChannelOutboundFinalSequence {
            if (value <= 0) {
                throw new IllegalArgumentException("finalSequence must be positive");
            }
        }
    }

    public record ChannelOutboundProfileConsumer(
        String channelProfileId,
        String providerType,
        ChannelOutboundConsumerKind consumerKind,
        String consumerId,
        String registrationId
    ) {
        public ChannelOutboundProfileConsumer {
            requireText(channelProfileId, "channelProfileId");
            requireText(providerType, "providerType");
            if (consumerKind == null) {
                throw new IllegalArgumentException("consumerKind is required");
            }
            requireText(consumerId, "consumerId");
        }
    }

    public record ChannelOutboundFrameCheckpoint(
        ChannelOutboundProfileConsumer consumer,
        Long lastAckedFinalSequence,
        String lastAckedFinalFrameId,
        String lastAckedSessionId,
        String lastAckedSessionMessageId,
        Instant lastAckedAt
    ) {
        public ChannelOutboundFrameCheckpoint {
            if (consumer == null) {
                throw new IllegalArgumentException("consumer is required");
            }
            if (lastAckedFinalSequence != null && lastAckedFinalSequence <= 0) {
                throw new IllegalArgumentException("lastAckedFinalSequence must be positive");
            }
        }
    }

    public record ChannelOutboundFrameStreamCursor(
        String streamCursor,
        Long lastAckedFinalSequence,
        String lastAckedSessionId,
        String lastAckedSessionMessageId,
        Integer maxFinalReplayFrames
    ) {
        public ChannelOutboundFrameStreamCursor {
            if (lastAckedFinalSequence != null && lastAckedFinalSequence <= 0) {
                throw new IllegalArgumentException("lastAckedFinalSequence must be positive");
            }
            if (maxFinalReplayFrames != null && maxFinalReplayFrames <= 0) {
                throw new IllegalArgumentException("maxFinalReplayFrames must be positive");
            }
        }
    }

    public record ChannelOutboundFrameAck(
        String protocol,
        String channelProfileId,
        String providerType,
        String frameId,
        long finalSequence,
        String sessionId,
        String sessionMessageId,
        Map<String, Object> metadata
    ) {
        public ChannelOutboundFrameAck {
            if (!CHANNEL_OUTBOUND_FRAME_ACK_PROTOCOL.equals(protocol)) {
                throw new IllegalArgumentException("protocol must be " + CHANNEL_OUTBOUND_FRAME_ACK_PROTOCOL);
            }
            requireText(channelProfileId, "channelProfileId");
            requireText(providerType, "providerType");
            requireText(frameId, "frameId");
            if (finalSequence <= 0) {
                throw new IllegalArgumentException("finalSequence must be positive");
            }
            requireText(sessionId, "sessionId");
            requireText(sessionMessageId, "sessionMessageId");
            metadata = immutableObjectMap(metadata);
            forbidAckMetadataFields(metadata);
        }
    }

    public record ChannelOutboundBindingSnapshotRefreshRequest(
        String channelProfileId,
        String bindingId,
        String sessionId,
        String reason,
        Instant bindingUpdatedAt
    ) {
        public ChannelOutboundBindingSnapshotRefreshRequest {
            if ((channelProfileId == null || channelProfileId.isBlank())
                && (bindingId == null || bindingId.isBlank())
                && (sessionId == null || sessionId.isBlank())) {
                throw new IllegalArgumentException("channelProfileId, bindingId, or sessionId is required");
            }
            requireText(reason, "reason");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static void validateOutboundFrameSemantics(
        ChannelOutboundFrameKind kind,
        String turnId,
        String turnExecutionId,
        Long sourceSeq,
        Long finalSequence,
        Map<String, Object> payload
    ) {
        if (kind == ChannelOutboundFrameKind.FINAL_DELIVERY) {
            if (finalSequence == null || finalSequence <= 0) {
                throw new IllegalArgumentException("finalSequence is required for FINAL_DELIVERY");
            }
            requirePayloadFields(payload, "sessionMessageId", "messageSequence", "messageBlocks");
            return;
        }
        if (finalSequence != null) {
            throw new IllegalArgumentException("finalSequence is only allowed for FINAL_DELIVERY");
        }
        requireText(turnId, "turnId");
        requireText(turnExecutionId, "turnExecutionId");
        if (sourceSeq == null || sourceSeq <= 0) {
            throw new IllegalArgumentException("sourceSeq is required for transient frames");
        }
        switch (kind) {
            case TYPING_START, TYPING_STOP, DRAFT_DISCARD -> requirePayloadFields(payload, "messageId");
            case DRAFT_UPDATE -> {
                requirePayloadFields(payload, "messageId", "blockId", "blockType");
                if ("TEXT".equals(payload.get("blockType"))) {
                    requirePayloadFields(payload, "delta");
                }
            }
            case DRAFT_COMPLETE -> requirePayloadFields(payload, "messageId", "blockId", "blockType", "block");
            default -> throw new IllegalArgumentException("unsupported channel outbound frame kind");
        }
    }

    private static void requirePayloadFields(Map<String, Object> payload, String... fields) {
        for (String field : fields) {
            Object value = payload.get(field);
            if (value == null || (value instanceof String text && text.isBlank())) {
                throw new IllegalArgumentException("payload." + field + " is required");
            }
        }
    }

    private static void forbidAckMetadataFields(Map<String, Object> metadata) {
        for (String field : List.of("providerResponse", "rawProviderResponse", "credential", "credentialRef", "externalSecretRef")) {
            if (metadata.containsKey(field)) {
                throw new IllegalArgumentException("ACK metadata must not contain " + field);
            }
        }
    }

    public record ChannelTemplateBindingKey(
        String channelProfileId,
        String assistantId,
        String messageType,
        String messageSubtype,
        String messageVersion
    ) {
    }

    public record ChannelTemplateBinding(
        String id,
        String channelProfileId,
        String assistantId,
        String messageType,
        String messageSubtype,
        String messageVersion,
        String externalTemplateId,
        String externalTemplateVersion,
        boolean enabled,
        Map<String, Object> variableSchema,
        String displayName,
        String externalEditUrl,
        long revision,
        Instant createdAt,
        Instant updatedAt
    ) {
        public ChannelTemplateBinding {
            variableSchema = immutableObjectMap(variableSchema);
        }
    }

    public record ChannelTemplateBindingWriteRequest(
        String externalTemplateId,
        String externalTemplateVersion,
        Map<String, Object> variableSchema,
        String displayName,
        String externalEditUrl,
        Boolean enabled,
        Long expectedRevision
    ) {
        public ChannelTemplateBindingWriteRequest {
            variableSchema = immutableObjectMap(variableSchema);
        }
    }

    public record ResolvedChannelTemplate(
        String externalTemplateId,
        String externalTemplateVersion,
        Map<String, Object> variableSchema,
        String displayName,
        String externalEditUrl,
        long bindingRevision
    ) {
        public ResolvedChannelTemplate {
            variableSchema = immutableObjectMap(variableSchema);
        }
    }

    public record ChannelProviderJobScheduleConfig(
        ChannelProviderJobScheduleType scheduleType,
        Integer intervalSeconds,
        String cronExpression,
        String timezone,
        Integer jobTimeoutSeconds,
        Map<String, Object> jobConfig
    ) {
        public ChannelProviderJobScheduleConfig {
            jobConfig = immutableObjectMap(jobConfig);
        }
    }

    public record ChannelProviderJobScheduleWriteConfig(
        Boolean enabled,
        ChannelProviderJobScheduleType scheduleType,
        Integer intervalSeconds,
        String cronExpression,
        String timezone,
        Integer jobTimeoutSeconds,
        Map<String, Object> jobConfig
    ) {
        public ChannelProviderJobScheduleWriteConfig {
            jobConfig = immutableObjectMap(jobConfig);
        }
    }

    public record ChannelProviderJobConfig(
        String jobId,
        String jobType,
        ChannelProviderJobStatus status,
        ChannelProviderJobScheduleConfig scheduleConfig,
        Instant nextRunAt,
        Instant lastRunAt,
        Instant lastSuccessAt,
        String lastError,
        int failureCount,
        long revision,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record ChannelProviderJobConfigWriteRequest(
        ChannelProviderJobScheduleWriteConfig scheduleConfig,
        Long expectedRevision
    ) {
    }

    public record ChannelProviderJobRun(
        String id,
        String runId,
        String jobId,
        ChannelProviderJobRunStatus status,
        Instant scheduledAt,
        Instant startedAt,
        int jobTimeoutSeconds,
        Instant finishedAt,
        Long durationMs,
        String idempotencyKey,
        int attempt,
        int eventsIngested,
        String nextCursor,
        Map<String, Object> error,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
    ) {
        public ChannelProviderJobRun {
            error = immutableObjectMap(error);
            metadata = immutableObjectMap(metadata);
        }
    }

    public record ChannelRunJobPayload(
        String jobType,
        Map<String, Object> jobConfig,
        Instant scheduledAt
    ) {
        public ChannelRunJobPayload {
            jobConfig = requiredImmutableObjectMap(jobConfig, "payload.jobConfig");
        }
    }

    public record ChannelRunJobRequest(
        String providerType,
        String channelProfileId,
        Map<String, Object> config,
        String externalSecretRef,
        String idempotencyKey,
        NormalizedChannelTraceContext traceContext,
        ChannelRunJobPayload payload
    ) {
        public ChannelRunJobRequest {
            config = requiredImmutableObjectMap(config, "config");
        }
    }

    public enum ChannelRunJobResponseStatus {
        SUCCEEDED,
        NOOP
    }

    public record ChannelRunJobResponse(
        ChannelRunJobResponseStatus status,
        String nextCursor,
        List<NormalizedChannelInboundEvent> events,
        Map<String, Object> metadata
    ) {
        public ChannelRunJobResponse {
            events = events == null || events.isEmpty() ? List.of() : List.copyOf(events);
            metadata = requiredImmutableObjectMap(metadata, "metadata");
        }
    }

    public record CreateChannelProfileRequest(
        String providerType,
        String displayName,
        ChannelProfileStatus status,
        Boolean inboundEnabled,
        Map<String, Object> config,
        ChannelAssistantBinding assistantBinding,
        String integrationAccountId
    ) {
        public CreateChannelProfileRequest {
            config = immutableObjectMap(config);
        }
    }

    public record UpdateChannelProfileRequest(
        String providerType,
        String displayName,
        ChannelProfileStatus status,
        Boolean inboundEnabled,
        Map<String, Object> config,
        ChannelAssistantBinding assistantBinding,
        String integrationAccountId,
        Long expectedRevision
    ) {
        public UpdateChannelProfileRequest {
            config = immutableObjectMap(config);
        }
    }

    public record CreateChannelProfileInternalRequest(
        String providerType,
        String displayName,
        ChannelProfileStatus status,
        Boolean inboundEnabled,
        Map<String, Object> config,
        ChannelAssistantBinding assistantBinding,
        ChannelProfileAccountSnapshot accountSnapshot
    ) {
        public CreateChannelProfileInternalRequest {
            config = nullableImmutableObjectMap(config);
        }
    }

    public record UpdateChannelProfileInternalRequest(
        String providerType,
        String displayName,
        ChannelProfileStatus status,
        Boolean inboundEnabled,
        Map<String, Object> config,
        ChannelAssistantBinding assistantBinding,
        ChannelProfileAccountSnapshot accountSnapshot,
        Long expectedRevision
    ) {
        public UpdateChannelProfileInternalRequest {
            config = nullableImmutableObjectMap(config);
        }
    }
}
