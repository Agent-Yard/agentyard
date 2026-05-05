package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.extension.ChannelProviderDescriptor;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistry;
import com.lynxus.channel.gateway.extension.ExtensionRegistrationService;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.extension.sdk.protocol.DescriptorType;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;

@Service
public class ChannelOutboundExtensionAccessService {
    private final ChannelAdminRepository repository;
    private final ChannelProviderRegistry registry;
    private final ExtensionRegistrationService registrationService;

    public ChannelOutboundExtensionAccessService(
        ChannelAdminRepository repository,
        ChannelProviderRegistry registry,
        ExtensionRegistrationService registrationService
    ) {
        this.repository = repository;
        this.registry = registry;
        this.registrationService = registrationService;
    }

    public AuthorizedExtensionConsumer requireConsumer(String channelProfileId, ChannelOutboundExtensionHeaders headers) {
        ExtensionDescriptor descriptorHeaders = validateHeaders(headers);
        ChannelGatewayProfile profile = repository.findProfile(requireText(channelProfileId, "channelProfileId"))
            .orElseThrow(() -> new NoSuchElementException("channel profile not found: " + channelProfileId));
        if (profile.status() != ChannelProfileStatus.ACTIVE) {
            throw new IllegalArgumentException("channel profile is not ACTIVE: " + profile.id());
        }
        if (!profile.providerType().equals(descriptorHeaders.providerType())) {
            throw new IllegalArgumentException("channel profile providerType does not match extension descriptor id");
        }
        ChannelProviderDescriptor descriptor = registry.requireProvider(profile.providerType());
        requireRemoteDescriptor(descriptor, descriptorHeaders.registrationId());
        ChannelOutboundProfileConsumer consumer = new ChannelOutboundProfileConsumer(
            profile.id(),
            descriptor.providerType(),
            ChannelOutboundConsumerKind.REMOTE_EXTENSION,
            descriptorHeaders.registrationId(),
            descriptorHeaders.registrationId()
        );
        return new AuthorizedExtensionConsumer(profile, descriptor, consumer);
    }

    public ExtensionDescriptor validateHeaders(ChannelOutboundExtensionHeaders headers) {
        if (headers == null) {
            throw new IllegalArgumentException("extension outbound headers are required");
        }
        String registrationId = requireText(headers.registrationId(), "X-Lynxus-Extension-Registration-Id");
        String descriptorType = requireText(headers.descriptorType(), "X-Lynxus-Extension-Descriptor-Type");
        String providerType = requireText(headers.descriptorId(), "X-Lynxus-Extension-Descriptor-Id");
        if (!DescriptorType.CHANNEL_PROVIDER.wireValue().equals(descriptorType)) {
            throw new IllegalArgumentException("X-Lynxus-Extension-Descriptor-Type must be CHANNEL_PROVIDER");
        }
        boolean exposesProvider = registrationService.services().stream()
            .anyMatch(registration -> registration.registrationId().equals(registrationId)
                && registration.exposes().channelProviderTypes().contains(providerType));
        if (!exposesProvider) {
            throw new IllegalArgumentException("extension registration does not expose channel provider: " + providerType);
        }
        return new ExtensionDescriptor(registrationId, providerType);
    }

    void requireRemoteDescriptor(ChannelProviderDescriptor descriptor, String registrationId) {
        if (descriptor.gatewayNative()) {
            throw new IllegalArgumentException("gateway-native channel profile is not extension-facing: " + descriptor.providerType());
        }
        if (!registrationId.equals(descriptor.registrationId())) {
            throw new IllegalArgumentException("extension registration does not own channel provider: " + descriptor.providerType());
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public record ExtensionDescriptor(String registrationId, String providerType) {
    }

    public record AuthorizedExtensionConsumer(
        ChannelGatewayProfile profile,
        ChannelProviderDescriptor descriptor,
        ChannelOutboundProfileConsumer consumer
    ) {
    }
}
