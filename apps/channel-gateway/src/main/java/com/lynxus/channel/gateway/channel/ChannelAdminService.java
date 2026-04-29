package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileAccountSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelProfileInternalRequest;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelProfileInternalRequest;
import com.lynxus.channel.gateway.shared.ConflictException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ChannelAdminService {
    private final ChannelAdminRepository repository;

    public ChannelAdminService(ChannelAdminRepository repository) {
        this.repository = repository;
    }

    public List<ChannelGatewayProfile> listProfiles() {
        return repository.listProfiles();
    }

    public ChannelGatewayProfile createProfile(CreateChannelProfileInternalRequest request) {
        Instant now = Instant.now();
        ChannelProfileAccountSnapshot accountSnapshot = request.accountSnapshot();
        ChannelGatewayProfile profile = new ChannelGatewayProfile(
            nextId("channel-profile"),
            requireProviderType(request.providerType()),
            requireText(request.displayName(), "channelProfile.displayName"),
            request.status() == null ? ChannelProfileStatus.ACTIVE : request.status(),
            request.inboundEnabled() == null || request.inboundEnabled(),
            request.config() == null ? Map.of() : request.config(),
            request.assistantBinding(),
            accountSnapshot == null ? null : normalizeOptionalText(accountSnapshot.accountId()),
            accountSnapshot != null && hasText(accountSnapshot.externalSecretRef()),
            1,
            now,
            now
        );
        repository.createProfile(profile, accountSnapshot == null ? null : normalizeOptionalText(accountSnapshot.externalSecretRef()));
        return profile;
    }

    public ChannelGatewayProfile getProfile(String channelProfileId) {
        return repository.findProfile(channelProfileId)
            .orElseThrow(() -> new NoSuchElementException("channel profile not found: " + channelProfileId));
    }

    public ChannelGatewayProfile updateProfile(String channelProfileId, UpdateChannelProfileInternalRequest request) {
        ChannelGatewayProfile existing = getProfile(channelProfileId);
        long expectedRevision = requireExpectedRevision(request.expectedRevision());
        ChannelProfileAccountSnapshot accountSnapshot = request.accountSnapshot();
        ChannelGatewayProfile updated = new ChannelGatewayProfile(
            existing.id(),
            requireProviderType(request.providerType()),
            requireText(request.displayName(), "channelProfile.displayName"),
            request.status() == null ? existing.status() : request.status(),
            request.inboundEnabled() == null ? existing.inboundEnabled() : request.inboundEnabled(),
            request.config() == null ? existing.config() : request.config(),
            request.assistantBinding() == null ? existing.assistantBinding() : request.assistantBinding(),
            accountSnapshot == null ? null : normalizeOptionalText(accountSnapshot.accountId()),
            accountSnapshot != null && hasText(accountSnapshot.externalSecretRef()),
            existing.revision() + 1,
            existing.createdAt(),
            Instant.now()
        );
        boolean updatedRow = repository.updateProfile(
            updated,
            expectedRevision,
            accountSnapshot == null ? null : normalizeOptionalText(accountSnapshot.externalSecretRef())
        );
        if (!updatedRow) {
            throw new ConflictException("channel profile revision conflict: " + channelProfileId);
        }
        return updated;
    }

    public List<ChannelConversationBinding> listBindings(String channelProfileId) {
        getProfile(channelProfileId);
        return repository.listBindings(channelProfileId);
    }

    public List<ChannelInboundEvent> listInboundEvents(String channelProfileId) {
        getProfile(channelProfileId);
        return repository.listInboundEvents(channelProfileId);
    }

    public List<ChannelOutboundDelivery> listOutboundDeliveries(String channelProfileId) {
        getProfile(channelProfileId);
        return repository.listOutboundDeliveries(channelProfileId);
    }

    public ChannelGatewayProfile findProfileByProviderAppId(String providerType, String appId) {
        String normalizedAppId = requireText(appId, "channelProfile.config.appId");
        return repository.listProfilesByProvider(providerType).stream()
            .filter(profile -> normalizedAppId.equals(String.valueOf(profile.config().get("appId"))))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("channel profile not found for " + providerType + " appId: " + normalizedAppId));
    }

    public void saveInboundEvent(ChannelInboundEvent event) {
        repository.saveInboundEvent(event);
    }

    public ChannelInboundEvent findInboundEventByDedupKey(String dedupKey) {
        return repository.findInboundEventByDedupKey(dedupKey).orElse(null);
    }

    public String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String requireProviderType(String providerType) {
        if (providerType == null || providerType.isBlank()) {
            throw new IllegalArgumentException("channelProfile.providerType is required");
        }
        return providerType.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static long requireExpectedRevision(Long expectedRevision) {
        if (expectedRevision == null || expectedRevision < 1) {
            throw new IllegalArgumentException("channelProfile.expectedRevision is required");
        }
        return expectedRevision;
    }

    private static String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
