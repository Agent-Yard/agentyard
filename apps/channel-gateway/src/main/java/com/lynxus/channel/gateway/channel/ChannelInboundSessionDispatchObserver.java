package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEventResult;

public interface ChannelInboundSessionDispatchObserver {
    default void afterDispatchSucceeded(
        NormalizedChannelInboundEvent event,
        NormalizedChannelInboundEventResult ingestResult,
        ChannelInboundSessionDispatcher.ChannelInboundSessionDispatchResult dispatchResult
    ) {
    }

    default void afterDispatchFailed(
        NormalizedChannelInboundEvent event,
        NormalizedChannelInboundEventResult ingestResult,
        RuntimeException error
    ) {
    }
}
