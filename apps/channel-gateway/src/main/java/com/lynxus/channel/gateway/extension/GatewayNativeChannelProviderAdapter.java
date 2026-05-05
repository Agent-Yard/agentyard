package com.lynxus.channel.gateway.extension;

import java.util.Map;

public interface GatewayNativeChannelProviderAdapter {
    String providerType();

    Map<String, Object> descriptor();
}
