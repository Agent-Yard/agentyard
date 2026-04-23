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

    public enum ChannelProviderType {
        FEISHU
    }

    public enum ChannelAccountStatus {
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

    public record ChannelAccount(
        String id,
        ChannelProviderType providerType,
        String name,
        ChannelAccountStatus status,
        Map<String, Object> config,
        Instant createdAt,
        Instant updatedAt
    ) {
        public ChannelAccount {
            config = immutableObjectMap(config);
        }
    }

    public record ChannelConversationBinding(
        String id,
        String channelAccountId,
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
        String channelAccountId,
        ChannelProviderType providerType,
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
        String channelAccountId,
        ChannelProviderType providerType,
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

    public record CreateChannelAccountRequest(
        ChannelProviderType providerType,
        String name,
        ChannelAccountStatus status,
        Map<String, Object> config
    ) {
        public CreateChannelAccountRequest {
            config = immutableObjectMap(config);
        }
    }

    public record UpdateChannelAccountRequest(
        String name,
        ChannelAccountStatus status,
        Map<String, Object> config
    ) {
        public UpdateChannelAccountRequest {
            config = immutableObjectMap(config);
        }
    }
}
