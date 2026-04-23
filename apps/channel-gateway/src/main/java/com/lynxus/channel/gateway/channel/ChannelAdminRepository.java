package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelAccount;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderType;
import com.lynxus.persistence.channel.ChannelStore;
import com.lynxus.persistence.jooqsupport.JooqJsonbSupport;
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

    public List<ChannelAccount> listAccounts() {
        return store.listAccounts();
    }

    public Optional<ChannelAccount> findAccount(String accountId) {
        return store.findAccount(accountId);
    }

    public List<ChannelAccount> listAccountsByProvider(ChannelProviderType providerType) {
        return store.listAccountsByProvider(providerType);
    }

    public void saveAccount(ChannelAccount account) {
        store.saveAccount(account);
    }

    public List<ChannelConversationBinding> listBindings(String accountId) {
        return store.listBindings(accountId);
    }

    public void saveBinding(ChannelConversationBinding binding) {
        store.saveBinding(binding);
    }

    public List<ChannelInboundEvent> listInboundEvents(String accountId) {
        return store.listInboundEvents(accountId);
    }

    public Optional<ChannelInboundEvent> findInboundEventByDedupKey(String dedupKey) {
        return store.findInboundEventByDedupKey(dedupKey);
    }

    public void saveInboundEvent(ChannelInboundEvent event) {
        store.saveInboundEvent(event);
    }

    public List<ChannelOutboundDelivery> listOutboundDeliveries(String accountId) {
        return store.listOutboundDeliveries(accountId);
    }

    public void saveOutboundDelivery(ChannelOutboundDelivery delivery) {
        store.saveOutboundDelivery(delivery);
    }
}
