package com.agentyard.channel.gateway.channel;

import com.agentyard.contracts.channel.ChannelContracts.ChannelInboundTurnMessageStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelInboundTurnStatus;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelAttachment;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelMessageRole;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelMessageSender;
import java.time.Instant;
import java.util.List;
import java.util.Map;

record ChannelInboundTurnAudit(
    String turnId,
    String channelProfileId,
    String providerType,
    String dedupKey,
    String externalConversationId,
    String externalUserId,
    Map<String, Object> normalizedPayload,
    Map<String, Object> rawPayload,
    Map<String, Object> traceContext,
    Map<String, Object> metadata,
    ChannelInboundTurnStatus status,
    String sessionId,
    Instant createdAt,
    Instant updatedAt
) {
}

record ChannelInboundTurnMessageAudit(
    String turnId,
    String channelProfileId,
    String externalConversationId,
    int requestIndex,
    String externalEventId,
    String externalMessageId,
    Instant occurredAt,
    NormalizedChannelMessageRole role,
    NormalizedChannelMessageSender sender,
    String messageType,
    String text,
    List<NormalizedChannelAttachment> attachments,
    Map<String, Object> metadata,
    ChannelInboundTurnMessageStatus status,
    String sessionMessageId,
    String duplicateOfTurnId,
    Instant createdAt,
    Instant updatedAt
) {
}

record ChannelInboundMessageDedupeAudit(
    String channelProfileId,
    String externalConversationId,
    String externalMessageId,
    String firstTurnId,
    int firstRequestIndex,
    String sessionId,
    String sessionMessageId,
    Instant createdAt
) {
}
