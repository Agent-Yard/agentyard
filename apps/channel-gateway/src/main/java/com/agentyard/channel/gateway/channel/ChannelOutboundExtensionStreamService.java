package com.agentyard.channel.gateway.channel;

import com.agentyard.channel.gateway.channel.ChannelOutboundExtensionAccessService.AuthorizedExtensionConsumer;
import com.agentyard.channel.gateway.shared.ConflictException;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChannelOutboundExtensionStreamService {
    private static final Logger log = LoggerFactory.getLogger(ChannelOutboundExtensionStreamService.class);
    private static final int DRAIN_LIMIT = 25;
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(20);
    private static final Duration MAX_IDLE_WAIT = Duration.ofSeconds(5);
    private static final Duration MIN_IDLE_WAIT = Duration.ofMillis(100);

    private final ChannelOutboundExtensionAccessService accessService;
    private final ChannelAdminRepository repository;
    private final ChannelOutboundExtensionStreamLeaseService leaseService;
    private final ChannelOutboundDownstreamConsumerRegistry downstreamRegistry;
    private final ChannelOutboundUpstreamRelaySupervisor upstreamRelaySupervisor;
    private final ChannelOutboundFrameHandoff handoff;
    private final ChannelOutboundForwardedPendingStore pendingStore;
    private final ChannelOutboundRelayProperties properties;
    private final ExecutorService executorService;
    private final AtomicLong streamCursorSequence = new AtomicLong();

    public ChannelOutboundExtensionStreamService(
        ChannelOutboundExtensionAccessService accessService,
        ChannelAdminRepository repository,
        ChannelOutboundExtensionStreamLeaseService leaseService,
        ChannelOutboundDownstreamConsumerRegistry downstreamRegistry,
        ChannelOutboundUpstreamRelaySupervisor upstreamRelaySupervisor,
        ChannelOutboundFrameHandoff handoff,
        ChannelOutboundForwardedPendingStore pendingStore,
        ChannelOutboundRelayProperties properties
    ) {
        this.accessService = accessService;
        this.repository = repository;
        this.leaseService = leaseService;
        this.downstreamRegistry = downstreamRegistry;
        this.upstreamRelaySupervisor = upstreamRelaySupervisor;
        this.handoff = handoff;
        this.pendingStore = pendingStore;
        this.properties = properties;
        this.executorService = Executors.newCachedThreadPool(Thread.ofVirtual().name("channel-outbound-extension-", 0).factory());
    }

    public SseEmitter open(String channelProfileId, ChannelOutboundExtensionHeaders headers, String lastEventId, Long diagnosticLastAckedFinalSequence) {
        AuthorizedExtensionConsumer authorized = accessService.requireConsumer(channelProfileId, headers);
        ChannelOutboundProfileConsumer consumer = authorized.consumer();
        String leaseToken = "extension-stream:" + ProfileConsumerKeys.key(consumer) + ":" + UUID.randomUUID();
        if (!leaseService.acquire(consumer, leaseToken, properties.getExtensionStreamLeaseTtl())) {
            throw new ConflictException("channel outbound extension stream already active for consumer: " + ProfileConsumerKeys.key(consumer));
        }

        repository.ensureOutboundFinalCheckpoint(consumer, java.time.Instant.now());
        pendingStore.clear(consumer);
        downstreamRegistry.markActive(consumer);
        upstreamRelaySupervisor.reconcileSubscriptions();

        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);
        Runnable cleanup = () -> cleanup(consumer, leaseToken, closed);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        executorService.submit(() -> pump(consumer, leaseToken, emitter, closed));
        log.info(
            "channel outbound extension stream connected consumer={} lastEventId={} diagnosticLastAckedFinalSequence={}",
            ProfileConsumerKeys.key(consumer),
            lastEventId,
            diagnosticLastAckedFinalSequence
        );
        return emitter;
    }

    @PreDestroy
    void shutdown() {
        executorService.shutdownNow();
    }

    private void pump(
        ChannelOutboundProfileConsumer consumer,
        String leaseToken,
        SseEmitter emitter,
        AtomicBoolean closed
    ) {
        long nextHeartbeatAt = System.currentTimeMillis();
        try {
            while (!closed.get()) {
                if (!leaseService.renew(consumer, leaseToken, properties.getExtensionStreamLeaseTtl())) {
                    throw new ConflictException("channel outbound extension stream lease lost for consumer: " + ProfileConsumerKeys.key(consumer));
                }
                long now = System.currentTimeMillis();
                if (now >= nextHeartbeatAt) {
                    sendHeartbeat(emitter);
                    nextHeartbeatAt = now + HEARTBEAT_INTERVAL.toMillis();
                }
                List<ChannelOutboundFrame> frames = handoff.drainOrWait(
                    consumer,
                    DRAIN_LIMIT,
                    idleWaitTimeout(now, nextHeartbeatAt, properties.getExtensionStreamLeaseTtl())
                );
                if (frames.isEmpty()) {
                    continue;
                }
                for (ChannelOutboundFrame frame : frames) {
                    if (frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY) {
                        pendingStore.markForwarded(consumer, frame);
                    }
                    sendFrame(emitter, frame);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (!closed.get()) {
                emitter.completeWithError(error);
            }
        } finally {
            cleanup(consumer, leaseToken, closed);
            emitter.complete();
        }
    }

    private void sendFrame(SseEmitter emitter, ChannelOutboundFrame frame) throws IOException {
        emitter.send(SseEmitter.event()
            .id(nextStreamCursor())
            .name(ChannelOutboundApiFrameStreamClient.CHANNEL_OUTBOUND_FRAME_EVENT)
            .data(frame));
    }

    private void sendHeartbeat(SseEmitter emitter) throws IOException {
        emitter.send(SseEmitter.event().comment("channel-outbound-heartbeat"));
    }

    private String nextStreamCursor() {
        return Long.toString(streamCursorSequence.incrementAndGet());
    }

    private static Duration idleWaitTimeout(long now, long nextHeartbeatAt, Duration leaseTtl) {
        long heartbeatWaitMillis = Math.max(1, nextHeartbeatAt - now);
        long leaseRenewWaitMillis = leaseRenewWaitMillis(leaseTtl);
        long waitMillis = Math.min(heartbeatWaitMillis, leaseRenewWaitMillis);
        return Duration.ofMillis(waitMillis);
    }

    private static long leaseRenewWaitMillis(Duration leaseTtl) {
        if (leaseTtl == null || leaseTtl.isNegative() || leaseTtl.isZero()) {
            return MAX_IDLE_WAIT.toMillis();
        }
        long thirdTtl = Math.max(1, leaseTtl.toMillis() / 3);
        long bounded = Math.min(MAX_IDLE_WAIT.toMillis(), thirdTtl);
        return Math.max(MIN_IDLE_WAIT.toMillis(), bounded);
    }

    private void cleanup(ChannelOutboundProfileConsumer consumer, String leaseToken, AtomicBoolean closed) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        downstreamRegistry.markInactive(consumer);
        leaseService.release(consumer, leaseToken);
        upstreamRelaySupervisor.reconcileSubscriptions();
        log.info("channel outbound extension stream disconnected consumer={}", ProfileConsumerKeys.key(consumer));
    }
}
