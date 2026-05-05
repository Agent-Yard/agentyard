package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.channel.ChannelOutboundApiFrameStreamClient.StreamHandler;
import com.lynxus.channel.gateway.channel.ChannelOutboundApiFrameStreamClient.StreamRequest;
import com.lynxus.channel.gateway.channel.ChannelOutboundProfileConsumerResolver.ResolvedProfileConsumer;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameCheckpoint;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ChannelOutboundUpstreamRelaySupervisor {
    private static final Logger log = LoggerFactory.getLogger(ChannelOutboundUpstreamRelaySupervisor.class);

    private final ChannelAdminRepository repository;
    private final ChannelOutboundProfileConsumerResolver consumerResolver;
    private final ChannelOutboundStreamOwnerLockService ownerLockService;
    private final ChannelOutboundFrameHandoff handoff;
    private final ChannelOutboundCapabilityFilter capabilityFilter;
    private final ChannelOutboundReplayCreditCalculator creditCalculator;
    private final ChannelOutboundRelayProperties properties;
    private final ChannelOutboundApiFrameStreamClient streamClient;
    private final ExecutorService executorService;
    private final String instanceOwnerPrefix;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, String> lastStreamCursorByConsumer = new ConcurrentHashMap<>();

    public ChannelOutboundUpstreamRelaySupervisor(
        ChannelAdminRepository repository,
        ChannelOutboundProfileConsumerResolver consumerResolver,
        ChannelOutboundStreamOwnerLockService ownerLockService,
        ChannelOutboundFrameHandoff handoff,
        ChannelOutboundCapabilityFilter capabilityFilter,
        ChannelOutboundReplayCreditCalculator creditCalculator,
        ChannelOutboundRelayProperties properties,
        ObjectMapper objectMapper,
        @Value("${lynxus.api.base-url}") String apiBaseUrl,
        @Qualifier("internalAuthToken") String internalAuthToken
    ) {
        this(
            repository,
            consumerResolver,
            ownerLockService,
            handoff,
            capabilityFilter,
            creditCalculator,
            properties,
            new ChannelOutboundApiFrameStreamClient(
                objectMapper,
                apiBaseUrl,
                internalAuthToken,
                HttpClient.newHttpClient(),
                properties.getConnectTimeout()
            ),
            Executors.newCachedThreadPool(Thread.ofVirtual().name("channel-outbound-upstream-", 0).factory()),
            "channel-gateway:" + UUID.randomUUID()
        );
    }

    ChannelOutboundUpstreamRelaySupervisor(
        ChannelAdminRepository repository,
        ChannelOutboundProfileConsumerResolver consumerResolver,
        ChannelOutboundStreamOwnerLockService ownerLockService,
        ChannelOutboundFrameHandoff handoff,
        ChannelOutboundCapabilityFilter capabilityFilter,
        ChannelOutboundReplayCreditCalculator creditCalculator,
        ChannelOutboundRelayProperties properties,
        ChannelOutboundApiFrameStreamClient streamClient,
        ExecutorService executorService,
        String instanceOwnerPrefix
    ) {
        this.repository = repository;
        this.consumerResolver = consumerResolver;
        this.ownerLockService = ownerLockService;
        this.handoff = handoff;
        this.capabilityFilter = capabilityFilter;
        this.creditCalculator = creditCalculator;
        this.properties = properties;
        this.streamClient = streamClient;
        this.executorService = executorService;
        this.instanceOwnerPrefix = instanceOwnerPrefix;
    }

    @Scheduled(fixedDelayString = "#{@channelOutboundRelayProperties.scanFixedDelay.toMillis()}")
    public void reconcileSubscriptions() {
        if (!properties.isEnabled()) {
            stopAll();
            return;
        }
        Set<String> desiredKeys = new HashSet<>();
        for (ChannelGatewayProfile profile : repository.listProfiles()) {
            Optional<ResolvedProfileConsumer> resolved = consumerResolver.resolve(profile);
            if (resolved.isEmpty()) {
                continue;
            }
            ResolvedProfileConsumer profileConsumer = resolved.get();
            String key = ProfileConsumerKeys.key(profileConsumer.consumer());
            desiredKeys.add(key);
            repository.ensureOutboundFinalCheckpoint(profileConsumer.consumer(), Instant.now());
            startIfNeeded(profileConsumer);
        }
        for (Map.Entry<String, Subscription> entry : subscriptions.entrySet()) {
            if (!desiredKeys.contains(entry.getKey()) || entry.getValue().isDone()) {
                stop(entry.getKey(), entry.getValue());
            }
        }
    }

    @Scheduled(fixedDelayString = "#{@channelOutboundRelayProperties.scanFixedDelay.toMillis()}")
    public void renewOwnership() {
        for (Map.Entry<String, Subscription> entry : subscriptions.entrySet()) {
            Subscription subscription = entry.getValue();
            if (!subscription.running.get()) {
                continue;
            }
            boolean renewed = ownerLockService.renew(
                subscription.consumer(),
                subscription.ownerToken(),
                properties.getOwnerLockTtl()
            );
            if (!renewed) {
                log.warn("lost channel outbound stream owner lock consumer={}", entry.getKey());
                stop(entry.getKey(), subscription);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        stopAll();
        executorService.shutdownNow();
    }

    private void startIfNeeded(ResolvedProfileConsumer resolved) {
        ChannelOutboundProfileConsumer consumer = resolved.consumer();
        String key = ProfileConsumerKeys.key(consumer);
        Subscription existing = subscriptions.get(key);
        if (existing != null && !existing.isDone() && existing.running.get()) {
            return;
        }
        int currentPendingFinals = handoff.pendingFinals(consumer);
        if (!creditCalculator.shouldResume(currentPendingFinals, resumePendingFinals(consumer))) {
            return;
        }
        int credit = creditCalculator.replayCredit(maxPendingFinals(consumer), currentPendingFinals);
        if (credit <= 0) {
            return;
        }

        String ownerToken = instanceOwnerPrefix + ":" + key + ":" + UUID.randomUUID();
        if (!ownerLockService.acquire(consumer, ownerToken, properties.getOwnerLockTtl())) {
            return;
        }
        Subscription subscription = new Subscription(consumer, ownerToken);
        Subscription previous = subscriptions.put(key, subscription);
        if (previous != null) {
            stop(key, previous);
        }
        Future<?> future = executorService.submit(() -> runSubscription(key, resolved, subscription));
        subscription.future.set(future);
    }

    private void runSubscription(String key, ResolvedProfileConsumer resolved, Subscription subscription) {
        try {
            ChannelOutboundFrameCheckpoint checkpoint = repository.findOutboundFinalCheckpoint(resolved.consumer())
                .orElse(null);
            int credit = creditCalculator.replayCredit(maxPendingFinals(resolved.consumer()), handoff.pendingFinals(resolved.consumer()));
            StreamRequest request = new StreamRequest(
                resolved.consumer().channelProfileId(),
                lastStreamCursorByConsumer.get(key),
                checkpoint == null ? null : checkpoint.lastAckedFinalSequence(),
                checkpoint == null ? null : checkpoint.lastAckedSessionId(),
                checkpoint == null ? null : checkpoint.lastAckedSessionMessageId(),
                credit
            );
            streamClient.stream(request, new RelayStreamHandler(key, resolved, subscription));
        } catch (BackpressureReached ignored) {
            log.info("channel outbound upstream paused at pending high water consumer={}", key);
        } catch (Exception error) {
            if (subscription.running.get()) {
                log.warn("channel outbound upstream stream ended with error consumer={}", key, error);
            }
        } finally {
            subscription.running.set(false);
            ownerLockService.release(subscription.consumer(), subscription.ownerToken());
            subscriptions.remove(key, subscription);
        }
    }

    private int maxPendingFinals(ChannelOutboundProfileConsumer consumer) {
        return consumer.consumerKind() == ChannelOutboundConsumerKind.GATEWAY_NATIVE
            ? properties.getNativeMaxPendingFinals()
            : properties.getRemoteMaxPendingFinals();
    }

    private int resumePendingFinals(ChannelOutboundProfileConsumer consumer) {
        return consumer.consumerKind() == ChannelOutboundConsumerKind.GATEWAY_NATIVE
            ? properties.getNativeResumePendingFinals()
            : properties.getRemoteResumePendingFinals();
    }

    private void stopAll() {
        for (Map.Entry<String, Subscription> entry : subscriptions.entrySet()) {
            stop(entry.getKey(), entry.getValue());
        }
    }

    private void stop(String key, Subscription subscription) {
        if (!subscription.running.getAndSet(false)) {
            return;
        }
        subscription.closeBody();
        Future<?> future = subscription.future.get();
        if (future != null) {
            future.cancel(true);
        }
        ownerLockService.release(subscription.consumer(), subscription.ownerToken());
        subscriptions.remove(key, subscription);
    }

    private final class RelayStreamHandler implements StreamHandler {
        private final String key;
        private final ResolvedProfileConsumer resolved;
        private final Subscription subscription;

        private RelayStreamHandler(String key, ResolvedProfileConsumer resolved, Subscription subscription) {
            this.key = key;
            this.resolved = resolved;
            this.subscription = subscription;
        }

        @Override
        public void onOpen(InputStream body) {
            subscription.body.set(body);
        }

        @Override
        public void onClosed() {
            subscription.body.set(null);
        }

        @Override
        public void onFrame(String streamCursor, ChannelOutboundFrame frame) {
            if (!subscription.running.get()) {
                throw new BackpressureReached();
            }
            if (streamCursor != null && !streamCursor.isBlank()) {
                lastStreamCursorByConsumer.put(key, streamCursor);
            }
            if (!capabilityFilter.allows(resolved.descriptor(), frame)) {
                return;
            }
            boolean accepted = handoff.offer(
                resolved.consumer(),
                frame,
                maxPendingFinals(resolved.consumer()),
                properties.getTransientQueueCapacity()
            );
            if (!accepted && frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY) {
                throw new BackpressureReached();
            }
        }

        @Override
        public void onFinalReplayWindowExhausted() {
            subscription.running.set(false);
        }
    }

    private static final class Subscription {
        private final ChannelOutboundProfileConsumer consumer;
        private final String ownerToken;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicReference<Future<?>> future = new AtomicReference<>();
        private final AtomicReference<InputStream> body = new AtomicReference<>();

        private Subscription(ChannelOutboundProfileConsumer consumer, String ownerToken) {
            this.consumer = consumer;
            this.ownerToken = ownerToken;
        }

        private ChannelOutboundProfileConsumer consumer() {
            return consumer;
        }

        private String ownerToken() {
            return ownerToken;
        }

        private boolean isDone() {
            Future<?> current = future.get();
            return current != null && current.isDone();
        }

        private void closeBody() {
            InputStream current = body.getAndSet(null);
            if (current == null) {
                return;
            }
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class BackpressureReached extends RuntimeException {
    }
}
