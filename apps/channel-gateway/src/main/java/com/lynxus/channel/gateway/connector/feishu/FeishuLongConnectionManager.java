package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.channel.gateway.channel.ChannelAdminRepository;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final ExecutorService executor;
    private final Map<String, Boolean> startedProfileIds = new ConcurrentHashMap<>();

    @Autowired
    FeishuLongConnectionManager(
        ChannelAdminRepository repository,
        FeishuCredentialProvider credentialProvider,
        FeishuLongConnectionClientFactory clientFactory
    ) {
        this(repository, credentialProvider, clientFactory, Executors.newCachedThreadPool(daemonThreadFactory()));
    }

    FeishuLongConnectionManager(
        ChannelAdminRepository repository,
        FeishuCredentialProvider credentialProvider,
        FeishuLongConnectionClientFactory clientFactory,
        ExecutorService executor
    ) {
        this.repository = repository;
        this.credentialProvider = credentialProvider;
        this.clientFactory = clientFactory;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${lynxus.channel-gateway.feishu.long-connection.reconcile-fixed-delay-ms:30000}")
    void reconcile() {
        for (ChannelGatewayProfile profile : repository.listProfilesByProvider(FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE)) {
            if (!eligible(profile) || startedProfileIds.containsKey(profile.id())) {
                continue;
            }
            start(profile);
        }
    }

    boolean isStarted(String channelProfileId) {
        return startedProfileIds.containsKey(channelProfileId);
    }

    private void start(ChannelGatewayProfile profile) {
        if (startedProfileIds.putIfAbsent(profile.id(), Boolean.TRUE) != null) {
            return;
        }
        try {
            FeishuAppCredential credential = credentialProvider.resolve(profile.accountId(), profile.config());
            FeishuLongConnectionClient client = clientFactory.create(
                new FeishuLongConnectionProfile(profile.id(), profile.displayName()),
                credential
            );
            executor.execute(() -> runClient(profile.id(), credential.appId(), client));
            log.info("started feishu long connection client: channelProfileId={}, accountId={}", profile.id(), profile.accountId());
        } catch (RuntimeException error) {
            startedProfileIds.remove(profile.id());
            log.warn("failed to start feishu long connection client: channelProfileId={}", profile.id(), error);
        }
    }

    private void runClient(String channelProfileId, String appId, FeishuLongConnectionClient client) {
        try {
            client.start();
        } catch (RuntimeException error) {
            log.warn("feishu long connection client stopped with error: channelProfileId={}, appId={}", channelProfileId, appId, error);
        } finally {
            startedProfileIds.remove(channelProfileId);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "feishu-long-connection-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
