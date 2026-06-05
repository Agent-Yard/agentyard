package com.agentyard.channel.gateway.channel;

public record NormalizedChannelEventHeaders(
    String registrationId,
    String descriptorType,
    String descriptorId,
    String traceId,
    String requestId,
    String idempotencyKey
) {
}
