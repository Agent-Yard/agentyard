package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionRuntimeChangeNotice;
import com.lynxus.platform.session.SessionRuntimeDtos.SessionRuntimeDetailDto;
import com.lynxus.platform.session.SessionRuntimeStreamDtos.SessionRuntimeStreamEvent;
import com.lynxus.platform.session.SessionRuntimeStreamDtos.SessionRuntimeStreamReplayResult;
import com.lynxus.platform.shared.redis.RedisSharedStateProperties;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class SessionRuntimeStreamService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionRuntimeStreamService.class);

    private final SessionRuntimeRepository repository;
    private final SessionRuntimeReplayStore replayStore;
    private final RedisPubSubBus pubSubBus;
    private final RedisKeyspace keyspace;
    private final RedisJsonCodec codec;
    private final RedisSharedStateProperties properties;
    private final Counter noticeMaterializedCounter;
    private final Counter fallbackPollCounter;
    private final Map<String, CopyOnWriteArraySet<SseEmitter>> emittersBySession = new ConcurrentHashMap<>();
    private final Map<String, String> observedFingerprints = new ConcurrentHashMap<>();
    private AutoCloseable updatedSubscription;
    private AutoCloseable changeSubscription;

    @Autowired
    public SessionRuntimeStreamService(
        SessionRuntimeRepository repository,
        SessionRuntimeReplayStore replayStore,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec,
        RedisSharedStateProperties properties,
        MeterRegistry meterRegistry
    ) {
        this.repository = repository;
        this.replayStore = replayStore;
        this.pubSubBus = pubSubBus;
        this.keyspace = keyspace;
        this.codec = codec;
        this.properties = properties;
        this.noticeMaterializedCounter = Counter.builder("lynxus.shared_state.runtime.notice.materialized")
            .description("Number of runtime change notices materialized into SSE update events")
            .register(meterRegistry);
        this.fallbackPollCounter = Counter.builder("lynxus.shared_state.runtime.poll.fallback")
            .description("Number of runtime SSE updates materialized by polling fallback")
            .register(meterRegistry);
    }

    SessionRuntimeStreamService(
        SessionRuntimeRepository repository,
        SessionRuntimeReplayStore replayStore,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec,
        RedisSharedStateProperties properties
    ) {
        this(repository, replayStore, pubSubBus, keyspace, codec, properties, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @PostConstruct
    void subscribe() {
        updatedSubscription = pubSubBus.subscribe(
            keyspace.sseChannelSessionUpdated(),
            payload -> pushToLocalEmitters(codec.read(payload, SessionRuntimeStreamEvent.class))
        );
        changeSubscription = pubSubBus.subscribe(
            keyspace.sseChannelSessionChanged(),
            payload -> handleRuntimeChangeNotice(codec.read(payload, SessionRuntimeChangeNotice.class))
        );
    }

    @PreDestroy
    void close() throws Exception {
        if (updatedSubscription != null) {
            updatedSubscription.close();
        }
        if (changeSubscription != null) {
            changeSubscription.close();
        }
    }

    public SseEmitter connect(String sessionId, String lastEventId, String principalName) {
        SseEmitter emitter = new SseEmitter(0L);
        emittersBySession.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArraySet<>()).add(emitter);
        Runnable cleanup = () -> unregister(sessionId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        try {
            SessionRuntimeStreamReplayResult replay = replayStore.replayAfter(sessionId, lastEventId);
            if (replay.matchedLastEventId()) {
                replay.events().forEach(event -> send(emitter, event));
            } else {
                send(emitter, snapshotEvent(sessionId));
            }
            repository.findSessionChangeStamp(sessionId)
                .ifPresent(stamp -> observedFingerprints.put(sessionId, stamp.fingerprint()));
        } catch (RuntimeException error) {
            unregister(sessionId, emitter);
            throw error;
        }
        LOGGER.info("session runtime stream connected sessionId={} principal={}", sessionId, principalName);
        return emitter;
    }

    @Scheduled(fixedDelayString = "${lynxus.shared-state.stream-poll-interval:PT1S}")
    void pollSubscribedSessions() {
        Set<String> sessionIds = Set.copyOf(emittersBySession.keySet());
        for (String sessionId : sessionIds) {
            Optional<SessionRuntimeRepository.SessionRuntimeChangeStamp> stamp = repository.findSessionChangeStamp(sessionId);
            if (stamp.isEmpty()) {
                continue;
            }
            String fingerprint = stamp.orElseThrow().fingerprint();
            String previous = observedFingerprints.putIfAbsent(sessionId, fingerprint);
            if (previous == null || previous.equals(fingerprint)) {
                continue;
            }
            materializeUpdatedEvent(sessionId, fingerprint, true, true);
        }
    }

    void handleRuntimeChangeNotice(SessionRuntimeChangeNotice notice) {
        if (notice == null || notice.sessionId() == null || notice.fingerprint() == null) {
            return;
        }
        materializeUpdatedEvent(
            notice.sessionId(),
            notice.fingerprint(),
            false,
            hasLocalSubscribers(notice.sessionId())
        );
    }

    private void materializeUpdatedEvent(
        String sessionId,
        String fingerprint,
        boolean fromFallbackPoll,
        boolean trackObservedFingerprint
    ) {
        if (trackObservedFingerprint) {
            String previous = observedFingerprints.put(sessionId, fingerprint);
            if (fingerprint.equals(previous)) {
                return;
            }
        }
        SessionRuntimeStreamEvent event = new SessionRuntimeStreamEvent(
            "session-updated:" + fingerprint,
            "SESSION_UPDATED",
            Instant.now(),
            sessionId,
            loadDetail(sessionId)
        );
        if (replayStore.append(event)) {
            if (fromFallbackPoll) {
                fallbackPollCounter.increment();
            } else {
                noticeMaterializedCounter.increment();
            }
            pubSubBus.publish(keyspace.sseChannelSessionUpdated(), codec.write(event));
        }
    }

    private SessionRuntimeStreamEvent snapshotEvent(String sessionId) {
        SessionRuntimeDetailDto detail = loadDetail(sessionId);
        return new SessionRuntimeStreamEvent(
            "session-snapshot:" + sessionId + ":" + detail.session().latestEventSequence(),
            "SESSION_SNAPSHOT",
            Instant.now(),
            sessionId,
            detail
        );
    }

    private SessionRuntimeDetailDto loadDetail(String sessionId) {
        return new SessionRuntimeDetailDto(
            repository.findSession(sessionId).orElseThrow(),
            repository.listEvents(sessionId),
            repository.listPlaybookRuns(sessionId)
        );
    }

    private void pushToLocalEmitters(SessionRuntimeStreamEvent event) {
        CopyOnWriteArraySet<SseEmitter> emitters = emittersBySession.get(event.sessionId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                send(emitter, event);
            } catch (RuntimeException error) {
                unregister(event.sessionId(), emitter);
            }
        }
    }

    private boolean hasLocalSubscribers(String sessionId) {
        CopyOnWriteArraySet<SseEmitter> emitters = emittersBySession.get(sessionId);
        return emitters != null && !emitters.isEmpty();
    }

    private void send(SseEmitter emitter, SessionRuntimeStreamEvent event) {
        try {
            emitter.send(
                SseEmitter.event()
                    .id(event.id())
                    .name(event.type())
                    .data(event)
            );
        } catch (IOException error) {
            throw new IllegalStateException("failed to emit session runtime stream event", error);
        }
    }

    private void unregister(String sessionId, SseEmitter emitter) {
        CopyOnWriteArraySet<SseEmitter> emitters = emittersBySession.get(sessionId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersBySession.remove(sessionId);
            observedFingerprints.remove(sessionId);
        }
    }
}
