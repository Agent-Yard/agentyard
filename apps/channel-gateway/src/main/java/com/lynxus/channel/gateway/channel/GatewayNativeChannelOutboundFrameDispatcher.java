package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.channel.ChannelOutboundProfileConsumerResolver.ResolvedProfileConsumer;
import com.lynxus.channel.gateway.extension.GatewayNativeChannelProviderAdapter;
import com.lynxus.channel.gateway.extension.GatewayNativeChannelProviderAdapter.OutboundFrameDispatch;
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
import java.util.ArrayList;
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
                List<ChannelOutboundFrame> frames = handoff.drain(consumer, properties.getNativeDispatchBatchSize());
                if (frames.isEmpty()) {
                    completedNormally = true;
                    return;
                }
                consumeFrames(resolved, frames);
            }
        } finally {
            activeConsumers.remove(key);
            if (completedNormally && handoff.hasFrames(consumer)) {
                onFramesAvailable(consumer);
            }
        }
    }

    private void consumeFrames(ResolvedProfileConsumer resolved, List<ChannelOutboundFrame> drainedFrames) {
        ChannelOutboundProfileConsumer consumer = resolved.consumer();
        GatewayNativeChannelProviderAdapter adapter;
        List<OutboundFrameDispatch> dispatches;
        try {
            adapter = nativeAdapters.find(resolved.profile().providerType())
                .orElseThrow(() -> new IllegalStateException("missing gateway-native channel provider adapter: " + resolved.profile().providerType()));
            dispatches = adapter.prepareOutboundFrames(resolved.profile(), drainedFrames);
            if (dispatches == null) {
                throw new IllegalStateException("gateway-native channel provider adapter returned null outbound dispatches: " + resolved.profile().providerType());
            }
            validatePreparedDispatches(resolved.profile().providerType(), drainedFrames, dispatches);
        } catch (RuntimeException error) {
            requeueFirstPreservingOrder(consumer, drainedFrames);
            throw error;
        }
        for (int index = 0; index < dispatches.size(); index++) {
            if (!properties.isEnabled()) {
                requeueFirstPreservingOrder(consumer, unconsumedFrames(dispatches, index));
                return;
            }
            OutboundFrameDispatch dispatch = dispatches.get(index);
            try {
                consumeFrame(resolved, adapter, dispatch.frame());
            } catch (RuntimeException error) {
                if (containsFinalDelivery(dispatch.drainedFrames())) {
                    requeueFirstPreservingOrder(consumer, unconsumedFrames(dispatches, index));
                    throw error;
                }
                log.warn(
                    "dropping failed transient native outbound frames consumer={} count={}",
                    ProfileConsumerKeys.key(consumer),
                    dispatch.drainedFrames().size(),
                    error
                );
            }
        }
    }

    private void consumeFrame(
        ResolvedProfileConsumer resolved,
        GatewayNativeChannelProviderAdapter adapter,
        ChannelOutboundFrame frame
    ) {
        ChannelOutboundProfileConsumer consumer = resolved.consumer();
        if (frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY && alreadyAcked(consumer, frame)) {
            upstreamRelaySupervisor.reconcileSubscriptions();
            return;
        }

        adapter.consumeOutboundFrame(resolved.profile(), frame);

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

    private static List<ChannelOutboundFrame> unconsumedFrames(List<OutboundFrameDispatch> dispatches, int firstUnconsumed) {
        List<ChannelOutboundFrame> frames = new ArrayList<>();
        for (int index = firstUnconsumed; index < dispatches.size(); index++) {
            frames.addAll(dispatches.get(index).drainedFrames());
        }
        return frames;
    }

    private static boolean containsFinalDelivery(List<ChannelOutboundFrame> frames) {
        return frames.stream().anyMatch(frame -> frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY);
    }

    private static void validatePreparedDispatches(
        String providerType,
        List<ChannelOutboundFrame> drainedFrames,
        List<OutboundFrameDispatch> dispatches
    ) {
        List<ChannelOutboundFrame> covered = new ArrayList<>();
        for (OutboundFrameDispatch dispatch : dispatches) {
            validateFinalDeliveryDispatch(providerType, dispatch);
            covered.addAll(dispatch.drainedFrames());
        }
        if (!covered.equals(drainedFrames)) {
            throw new IllegalStateException("gateway-native channel provider adapter changed drained frame order or coverage: " + providerType);
        }
    }

    private static void validateFinalDeliveryDispatch(String providerType, OutboundFrameDispatch dispatch) {
        List<ChannelOutboundFrame> frames = dispatch.drainedFrames();
        boolean dispatchesFinal = dispatch.frame().kind() == ChannelOutboundFrameKind.FINAL_DELIVERY;
        boolean coversFinal = containsFinalDelivery(frames);
        if (!dispatchesFinal && !coversFinal) {
            return;
        }
        if (frames.size() != 1
            || frames.getFirst().kind() != ChannelOutboundFrameKind.FINAL_DELIVERY
            || !dispatch.frame().equals(frames.getFirst())) {
            throw new IllegalStateException("gateway-native FINAL_DELIVERY must be dispatched independently: " + providerType);
        }
    }

    private void requeueFirstPreservingOrder(ChannelOutboundProfileConsumer consumer, List<ChannelOutboundFrame> frames) {
        for (int index = frames.size() - 1; index >= 0; index--) {
            handoff.requeueFirst(consumer, frames.get(index));
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
