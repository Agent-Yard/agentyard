package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEventResult;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundTurn;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundTurnResult;

public interface ChannelInboundSessionDispatchObserver {
    default void afterDispatchSucceeded(
        NormalizedChannelInboundTurn turn,
        ChannelInboundTurnIngestResult ingestResult,
        NormalizedChannelInboundTurnResult dispatchResult
    ) {
    }

    default void afterDispatchFailed(
        NormalizedChannelInboundTurn turn,
        ChannelInboundTurnIngestResult ingestResult,
        RuntimeException error
    ) {
    }

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
