package com.lynxus.channel.gateway.extension;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityResponse;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityResponseStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundResponse;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderActivityRequest;
import java.util.Map;

public interface GatewayNativeChannelProviderAdapter {
    String providerType();

    Map<String, Object> descriptor();

    ChannelOutboundResponse sendOutbound(ChannelOutboundRequest request);

    default ChannelOutboundActivityResponse sendActivity(ChannelProviderActivityRequest request) {
        return new ChannelOutboundActivityResponse(ChannelOutboundActivityResponseStatus.UNSUPPORTED, false, Map.of());
    }
}
