package com.agentyard.channel.gateway.channel;

public record ChannelOutboundExtensionHeaders(
    String registrationId,
    String descriptorType,
    String descriptorId
) {
}
