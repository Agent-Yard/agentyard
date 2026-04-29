package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.extension.ChannelProviderDescriptor;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundPayload;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundRequest;

record ChannelOutboundInvocation(
    ChannelProviderDescriptor descriptor,
    ChannelOutboundProfileSnapshot profile,
    String idempotencyKey,
    TraceIds traceIds,
    ChannelOutboundPayload payload
) {
    ChannelOutboundRequest requestBody() {
        return new ChannelOutboundRequest(
            descriptor.providerType(),
            profile.channelProfileId(),
            profile.config(),
            blankToNull(profile.externalSecretRef()),
            idempotencyKey,
            traceIds.traceContext(),
            payload
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
