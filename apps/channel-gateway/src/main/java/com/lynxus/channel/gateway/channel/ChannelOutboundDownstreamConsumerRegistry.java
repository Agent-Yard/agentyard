package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ChannelOutboundDownstreamConsumerRegistry {
    private final Set<String> activeRemoteConsumers = ConcurrentHashMap.newKeySet();

    public void markActive(ChannelOutboundProfileConsumer consumer) {
        if (consumer.consumerKind() == ChannelOutboundConsumerKind.REMOTE_EXTENSION) {
            activeRemoteConsumers.add(ProfileConsumerKeys.key(consumer));
        }
    }

    public void markInactive(ChannelOutboundProfileConsumer consumer) {
        if (consumer.consumerKind() == ChannelOutboundConsumerKind.REMOTE_EXTENSION) {
            activeRemoteConsumers.remove(ProfileConsumerKeys.key(consumer));
        }
    }

    public boolean isActive(ChannelOutboundProfileConsumer consumer) {
        return consumer.consumerKind() != ChannelOutboundConsumerKind.REMOTE_EXTENSION
            || activeRemoteConsumers.contains(ProfileConsumerKeys.key(consumer));
    }
}
