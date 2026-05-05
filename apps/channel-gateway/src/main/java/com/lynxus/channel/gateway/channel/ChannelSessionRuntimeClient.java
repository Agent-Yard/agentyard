package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionMessageRequest;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionMessageResponse;

interface ChannelSessionRuntimeClient {
    ChannelInboundSessionMessageResponse dispatchInboundMessage(ChannelInboundSessionMessageRequest request);
}
