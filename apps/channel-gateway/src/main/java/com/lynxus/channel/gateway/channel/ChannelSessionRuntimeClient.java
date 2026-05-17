package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionTurnRequest;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionTurnResponse;

public interface ChannelSessionRuntimeClient {
    ChannelInboundSessionTurnResponse dispatchInboundTurn(ChannelInboundSessionTurnRequest request);
}
