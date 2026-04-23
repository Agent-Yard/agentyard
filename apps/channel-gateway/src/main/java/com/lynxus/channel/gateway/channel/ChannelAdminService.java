package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelAccount;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAccountStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderType;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelAccountRequest;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelAccountRequest;
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

    public List<ChannelAccount> listAccounts() {
        return repository.listAccounts();
    }

    public ChannelAccount createAccount(CreateChannelAccountRequest request) {
        Instant now = Instant.now();
        ChannelAccount account = new ChannelAccount(
            nextId("channel-account"),
            requireProviderType(request.providerType()),
            requireText(request.name(), "channelAccount.name"),
            request.status() == null ? ChannelAccountStatus.ACTIVE : request.status(),
            request.config() == null ? Map.of() : request.config(),
            now,
            now
        );
        repository.saveAccount(account);
        return account;
    }

    public ChannelAccount getAccount(String accountId) {
        return repository.findAccount(accountId)
            .orElseThrow(() -> new NoSuchElementException("channel account not found: " + accountId));
    }

    public ChannelAccount updateAccount(String accountId, UpdateChannelAccountRequest request) {
        ChannelAccount existing = getAccount(accountId);
        ChannelAccount updated = new ChannelAccount(
            existing.id(),
            existing.providerType(),
            requireText(request.name(), "channelAccount.name"),
            request.status() == null ? existing.status() : request.status(),
            request.config() == null ? existing.config() : request.config(),
            existing.createdAt(),
            Instant.now()
        );
        repository.saveAccount(updated);
        return updated;
    }

    public List<ChannelConversationBinding> listBindings(String accountId) {
        getAccount(accountId);
        return repository.listBindings(accountId);
    }

    public List<ChannelInboundEvent> listInboundEvents(String accountId) {
        getAccount(accountId);
        return repository.listInboundEvents(accountId);
    }

    public List<ChannelOutboundDelivery> listOutboundDeliveries(String accountId) {
        getAccount(accountId);
        return repository.listOutboundDeliveries(accountId);
    }

    public ChannelAccount findAccountByProviderAppId(ChannelProviderType providerType, String appId) {
        String normalizedAppId = requireText(appId, "channelAccount.config.appId");
        return repository.listAccountsByProvider(providerType).stream()
            .filter(account -> normalizedAppId.equals(String.valueOf(account.config().get("appId"))))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("channel account not found for " + providerType.name() + " appId: " + normalizedAppId));
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

    private static ChannelProviderType requireProviderType(ChannelProviderType providerType) {
        if (providerType == null) {
            throw new IllegalArgumentException("channelAccount.providerType is required");
        }
        return providerType;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
