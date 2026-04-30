package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.channel.gateway.channel.ChannelAdminRepository;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class FeishuLongConnectionManager {
    private static final Logger log = LoggerFactory.getLogger(FeishuLongConnectionManager.class);

    private final ChannelAdminRepository repository;
    private final FeishuCredentialProvider credentialProvider;
    private final FeishuLongConnectionClientFactory clientFactory;
    private final Set<String> startedAccountIds = ConcurrentHashMap.newKeySet();
    private final Map<String, FeishuLongConnectionProfile> selectedProfilesByAccountId = new ConcurrentHashMap<>();
    private final Set<String> duplicateProfileWarnings = ConcurrentHashMap.newKeySet();

    @Autowired
    FeishuLongConnectionManager(
        ChannelAdminRepository repository,
        FeishuCredentialProvider credentialProvider,
        FeishuLongConnectionClientFactory clientFactory
    ) {
        this.repository = repository;
        this.credentialProvider = credentialProvider;
        this.clientFactory = clientFactory;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${lynxus.channel-gateway.feishu.long-connection.reconcile-fixed-delay-ms:30000}")
    synchronized void reconcile() {
        Map<String, ChannelGatewayProfile> selectedProfiles = selectedProfilesByAccount();
        selectedProfilesByAccountId.keySet().removeIf(accountId -> !selectedProfiles.containsKey(accountId));
        for (Map.Entry<String, ChannelGatewayProfile> entry : selectedProfiles.entrySet()) {
            String accountId = entry.getKey();
            ChannelGatewayProfile profile = entry.getValue();
            selectedProfilesByAccountId.put(accountId, profile(profile));
            if (!startedAccountIds.contains(accountId)) {
                start(accountId, profile);
            }
        }
    }

    boolean isStarted(String channelProfileId) {
        return selectedProfilesByAccountId.values().stream()
            .anyMatch(profile -> profile.channelProfileId().equals(channelProfileId)
                && startedAccountIds.contains(profile.accountId()));
    }

    private Map<String, ChannelGatewayProfile> selectedProfilesByAccount() {
        Map<String, ChannelGatewayProfile> selectedProfiles = new LinkedHashMap<>();
        for (ChannelGatewayProfile profile : repository.listProfilesByProvider(FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE)) {
            if (!eligible(profile)) {
                continue;
            }
            ChannelGatewayProfile selected = selectedProfiles.putIfAbsent(profile.accountId(), profile);
            if (selected != null && duplicateProfileWarnings.add(profile.accountId() + ":" + profile.id())) {
                log.warn(
                    "multiple eligible feishu profiles share the same integration account; selectedProfileId={}, skippedProfileId={}, accountId={}",
                    selected.id(),
                    profile.id(),
                    profile.accountId()
                );
            }
        }
        return selectedProfiles;
    }

    private void start(String accountId, ChannelGatewayProfile profile) {
        if (!startedAccountIds.add(accountId)) {
            return;
        }
        try {
            FeishuAppCredential credential = credentialProvider.resolve(accountId, profile.config());
            // The Feishu SDK starts its own non-blocking websocket lifecycle; keep this inline so we do not
            // introduce a separate executor that competes with the SDK's connection management.
            clientFactory.start(() -> selectedProfilesByAccountId.get(accountId), credential);
            log.info("started feishu long connection client: channelProfileId={}, accountId={}", profile.id(), accountId);
        } catch (RuntimeException error) {
            startedAccountIds.remove(accountId);
            log.warn("failed to start feishu long connection client: channelProfileId={}", profile.id(), error);
        }
    }

    private static boolean eligible(ChannelGatewayProfile profile) {
        if (profile == null) {
            return false;
        }
        ChannelAssistantBinding binding = profile.assistantBinding();
        return FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE.equals(profile.providerType())
            && profile.status() == ChannelProfileStatus.ACTIVE
            && profile.inboundEnabled()
            && hasText(profile.accountId())
            && binding != null
            && hasText(binding.assistantId());
    }

    private static FeishuLongConnectionProfile profile(ChannelGatewayProfile profile) {
        return new FeishuLongConnectionProfile(profile.accountId(), profile.id());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
