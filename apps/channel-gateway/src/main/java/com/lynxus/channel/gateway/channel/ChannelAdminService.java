package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelProfileRequest;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelProfileRequest;
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

    public List<ChannelProfile> listProfiles() {
        return repository.listProfiles();
    }

    public ChannelProfile createProfile(CreateChannelProfileRequest request) {
        Instant now = Instant.now();
        ChannelProfile profile = new ChannelProfile(
            nextId("channel-profile"),
            requireProviderType(request.providerType()),
            requireText(request.name(), "channelProfile.name"),
            request.status() == null ? ChannelProfileStatus.ACTIVE : request.status(),
            request.config() == null ? Map.of() : request.config(),
            now,
            now
        );
        repository.saveProfile(profile);
        return profile;
    }

    public ChannelProfile getProfile(String channelProfileId) {
        return repository.findProfile(channelProfileId)
            .orElseThrow(() -> new NoSuchElementException("channel profile not found: " + channelProfileId));
    }

    public ChannelProfile updateProfile(String channelProfileId, UpdateChannelProfileRequest request) {
        ChannelProfile existing = getProfile(channelProfileId);
        ChannelProfile updated = new ChannelProfile(
            existing.id(),
            existing.providerType(),
            requireText(request.name(), "channelProfile.name"),
            request.status() == null ? existing.status() : request.status(),
            request.config() == null ? existing.config() : request.config(),
            existing.createdAt(),
            Instant.now()
        );
        repository.saveProfile(updated);
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

    public ChannelProfile findProfileByProviderAppId(String providerType, String appId) {
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
}
