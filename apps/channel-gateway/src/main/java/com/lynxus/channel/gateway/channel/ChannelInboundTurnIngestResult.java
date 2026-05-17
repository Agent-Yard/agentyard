package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;

public record ChannelInboundTurnIngestResult(
    ChannelInboundTurnAudit turn,
    boolean duplicateDedupKey,
    ChannelConversationBinding binding
) {
}
