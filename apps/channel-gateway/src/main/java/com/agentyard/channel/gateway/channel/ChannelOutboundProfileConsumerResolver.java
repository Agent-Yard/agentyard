package com.agentyard.channel.gateway.channel;

import com.agentyard.channel.gateway.extension.ChannelProviderDescriptor;
import com.agentyard.channel.gateway.extension.ChannelProviderRegistry;
import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ChannelOutboundProfileConsumerResolver {
    private final ChannelProviderRegistry registry;

    public ChannelOutboundProfileConsumerResolver(ChannelProviderRegistry registry) {
        this.registry = registry;
    }

    public Optional<ResolvedProfileConsumer> resolve(ChannelGatewayProfile profile) {
        if (profile == null || profile.status() != ChannelProfileStatus.ACTIVE) {
            return Optional.empty();
        }
        ChannelProviderDescriptor descriptor = registry.requireProvider(profile.providerType());
        ChannelOutboundProfileConsumer consumer = descriptor.gatewayNative()
            ? new ChannelOutboundProfileConsumer(
                profile.id(),
                descriptor.providerType(),
                ChannelOutboundConsumerKind.GATEWAY_NATIVE,
                "native:" + descriptor.providerType(),
                null
            )
            : new ChannelOutboundProfileConsumer(
                profile.id(),
                descriptor.providerType(),
                ChannelOutboundConsumerKind.REMOTE_EXTENSION,
                descriptor.registrationId(),
                descriptor.registrationId()
            );
        return Optional.of(new ResolvedProfileConsumer(profile, descriptor, consumer));
    }

    public record ResolvedProfileConsumer(
        ChannelGatewayProfile profile,
        ChannelProviderDescriptor descriptor,
        ChannelOutboundProfileConsumer consumer
    ) {
    }
}
