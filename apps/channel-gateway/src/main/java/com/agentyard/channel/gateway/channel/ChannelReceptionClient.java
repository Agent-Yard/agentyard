package com.agentyard.channel.gateway.channel;

import com.agentyard.contracts.channel.ChannelContracts.ChannelReceptionRequest;
import com.agentyard.contracts.channel.ChannelContracts.ChannelReceptionResponse;

public interface ChannelReceptionClient {
    ChannelReceptionResponse dispatchInboundTurn(ChannelReceptionRequest request);
}
