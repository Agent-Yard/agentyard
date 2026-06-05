package com.agentyard.channel.gateway.channel;

import com.agentyard.contracts.channel.ChannelContracts.ChannelConversationBinding;

public record ChannelInboundTurnIngestResult(
    ChannelInboundTurnAudit turn,
    boolean duplicateDedupKey,
    ChannelConversationBinding binding
) {
}
