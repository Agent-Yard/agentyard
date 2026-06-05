package com.agentyard.channel.gateway.channel;

import com.agentyard.channel.gateway.channel.ChannelOutboundExtensionAccessService.ExtensionDescriptor;
import com.agentyard.channel.gateway.extension.ChannelProviderDescriptor;
import com.agentyard.channel.gateway.extension.ChannelProviderRegistry;
import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameCheckpoint;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ChannelOutboundExtensionSubscriptionService {
    private final ChannelAdminRepository repository;
    private final ChannelProviderRegistry registry;
    private final ChannelOutboundExtensionAccessService accessService;

    public ChannelOutboundExtensionSubscriptionService(
        ChannelAdminRepository repository,
        ChannelProviderRegistry registry,
        ChannelOutboundExtensionAccessService accessService
    ) {
        this.repository = repository;
        this.registry = registry;
        this.accessService = accessService;
    }

    public ChannelOutboundFrameSubscriptionList list(ChannelOutboundExtensionHeaders headers) {
        ExtensionDescriptor descriptorHeaders = accessService.validateHeaders(headers);
        ChannelProviderDescriptor descriptor = registry.requireProvider(descriptorHeaders.providerType());
        if (descriptor.gatewayNative() || !descriptorHeaders.registrationId().equals(descriptor.registrationId())) {
            return new ChannelOutboundFrameSubscriptionList(List.of());
        }
        List<ChannelOutboundFrameSubscription> subscriptions = repository.listProfilesByProvider(descriptor.providerType()).stream()
            .filter(profile -> profile.status() == ChannelProfileStatus.ACTIVE)
            .sorted(Comparator.comparing(ChannelGatewayProfile::id))
            .map(profile -> toSubscription(profile, descriptorHeaders.registrationId()))
            .toList();
        return new ChannelOutboundFrameSubscriptionList(subscriptions);
    }

    private ChannelOutboundFrameSubscription toSubscription(ChannelGatewayProfile profile, String registrationId) {
        ChannelOutboundProfileConsumer consumer = new ChannelOutboundProfileConsumer(
            profile.id(),
            profile.providerType(),
            ChannelOutboundConsumerKind.REMOTE_EXTENSION,
            registrationId,
            registrationId
        );
        repository.ensureOutboundFinalCheckpoint(consumer, Instant.now());
        Long lastAckedFinalSequence = repository.findOutboundFinalCheckpoint(consumer)
            .map(ChannelOutboundFrameCheckpoint::lastAckedFinalSequence)
            .orElse(null);
        return new ChannelOutboundFrameSubscription(
            profile.id(),
            profile.providerType(),
            profile.status().name(),
            streamUrl(profile.id()),
            profile.revision(),
            profile.updatedAt(),
            lastAckedFinalSequence
        );
    }

    private static String streamUrl(String channelProfileId) {
        return "/extension/channel/outbound-frames/stream?channelProfileId="
            + URLEncoder.encode(channelProfileId, StandardCharsets.UTF_8);
    }

    public record ChannelOutboundFrameSubscriptionList(List<ChannelOutboundFrameSubscription> subscriptions) {
        public ChannelOutboundFrameSubscriptionList {
            subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
        }
    }

    public record ChannelOutboundFrameSubscription(
        String channelProfileId,
        String providerType,
        String status,
        String streamUrl,
        long revision,
        Instant updatedAt,
        Long lastAckedFinalSequence
    ) {
    }
}
