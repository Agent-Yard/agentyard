package com.agentyard.channel.gateway.channel;

import com.agentyard.contracts.session.SessionContracts.ChannelInboundSessionTurnRequest;
import com.agentyard.contracts.session.SessionContracts.ChannelInboundSessionTurnResponse;

public interface ChannelSessionRuntimeClient {
    ChannelInboundSessionTurnResponse dispatchInboundTurn(ChannelInboundSessionTurnRequest request);
}
