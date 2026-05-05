package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.channel.ChannelOutboundProfileConsumerResolver.ResolvedProfileConsumer;
import com.lynxus.channel.gateway.extension.GatewayNativeChannelProviderAdapter;
import com.lynxus.channel.gateway.extension.GatewayNativeChannelProviderAdapters;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameCheckpoint;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class GatewayNativeChannelOutboundFrameDispatcher {
    private static final Logger log = LoggerFactory.getLogger(GatewayNativeChannelOutboundFrameDispatcher.class);

    private final ChannelAdminRepository repository;
    private final ChannelOutboundProfileConsumerResolver consumerResolver;
    private final GatewayNativeChannelProviderAdapters nativeAdapters;
    private final ChannelOutboundFrameHandoff handoff;
    private final ChannelOutboundUpstreamRelaySupervisor upstreamRelaySupervisor;
    private final ChannelOutboundRelayProperties properties;
    private final Set<String> activeConsumers = ConcurrentHashMap.newKeySet();
    private final ExecutorService immediateDispatchExecutor;
    private final AtomicBoolean listenerRegistered = new AtomicBoolean();
    private final ChannelOutboundFrameHandoff.FrameAvailableListener frameAvailableListener = this::onFramesAvailable;

    public GatewayNativeChannelOutboundFrameDispatcher(
        ChannelAdminRepository repository,
        ChannelOutboundProfileConsumerResolver consumerResolver,
        GatewayNativeChannelProviderAdapters nativeAdapters,
        ChannelOutboundFrameHandoff handoff,
        ChannelOutboundUpstreamRelaySupervisor upstreamRelaySupervisor,
        ChannelOutboundRelayProperties properties
    ) {
        this.repository = repository;
        this.consumerResolver = consumerResolver;
        this.nativeAdapters = nativeAdapters;
        this.handoff = handoff;
        this.upstreamRelaySupervisor = upstreamRelaySupervisor;
        this.properties = properties;
        this.immediateDispatchExecutor = Executors.newCachedThreadPool(Thread.ofVirtual().name("channel-outbound-native-", 0).factory());
    }

    @PostConstruct
    void start() {
        if (listenerRegistered.compareAndSet(false, true)) {
            handoff.registerListener(frameAvailableListener);
        }
    }

    @PreDestroy
    void shutdown() {
        if (listenerRegistered.compareAndSet(true, false)) {
            handoff.unregisterListener(frameAvailableListener);
        }
        immediateDispatchExecutor.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${lynxus.channel-gateway.outbound-relay.scan-fixed-delay:PT5S}")
    public void dispatchAvailableFrames() {
        if (!properties.isEnabled()) {
            return;
        }
        for (ChannelGatewayProfile profile : repository.listProfiles()) {
            Optional<ResolvedProfileConsumer> resolved = consumerResolver.resolve(profile);
            if (resolved.isEmpty() || resolved.get().consumer().consumerKind() != ChannelOutboundConsumerKind.GATEWAY_NATIVE) {
                continue;
            }
            dispatchOne(resolved.get());
        }
    }

    void dispatchOne(ResolvedProfileConsumer resolved) {
        dispatchUntilIdle(resolved);
    }

    private void onFramesAvailable(ChannelOutboundProfileConsumer consumer) {
        if (!properties.isEnabled() || consumer.consumerKind() != ChannelOutboundConsumerKind.GATEWAY_NATIVE) {
            return;
        }
        if (activeConsumers.contains(ProfileConsumerKeys.key(consumer))) {
            return;
        }
        try {
            immediateDispatchExecutor.submit(() -> dispatchConsumer(consumer));
        } catch (RejectedExecutionException ignored) {
            // Shutdown races can occur while Spring is stopping the service.
        }
    }

    private void dispatchConsumer(ChannelOutboundProfileConsumer consumer) {
        try {
            Optional<ResolvedProfileConsumer> resolved = repository.findProfile(consumer.channelProfileId())
                .flatMap(consumerResolver::resolve)
                .filter(candidate -> candidate.consumer().consumerKind() == ChannelOutboundConsumerKind.GATEWAY_NATIVE)
                .filter(candidate -> ProfileConsumerKeys.key(candidate.consumer()).equals(ProfileConsumerKeys.key(consumer)));
            resolved.ifPresent(this::dispatchUntilIdle);
        } catch (RuntimeException error) {
            log.warn("channel outbound native immediate dispatch failed consumer={}", ProfileConsumerKeys.key(consumer), error);
        }
    }

    private void dispatchUntilIdle(ResolvedProfileConsumer resolved) {
        ChannelOutboundProfileConsumer consumer = resolved.consumer();
        String key = ProfileConsumerKeys.key(consumer);
        if (!activeConsumers.add(key)) {
            return;
        }
        boolean completedNormally = false;
        try {
            while (properties.isEnabled()) {
                List<ChannelOutboundFrame> frames = handoff.drain(consumer, 1);
                if (frames.isEmpty()) {
                    completedNormally = true;
                    return;
                }
                consumeFrame(resolved, frames.getFirst());
            }
        } finally {
            activeConsumers.remove(key);
            if (completedNormally && handoff.hasFrames(consumer)) {
                onFramesAvailable(consumer);
            }
        }
    }

    private void consumeFrame(ResolvedProfileConsumer resolved, ChannelOutboundFrame frame) {
        ChannelOutboundProfileConsumer consumer = resolved.consumer();
        if (frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY && alreadyAcked(consumer, frame)) {
            upstreamRelaySupervisor.reconcileSubscriptions();
            return;
        }

        try {
            GatewayNativeChannelProviderAdapter adapter = nativeAdapters.find(resolved.profile().providerType())
                .orElseThrow(() -> new IllegalStateException("missing gateway-native channel provider adapter: " + resolved.profile().providerType()));
            adapter.consumeOutboundFrame(resolved.profile(), frame);
        } catch (RuntimeException error) {
            if (frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY) {
                handoff.requeueFirst(consumer, frame);
            }
            throw error;
        }

        if (frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY) {
            repository.advanceOutboundFinalCheckpoint(
                consumer,
                frame.finalSequence(),
                frame.frameId(),
                frame.sessionId(),
                Objects.toString(frame.payload().get("sessionMessageId"), null),
                Instant.now()
            );
            upstreamRelaySupervisor.reconcileSubscriptions();
            log.info(
                "channel outbound native final acknowledged consumer={} finalSequence={} frameId={}",
                ProfileConsumerKeys.key(consumer),
                frame.finalSequence(),
                frame.frameId()
            );
        }
    }

    private boolean alreadyAcked(ChannelOutboundProfileConsumer consumer, ChannelOutboundFrame frame) {
        ChannelOutboundFrameCheckpoint checkpoint = repository.findOutboundFinalCheckpoint(consumer).orElse(null);
        return checkpoint != null
            && checkpoint.lastAckedFinalSequence() != null
            && frame.finalSequence() != null
            && frame.finalSequence() <= checkpoint.lastAckedFinalSequence();
    }
}
