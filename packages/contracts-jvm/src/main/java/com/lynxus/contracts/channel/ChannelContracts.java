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
        ARCHIVED
    }

    public enum ChannelInboundEventStatus {
        RECEIVED,
        REJECTED
    }

    public enum ChannelOutboundDeliveryStatus {
        PENDING,
        SENT,
        FAILED
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

    public record ChannelOutboundDelivery(
        String deliveryId,
        String channelProfileId,
        String providerType,
        String sessionId,
        String sessionMessageId,
        String externalConversationId,
        Map<String, Object> payload,
        ChannelOutboundDeliveryStatus status,
        int attemptCount,
        String lastError,
        Instant createdAt,
        Instant updatedAt
    ) {
        public ChannelOutboundDelivery {
            payload = immutableObjectMap(payload);
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
