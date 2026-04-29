package com.lynxus.contracts.channel;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    public record ChannelProfile(
        String id,
        String providerType,
        String name,
        ChannelProfileStatus status,
        Map<String, Object> config,
        Instant createdAt,
        Instant updatedAt
    ) {
        public ChannelProfile {
            config = immutableObjectMap(config);
        }
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
        String name,
        ChannelProfileStatus status,
        Map<String, Object> config
    ) {
        public CreateChannelProfileRequest {
            config = immutableObjectMap(config);
        }
    }

    public record UpdateChannelProfileRequest(
        String name,
        ChannelProfileStatus status,
        Map<String, Object> config
    ) {
        public UpdateChannelProfileRequest {
            config = immutableObjectMap(config);
        }
    }
}
