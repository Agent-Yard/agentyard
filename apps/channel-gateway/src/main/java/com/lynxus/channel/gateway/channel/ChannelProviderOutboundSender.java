package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundResponse;

interface ChannelProviderOutboundSender {
    ChannelOutboundResponse send(ChannelOutboundInvocation invocation) throws Exception;
}
