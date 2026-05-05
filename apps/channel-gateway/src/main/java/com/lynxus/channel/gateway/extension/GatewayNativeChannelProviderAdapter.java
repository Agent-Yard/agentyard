package com.lynxus.channel.gateway.extension;

import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import java.util.Map;

public interface GatewayNativeChannelProviderAdapter {
    String providerType();

    Map<String, Object> descriptor();

    void consumeOutboundFrame(ChannelGatewayProfile profile, ChannelOutboundFrame frame);
}
