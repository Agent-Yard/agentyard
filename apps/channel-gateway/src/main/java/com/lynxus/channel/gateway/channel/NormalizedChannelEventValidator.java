package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelEventType;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEvent;
import java.util.EnumSet;
import java.util.regex.Pattern;

final class NormalizedChannelEventValidator {
    private static final Pattern DEDUP_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final EnumSet<NormalizedChannelEventType> MESSAGE_EVENTS = EnumSet.of(
        NormalizedChannelEventType.MESSAGE_RECEIVED,
        NormalizedChannelEventType.FILE_RECEIVED
    );

    private NormalizedChannelEventValidator() {
    }

    static void validateEnvelope(NormalizedChannelInboundEvent event) {
        requireText(event.providerType(), "normalizedEvent.providerType");
        requireText(event.channelProfileId(), "normalizedEvent.channelProfileId");
        if (event.eventType() == null) {
            throw new IllegalArgumentException("normalizedEvent.eventType is required");
        }
        validateDedupKey(event.dedupKey());
        if (event.traceContext() == null || !hasText(event.traceContext().traceparent())) {
            throw new IllegalArgumentException("normalizedEvent.traceContext.traceparent is required");
        }
    }

    static void validateEventTypeMatrix(NormalizedChannelInboundEvent event) {
        validateEnvelope(event);
        switch (event.eventType()) {
            case MESSAGE_RECEIVED -> {
                requireConversation(event);
                requireSender(event);
                requireMessage(event);
                requireTopLevelExternalConversationId(event);
                requireTopLevelExternalMessageId(event);
            }
            case FILE_RECEIVED -> {
                requireConversation(event);
                requireSender(event);
                requireMessage(event);
                if (event.message().attachments().isEmpty()) {
                    throw new IllegalArgumentException("normalizedEvent.message.attachments is required for FILE_RECEIVED");
                }
                requireTopLevelExternalConversationId(event);
                requireTopLevelExternalMessageId(event);
            }
            case MESSAGE_UPDATED -> {
                requireConversation(event);
                requireMessage(event);
                requireTopLevelExternalConversationId(event);
                requireTopLevelExternalMessageId(event);
            }
            case MESSAGE_DELETED -> {
                requireConversation(event);
                requireTopLevelExternalConversationId(event);
                requireTopLevelExternalMessageId(event);
            }
            case CONVERSATION_UPDATED -> {
                requireConversation(event);
                forbidMessage(event);
                requireTopLevelExternalConversationId(event);
            }
            case MEMBER_JOINED, MEMBER_LEFT -> {
                requireConversation(event);
                requireSender(event);
                forbidMessage(event);
                requireTopLevelExternalConversationId(event);
                requireTopLevelExternalUserId(event);
            }
            case REACTION_ADDED -> {
                requireConversation(event);
                requireSender(event);
                requireTopLevelExternalConversationId(event);
                requireTopLevelExternalMessageId(event);
                requireTopLevelExternalUserId(event);
            }
            case WEBHOOK_VERIFIED -> {
                forbidSender(event);
                forbidMessage(event);
            }
            case UNKNOWN -> {
                // UNKNOWN is accepted as a stored fallback and intentionally does not trigger strong business actions.
            }
        }
        validateNestedIdConsistency(event);
    }

    static boolean triggersSessionBinding(NormalizedChannelInboundEvent event) {
        return event.eventType() != null && MESSAGE_EVENTS.contains(event.eventType());
    }

    static void validateDedupKey(String dedupKey) {
        if (!hasText(dedupKey) || !DEDUP_KEY_PATTERN.matcher(dedupKey).matches()) {
            throw new IllegalArgumentException("normalizedEvent.dedupKey must match [A-Za-z0-9._:-]{1,128}");
        }
    }

    private static void validateNestedIdConsistency(NormalizedChannelInboundEvent event) {
        if (event.conversation() != null && hasText(event.conversation().externalConversationId()) && hasText(event.externalConversationId())
            && !event.conversation().externalConversationId().equals(event.externalConversationId())) {
            throw new IllegalArgumentException("normalizedEvent.conversation.externalConversationId must equal externalConversationId");
        }
        if (event.sender() != null && hasText(event.sender().externalUserId()) && hasText(event.externalUserId())
            && !event.sender().externalUserId().equals(event.externalUserId())) {
            throw new IllegalArgumentException("normalizedEvent.sender.externalUserId must equal externalUserId");
        }
        if (event.message() != null && hasText(event.message().externalMessageId()) && hasText(event.externalMessageId())
            && !event.message().externalMessageId().equals(event.externalMessageId())) {
            throw new IllegalArgumentException("normalizedEvent.message.externalMessageId must equal externalMessageId");
        }
    }

    private static void requireConversation(NormalizedChannelInboundEvent event) {
        if (event.conversation() == null) {
            throw new IllegalArgumentException("normalizedEvent.conversation is required for " + event.eventType());
        }
    }

    private static void requireSender(NormalizedChannelInboundEvent event) {
        if (event.sender() == null) {
            throw new IllegalArgumentException("normalizedEvent.sender is required for " + event.eventType());
        }
    }

    private static void forbidSender(NormalizedChannelInboundEvent event) {
        if (event.sender() != null) {
            throw new IllegalArgumentException("normalizedEvent.sender is forbidden for " + event.eventType());
        }
    }

    private static void requireMessage(NormalizedChannelInboundEvent event) {
        if (event.message() == null) {
            throw new IllegalArgumentException("normalizedEvent.message is required for " + event.eventType());
        }
    }

    private static void forbidMessage(NormalizedChannelInboundEvent event) {
        if (event.message() != null) {
            throw new IllegalArgumentException("normalizedEvent.message is forbidden for " + event.eventType());
        }
    }

    private static void requireTopLevelExternalConversationId(NormalizedChannelInboundEvent event) {
        requireText(event.externalConversationId(), "normalizedEvent.externalConversationId");
    }

    private static void requireTopLevelExternalMessageId(NormalizedChannelInboundEvent event) {
        requireText(event.externalMessageId(), "normalizedEvent.externalMessageId");
    }

    private static void requireTopLevelExternalUserId(NormalizedChannelInboundEvent event) {
        requireText(event.externalUserId(), "normalizedEvent.externalUserId");
    }

    static String requireText(String value, String field) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
