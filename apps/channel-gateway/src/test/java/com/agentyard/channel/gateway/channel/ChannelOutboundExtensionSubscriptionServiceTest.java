package com.agentyard.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentyard.channel.gateway.extension.ChannelProviderDescriptor;
import com.agentyard.channel.gateway.extension.ChannelProviderRegistry;
import com.agentyard.channel.gateway.extension.ChannelProviderRegistryLoadResult;
import com.agentyard.channel.gateway.extension.ExtensionRegistrationProperties;
import com.agentyard.channel.gateway.extension.ExtensionRegistrationService;
import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameCheckpoint;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderOutboundCapability;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChannelOutboundExtensionSubscriptionServiceTest {
    @Test
    void bootstrapFiltersActiveRemoteProfilesByRegistrationAndProvider() {
        ChannelAdminRepository repository = mock(ChannelAdminRepository.class);
        ChannelProviderDescriptor descriptor = descriptor("enterprise.acme.im", false, "acme-channel-provider");
        ChannelOutboundExtensionAccessService accessService = accessService(repository, descriptor);
        ChannelOutboundExtensionSubscriptionService service = new ChannelOutboundExtensionSubscriptionService(
            repository,
            new StaticRegistry(descriptor),
            accessService
        );
        Instant now = Instant.parse("2026-05-05T00:00:00Z");
        ChannelGatewayProfile active = profile("profile-active", "enterprise.acme.im", ChannelProfileStatus.ACTIVE, 7, now);
        ChannelGatewayProfile inactive = profile("profile-inactive", "enterprise.acme.im", ChannelProfileStatus.INACTIVE, 8, now);
        ChannelOutboundProfileConsumer consumer = new ChannelOutboundProfileConsumer(
            active.id(),
            active.providerType(),
            ChannelOutboundConsumerKind.REMOTE_EXTENSION,
            "acme-channel-provider",
            "acme-channel-provider"
        );
        when(repository.listProfilesByProvider("enterprise.acme.im")).thenReturn(List.of(inactive, active));
        when(repository.findOutboundFinalCheckpoint(consumer)).thenReturn(java.util.Optional.of(new ChannelOutboundFrameCheckpoint(
            consumer,
            42L,
            "frame-42",
            "session-1",
            "message-42",
            now
        )));

        var result = service.list(headers("acme-channel-provider", "enterprise.acme.im"));

        assertEquals(1, result.subscriptions().size());
        var subscription = result.subscriptions().getFirst();
        assertEquals("profile-active", subscription.channelProfileId());
        assertEquals("enterprise.acme.im", subscription.providerType());
        assertEquals("ACTIVE", subscription.status());
        assertEquals("/extension/channel/outbound-frames/stream?channelProfileId=profile-active", subscription.streamUrl());
        assertEquals(7, subscription.revision());
        assertEquals(42L, subscription.lastAckedFinalSequence());
    }

    @Test
    void bootstrapDoesNotExposeGatewayNativeProviderProfiles() {
        ChannelAdminRepository repository = mock(ChannelAdminRepository.class);
        ChannelProviderDescriptor descriptor = descriptor("enterprise.acme.native", true, null);
        ChannelOutboundExtensionAccessService accessService = accessService(repository, descriptor);
        ChannelOutboundExtensionSubscriptionService service = new ChannelOutboundExtensionSubscriptionService(
            repository,
            new StaticRegistry(descriptor),
            accessService
        );

        assertEquals(0, service.list(headers("acme-channel-provider", "enterprise.acme.native")).subscriptions().size());
    }

    private static ChannelOutboundExtensionAccessService accessService(
        ChannelAdminRepository repository,
        ChannelProviderDescriptor descriptor
    ) {
        return new ChannelOutboundExtensionAccessService(
            repository,
            new StaticRegistry(descriptor),
            registrationService()
        );
    }

    private static ChannelOutboundExtensionHeaders headers(String registrationId, String providerType) {
        return new ChannelOutboundExtensionHeaders(registrationId, "CHANNEL_PROVIDER", providerType);
    }

    private static ChannelGatewayProfile profile(
        String profileId,
        String providerType,
        ChannelProfileStatus status,
        long revision,
        Instant now
    ) {
        return new ChannelGatewayProfile(
            profileId,
            providerType,
            "Profile",
            status,
            true,
            Map.of(),
            null,
            null,
            false,
            revision,
            now,
            now
        );
    }

    private static ChannelProviderDescriptor descriptor(String providerType, boolean gatewayNative, String registrationId) {
        return new ChannelProviderDescriptor(
            providerType,
            registrationId,
            gatewayNative ? null : "http://provider.example.com",
            null,
            gatewayNative,
            Map.of(),
            "digest",
            Map.of(),
            Map.of(),
            new ChannelProviderOutboundCapability("FRAME_STREAM", true, true, true, true),
            Map.of()
        );
    }

    private static ExtensionRegistrationService registrationService() {
        try {
            Path file = Files.createTempFile("agentyard-extension-registration", ".yaml");
            Files.writeString(file, """
                agentyard:
                  extensions:
                    services:
                      - registrationId: acme-channel-provider
                        baseUrl: http://channel.example.com
                        exposes:
                          channelProviderTypes:
                            - enterprise.acme.im
                            - enterprise.acme.native
                        auth:
                          type: INTERNAL_TOKEN
                """);
            return new ExtensionRegistrationService(
                new ExtensionRegistrationProperties(file.toString()),
                "http://channel-gateway.example.com",
                "http://agent-runtime.example.com"
            );
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private record StaticRegistry(ChannelProviderDescriptor descriptor) implements ChannelProviderRegistry {
        @Override
        public ChannelProviderRegistryLoadResult snapshot() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelProviderDescriptor requireProvider(String providerType) {
            if (!descriptor.providerType().equals(providerType)) {
                throw new IllegalArgumentException("unknown providerType: " + providerType);
            }
            return descriptor;
        }

        @Override
        public Map<String, Object> materializeAndValidateProfileConfig(String providerType, Map<String, Object> config) {
            return config == null ? Map.of() : Map.copyOf(config);
        }
    }
}
