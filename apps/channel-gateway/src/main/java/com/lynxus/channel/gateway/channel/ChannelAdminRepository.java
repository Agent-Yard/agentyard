package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.jooqsupport.JooqJsonbSupport;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ChannelAdminRepository {
    private final ChannelStore store;

    public ChannelAdminRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.store = new ChannelStore(dsl, new JooqJsonbSupport(objectMapper));
    }

    public List<ChannelGatewayProfile> listProfiles() {
        return store.listProfiles();
    }

    public Optional<ChannelGatewayProfile> findProfile(String channelProfileId) {
        return store.findProfile(channelProfileId);
    }

    public List<ChannelGatewayProfile> listProfilesByProvider(String providerType) {
        return store.listProfilesByProvider(providerType);
    }

    public void createProfile(ChannelGatewayProfile profile, String externalSecretRef) {
        store.createProfile(profile, externalSecretRef);
    }

    public boolean updateProfile(ChannelGatewayProfile profile, long expectedRevision, String externalSecretRef) {
        return store.updateProfile(profile, expectedRevision, externalSecretRef);
    }

    public boolean disableProfile(String channelProfileId, long expectedRevision, long nextRevision, Instant updatedAt) {
        return store.disableProfile(channelProfileId, expectedRevision, nextRevision, updatedAt);
    }

    public List<ChannelConversationBinding> listBindings(String channelProfileId) {
        return store.listBindings(channelProfileId);
    }

    public void saveBinding(ChannelConversationBinding binding) {
        store.saveBinding(binding);
    }

    public List<ChannelInboundEvent> listInboundEvents(String channelProfileId) {
        return store.listInboundEvents(channelProfileId);
    }

    public Optional<ChannelInboundEvent> findInboundEventByDedupKey(String dedupKey) {
        return store.findInboundEventByDedupKey(dedupKey);
    }

    public void saveInboundEvent(ChannelInboundEvent event) {
        store.saveInboundEvent(event);
    }

    public List<ChannelOutboundDelivery> listOutboundDeliveries(String channelProfileId) {
        return store.listOutboundDeliveries(channelProfileId);
    }

    public void saveOutboundDelivery(ChannelOutboundDelivery delivery) {
        store.saveOutboundDelivery(delivery);
    }
}
