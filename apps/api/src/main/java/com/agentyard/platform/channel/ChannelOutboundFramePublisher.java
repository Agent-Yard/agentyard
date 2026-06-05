package com.agentyard.platform.channel;

import com.agentyard.contracts.channel.ChannelContracts;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.agentyard.persistence.session.SessionRuntimeStore;
import com.agentyard.platform.session.SessionRuntimeRepository;
import com.agentyard.platform.shared.redis.RedisSharedStateProperties;
import com.agentyard.shared.redis.RedisJsonCodec;
import com.agentyard.shared.redis.RedisKeyspace;
import com.agentyard.shared.redis.RedisPubSubBus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChannelOutboundFramePublisher {
    private static final Logger log = LoggerFactory.getLogger(ChannelOutboundFramePublisher.class);
    private static final String FRAME_EVENT = "channel-outbound-frame";
    private static final String FINAL_REPLAY_WINDOW_EXHAUSTED_EVENT = "final-replay-window-exhausted";
    private static final int DEFAULT_MAX_FINAL_REPLAY_FRAMES = 100;
    private static final int SERVER_MAX_FINAL_REPLAY_FRAMES = 1_000;

    private final SessionRuntimeRepository repository;
    private final RedisPubSubBus pubSubBus;
    private final RedisKeyspace keyspace;
    private final RedisJsonCodec codec;
    private final RedisSharedStateProperties properties;
    private final ChannelOutboundFrameStreamProperties streamProperties;
    private final Function<Long, SseEmitter> emitterFactory;
    private final ScheduledExecutorService heartbeatExecutor;
    private final AtomicLong localStreamSequence = new AtomicLong();
    private final Map<String, ArrayDeque<StoredFrame>> transientReplayByProfile = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArraySet<Subscriber>> subscribersByProfile = new ConcurrentHashMap<>();
    private AutoCloseable subscription;

    @Autowired
    public ChannelOutboundFramePublisher(
        SessionRuntimeRepository repository,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec,
        RedisSharedStateProperties properties,
        ChannelOutboundFrameStreamProperties streamProperties
    ) {
        this(repository, pubSubBus, keyspace, codec, properties, streamProperties, SseEmitter::new, newHeartbeatExecutor());
    }

    ChannelOutboundFramePublisher(
        SessionRuntimeRepository repository,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec,
        RedisSharedStateProperties properties,
        ChannelOutboundFrameStreamProperties streamProperties,
        Function<Long, SseEmitter> emitterFactory,
        ScheduledExecutorService heartbeatExecutor
    ) {
        this.repository = repository;
        this.pubSubBus = pubSubBus;
        this.keyspace = keyspace;
        this.codec = codec;
        this.properties = properties;
        this.streamProperties = streamProperties;
        this.emitterFactory = emitterFactory;
        this.heartbeatExecutor = heartbeatExecutor;
    }

    @PostConstruct
    void subscribe() {
        subscription = pubSubBus.subscribe(keyspace.channelOutboundFrameChannel(), this::handleBroadcast);
    }

    @PreDestroy
    void close() throws Exception {
        AutoCloseable current = subscription;
        subscription = null;
        if (current != null) {
            current.close();
        }
        subscribersByProfile.values().forEach(subscribers -> subscribers.forEach(subscriber -> {
            subscriber.closed().set(true);
            cancelHeartbeat(subscriber);
            subscriber.emitter().complete();
        }));
        subscribersByProfile.clear();
        heartbeatExecutor.shutdownNow();
    }

    public SseEmitter connect(
        String channelProfileId,
        String lastEventId,
        Long lastAckedFinalSequence,
        Integer maxFinalReplayFrames
    ) {
        String profileId = requireText(channelProfileId, "channelProfileId");
        long checkpoint = normalizeCheckpoint(lastAckedFinalSequence);
        int replayLimit = normalizeMaxFinalReplayFrames(maxFinalReplayFrames);
        SseEmitter emitter = Objects.requireNonNull(emitterFactory.apply(0L), "emitter");
        Subscriber subscriber = new Subscriber(
            profileId,
            emitter,
            new AtomicLong(checkpoint),
            new AtomicReference<>(),
            new AtomicBoolean(false)
        );
        subscribersByProfile.computeIfAbsent(profileId, ignored -> new CopyOnWriteArraySet<>()).add(subscriber);
        Runnable cleanup = () -> unregister(profileId, subscriber);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            replayTransient(profileId, lastEventId).forEach(stored -> sendFrame(emitter, stored.streamCursor(), stored.frame()));
            if (emitFinalReplay(subscriber, replayLimit).exhausted()) {
                cleanup.run();
            } else {
                scheduleHeartbeat(subscriber);
            }
        } catch (RuntimeException error) {
            cleanup.run();
            throw error;
        }
        log.info(
            "channel outbound frame stream connected channelProfileId={} lastAckedFinalSequence={} maxFinalReplayFrames={}",
            profileId,
            checkpoint,
            replayLimit
        );
        return emitter;
    }

    public void publishTransient(ChannelOutboundFrame frame) {
        if (frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY) {
            throw new IllegalArgumentException("publishTransient does not accept FINAL_DELIVERY frames");
        }
        StoredFrame stored = new StoredFrame(nextStreamCursor(), frame, Instant.now());
        appendTransient(stored);
        emitTransient(stored);
        pubSubBus.publish(keyspace.channelOutboundFrameChannel(), codec.write(Broadcast.transientFrame(properties.instanceId(), stored)));
    }

    public void notifyFinalAvailableForSession(String channelProfileId) {
        String profileId = requireText(channelProfileId, "channelProfileId");
        emitFinalAvailable(profileId);
        pubSubBus.publish(keyspace.channelOutboundFrameChannel(), codec.write(Broadcast.finalAvailable(properties.instanceId(), profileId)));
    }

    private void handleBroadcast(String payload) {
        Broadcast broadcast = codec.read(payload, Broadcast.class);
        if (broadcast == null || properties.instanceId().equals(broadcast.sourceInstanceId())) {
            return;
        }
        if (broadcast.type() == BroadcastType.TRANSIENT_FRAME && broadcast.storedFrame() != null) {
            appendTransient(broadcast.storedFrame());
            emitTransient(broadcast.storedFrame());
            return;
        }
        if (broadcast.type() == BroadcastType.FINAL_AVAILABLE && broadcast.channelProfileId() != null) {
            emitFinalAvailable(broadcast.channelProfileId());
        }
    }

    private void emitFinalAvailable(String channelProfileId) {
        CopyOnWriteArraySet<Subscriber> subscribers = subscribersByProfile.get(channelProfileId);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        for (Subscriber subscriber : subscribers) {
            ReplayResult replayResult = emitFinalReplay(subscriber, DEFAULT_MAX_FINAL_REPLAY_FRAMES);
            if (replayResult.exhausted()) {
                unregister(channelProfileId, subscriber);
            }
        }
    }

    private ReplayResult emitFinalReplay(Subscriber subscriber, int maxFinalReplayFrames) {
        synchronized (subscriber) {
            int limit = normalizeMaxFinalReplayFrames(maxFinalReplayFrames);
            long afterFinalSequence = subscriber.lastSentFinalSequence().get();
            List<ChannelOutboundFrame> frames = deriveFinalFrames(subscriber.channelProfileId(), afterFinalSequence, limit + 1);
            boolean exhausted = frames.size() > limit;
            List<ChannelOutboundFrame> toEmit = exhausted ? frames.subList(0, limit) : frames;
            long lastEmittedFinalSequence = afterFinalSequence;
            int emitted = 0;
            for (ChannelOutboundFrame frame : toEmit) {
                sendFrame(subscriber.emitter(), nextStreamCursor(), frame);
                lastEmittedFinalSequence = frame.finalSequence();
                subscriber.lastSentFinalSequence().set(lastEmittedFinalSequence);
                emitted += 1;
            }
            if (exhausted) {
                sendFinalReplayWindowExhausted(subscriber.emitter(), emitted, lastEmittedFinalSequence);
                subscriber.emitter().complete();
            }
            return new ReplayResult(emitted, lastEmittedFinalSequence, exhausted);
        }
    }

    private List<ChannelOutboundFrame> deriveFinalFrames(String channelProfileId, long afterFinalSequence, int limit) {
        return repository.listChannelOutboundFinalMessages(channelProfileId, afterFinalSequence, limit)
            .stream()
            .map(ChannelOutboundFramePublisher::toFinalFrame)
            .toList();
    }

    private static ChannelOutboundFrame toFinalFrame(SessionRuntimeStore.ChannelOutboundFinalMessageData message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionMessageId", message.messageId());
        payload.put("messageSequence", message.messageSequence());
        payload.put("messageBlocks", message.blocks());
        Object resolvedTemplate = message.metadata().get("resolvedTemplate");
        if (resolvedTemplate != null) {
            payload.put("resolvedTemplate", resolvedTemplate);
        }
        String frameId = message.channelProfileId() + ":" + message.sessionId() + ":" + message.messageId() + ":FINAL_DELIVERY";
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            frameId,
            message.channelProfileId(),
            message.providerType(),
            message.assistantId(),
            message.externalConversationId(),
            message.sessionId(),
            null,
            null,
            null,
            message.finalSequence(),
            ChannelOutboundFrameKind.FINAL_DELIVERY,
            message.updatedAt() == null ? message.createdAt() : message.updatedAt(),
            ChannelContracts.channelOutboundFrameIdempotencyKey(frameId),
            Map.copyOf(payload),
            null
        );
    }

    private void appendTransient(StoredFrame stored) {
        ArrayDeque<StoredFrame> frames = transientReplayByProfile.computeIfAbsent(stored.frame().channelProfileId(), ignored -> new ArrayDeque<>());
        synchronized (frames) {
            removeExpiredTransientFrames(frames);
            frames.addLast(stored);
            while (frames.size() > properties.sseReplayLimit()) {
                frames.removeFirst();
            }
        }
    }

    private List<StoredFrame> replayTransient(String channelProfileId, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return List.of();
        }
        ArrayDeque<StoredFrame> frames = transientReplayByProfile.get(channelProfileId);
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }
        synchronized (frames) {
            removeExpiredTransientFrames(frames);
            boolean found = false;
            List<StoredFrame> replay = new ArrayList<>();
            for (StoredFrame frame : frames) {
                if (found) {
                    replay.add(frame);
                } else if (lastEventId.equals(frame.streamCursor())) {
                    found = true;
                }
            }
            return found ? List.copyOf(replay) : List.of();
        }
    }

    private void removeExpiredTransientFrames(ArrayDeque<StoredFrame> frames) {
        Instant expiresBefore = Instant.now().minus(properties.sseReplayTtl());
        while (!frames.isEmpty()
            && frames.peekFirst().storedAt() != null
            && frames.peekFirst().storedAt().isBefore(expiresBefore)) {
            frames.removeFirst();
        }
    }

    private void emitTransient(StoredFrame stored) {
        CopyOnWriteArraySet<Subscriber> subscribers = subscribersByProfile.get(stored.frame().channelProfileId());
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        for (Subscriber subscriber : subscribers) {
            try {
                sendFrame(subscriber.emitter(), stored.streamCursor(), stored.frame());
            } catch (RuntimeException error) {
                unregister(stored.frame().channelProfileId(), subscriber);
            }
        }
    }

    void emitHeartbeats() {
        subscribersByProfile.forEach((channelProfileId, subscribers) -> {
            for (Subscriber subscriber : subscribers) {
                emitHeartbeat(channelProfileId, subscriber);
            }
        });
    }

    private void emitHeartbeat(String channelProfileId, Subscriber subscriber) {
        try {
            sendHeartbeat(subscriber.emitter());
        } catch (RuntimeException error) {
            unregister(channelProfileId, subscriber);
        }
    }

    private void sendFrame(SseEmitter emitter, String streamCursor, ChannelOutboundFrame frame) {
        try {
            emitter.send(SseEmitter.event()
                .id(streamCursor)
                .name(FRAME_EVENT)
                .data(frame));
        } catch (IOException error) {
            throw new IllegalStateException("failed to emit channel outbound frame", error);
        }
    }

    private void sendHeartbeat(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("channel-outbound-heartbeat"));
        } catch (IOException error) {
            throw new IllegalStateException("failed to emit channel outbound heartbeat", error);
        }
    }

    private void sendFinalReplayWindowExhausted(SseEmitter emitter, int emitted, long lastEmittedFinalSequence) {
        try {
            emitter.send(SseEmitter.event()
                .name(FINAL_REPLAY_WINDOW_EXHAUSTED_EVENT)
                .data(new FinalReplayWindowExhausted(emitted, lastEmittedFinalSequence)));
        } catch (IOException error) {
            throw new IllegalStateException("failed to emit channel outbound final replay window exhaustion", error);
        }
    }

    private void unregister(String channelProfileId, Subscriber subscriber) {
        subscriber.closed().set(true);
        CopyOnWriteArraySet<Subscriber> subscribers = subscribersByProfile.get(channelProfileId);
        if (subscribers == null) {
            cancelHeartbeat(subscriber);
            return;
        }
        subscribers.remove(subscriber);
        cancelHeartbeat(subscriber);
        if (subscribers.isEmpty()) {
            subscribersByProfile.remove(channelProfileId);
        }
    }

    private String nextStreamCursor() {
        return properties.instanceId() + ":" + localStreamSequence.incrementAndGet();
    }

    private static long normalizeCheckpoint(Long value) {
        if (value == null) {
            return 0L;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("lastAckedFinalSequence must be positive");
        }
        return value;
    }

    private static int normalizeMaxFinalReplayFrames(Integer value) {
        int requested = value == null ? DEFAULT_MAX_FINAL_REPLAY_FRAMES : value;
        if (requested <= 0) {
            throw new IllegalArgumentException("maxFinalReplayFrames must be positive");
        }
        return Math.min(requested, SERVER_MAX_FINAL_REPLAY_FRAMES);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private void scheduleHeartbeat(Subscriber subscriber) {
        long heartbeatIntervalMillis = Math.max(1L, streamProperties.heartbeatInterval().toMillis());
        ScheduledFuture<?> task = heartbeatExecutor.scheduleAtFixedRate(
            () -> emitHeartbeat(subscriber.channelProfileId(), subscriber),
            heartbeatIntervalMillis,
            heartbeatIntervalMillis,
            TimeUnit.MILLISECONDS
        );
        if (subscriber.closed().get()) {
            task.cancel(true);
            return;
        }
        subscriber.heartbeatTask().set(task);
        if (subscriber.closed().get()) {
            cancelHeartbeat(subscriber);
        }
    }

    private static void cancelHeartbeat(Subscriber subscriber) {
        ScheduledFuture<?> task = subscriber.heartbeatTask().getAndSet(null);
        if (task != null) {
            task.cancel(true);
        }
    }

    private static ScheduledExecutorService newHeartbeatExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "channel-outbound-frame-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    public record FinalReplayWindowExhausted(int emitted, long lastEmittedFinalSequence) {
    }

    public enum BroadcastType {
        TRANSIENT_FRAME,
        FINAL_AVAILABLE
    }

    public record Broadcast(
        BroadcastType type,
        String sourceInstanceId,
        String channelProfileId,
        StoredFrame storedFrame
    ) {
        static Broadcast transientFrame(String sourceInstanceId, StoredFrame storedFrame) {
            return new Broadcast(BroadcastType.TRANSIENT_FRAME, sourceInstanceId, storedFrame.frame().channelProfileId(), storedFrame);
        }

        static Broadcast finalAvailable(String sourceInstanceId, String channelProfileId) {
            return new Broadcast(BroadcastType.FINAL_AVAILABLE, sourceInstanceId, channelProfileId, null);
        }
    }

    public record StoredFrame(String streamCursor, ChannelOutboundFrame frame, Instant storedAt) {
    }

    private record ReplayResult(int emitted, long lastEmittedFinalSequence, boolean exhausted) {
    }

    private record Subscriber(
        String channelProfileId,
        SseEmitter emitter,
        AtomicLong lastSentFinalSequence,
        AtomicReference<ScheduledFuture<?>> heartbeatTask,
        AtomicBoolean closed
    ) {
    }
}
