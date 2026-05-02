package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityResponse;

interface ChannelProviderActivitySender {
    ChannelOutboundActivityResponse send(ChannelOutboundActivityInvocation invocation) throws Exception;
}
