package com.lynxus.channel.gateway.extension;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundResponse;
import java.util.Map;

public interface GatewayNativeChannelProviderAdapter {
    String providerType();

    Map<String, Object> descriptor();

    ChannelOutboundResponse sendOutbound(ChannelOutboundRequest request);
}
