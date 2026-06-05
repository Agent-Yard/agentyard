package com.agentyard.channel.gateway.channel;

import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelInboundTurn;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelInboundTurnResult;

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
}
