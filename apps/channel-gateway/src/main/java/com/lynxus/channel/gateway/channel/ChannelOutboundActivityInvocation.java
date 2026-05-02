package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.extension.ChannelProviderDescriptor;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderActivityPayload;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderActivityRequest;

record ChannelOutboundActivityInvocation(
    ChannelProviderDescriptor descriptor,
    ChannelOutboundProfileSnapshot profile,
    ChannelOutboundActivityRequest request,
    TraceIds traceIds
) {
    ChannelProviderActivityRequest requestBody() {
        return new ChannelProviderActivityRequest(
            descriptor.providerType(),
            profile.channelProfileId(),
            profile.config(),
            profile.externalSecretRef(),
            request.idempotencyKey(),
            traceIds.traceContext(),
            new ChannelProviderActivityPayload(
                request.externalConversationId(),
                request.sessionId(),
                request.turnId(),
                request.frameId(),
                request.activityType(),
                request.payload()
            )
        );
    }
}
