package com.lynxus.channel.gateway.channel;

public record ChannelOutboundExtensionHeaders(
    String registrationId,
    String descriptorType,
    String descriptorId
) {
}
