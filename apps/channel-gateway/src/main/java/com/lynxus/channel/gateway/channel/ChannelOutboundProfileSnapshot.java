package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import java.util.Map;

record ChannelOutboundProfileSnapshot(
    String channelProfileId,
    String providerType,
    ChannelProfileStatus status,
    Map<String, Object> config,
    ChannelAssistantBinding assistantBinding,
    String externalSecretRef
) {
}
