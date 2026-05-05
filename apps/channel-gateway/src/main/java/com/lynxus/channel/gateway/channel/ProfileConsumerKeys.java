package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;

final class ProfileConsumerKeys {
    private ProfileConsumerKeys() {
    }

    static String key(ChannelOutboundProfileConsumer consumer) {
        return clean(consumer.channelProfileId())
            + ":"
            + clean(consumer.providerType())
            + ":"
            + consumer.consumerKind().name()
            + ":"
            + clean(consumer.consumerId());
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("profile consumer identity contains blank segment");
        }
        return value.trim().replace(':', '_');
    }
}
