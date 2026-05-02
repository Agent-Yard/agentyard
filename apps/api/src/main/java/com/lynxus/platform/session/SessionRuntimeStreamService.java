package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionContracts.AgentTurnStreamFrame;
import com.lynxus.contracts.session.SessionContracts.AgentTurnStreamFrameKind;
import com.lynxus.contracts.session.SessionContracts.SessionMessageBlockType;
import com.lynxus.contracts.session.SessionContracts.SessionReplyDraftOperation;
import com.lynxus.contracts.session.SessionContracts.StreamVisibility;
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
import java.util.Collections;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class SessionRuntimeStreamService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionRuntimeStreamService.class);
    private static final Set<AgentTurnStreamFrameKind> CUSTOMER_VISIBLE_FRAME_KINDS = EnumSet.of(
        AgentTurnStreamFrameKind.USER_NOTICE,
        AgentTurnStreamFrameKind.REPLY_BLOCK_STARTED,
        AgentTurnStreamFrameKind.REPLY_BLOCK_DELTA,
        AgentTurnStreamFrameKind.REPLY_BLOCK_SNAPSHOT,
        AgentTurnStreamFrameKind.REPLY_BLOCK_COMPLETED
    );
    private static final Set<String> CUSTOMER_USER_NOTICE_LABELS = Set.of(
        "PROCESSING",
        "CHECKING_ORDER",
        "CHECKING_INFORMATION",
        "SEARCHING_KNOWLEDGE",
        "PREPARING_REPLY",
        "COMPOSING_REPLY",
        "FINALIZING_REPLY"
    );
    private static final Set<String> CUSTOMER_USER_NOTICE_KEYS = Set.of("label", "text");
    private static final Set<String> CUSTOMER_REPLY_DRAFT_KEYS = Set.of("blockId", "blockType", "delta", "text");
    private static final Set<String> CUSTOMER_REPLY_COMPLETED_KEYS = Set.of("blockId", "block");
    private static final Set<String> CUSTOMER_TEXT_BLOCK_KEYS = Set.of("type", "text");
    private static final Set<String> INTERNAL_PAYLOAD_KEY_TOKENS = Set.of(
        "model",
        "provider",
        "prompt",
        "system",
        "privacy",
        "placeholder",
        "restore",
        "sanitize",
        "tool",
        "connector",
        "endpoint",
        "http",
        "credential",
        "secret",
        "token",
        "stack",
        "trace",
        "schema",
        "raw",
        "debug",
        "latency",
        "duration",
        "internal"
    );
    private static final Set<String> INTERNAL_TEXT_TOKENS = Set.of(
        "model",
        "provider",
        "prompt",
        "tool",
        "http",
        "credential",
        "secret",
        "stack trace",
        "placeholder",
        "privacy",
        "internal",
        "模型",
        "工具",
        "提示词",
        "系统提示"
    );

    private final SessionRuntimeRepository repository;
    private final SessionRuntimeReplayStore replayStore;
    private final RedisPubSubBus pubSubBus;
    private final RedisKeyspace keyspace;
    private final RedisJsonCodec codec;
    private final RedisSharedStateProperties properties;
    private final SessionChannelActivityRelay channelActivityRelay;
    private final Counter noticeMaterializedCounter;
    private final Counter fallbackPollCounter;
    private final Map<String, CopyOnWriteArraySet<Subscriber>> emittersBySession = new ConcurrentHashMap<>();
    private final Map<String, String> observedFingerprints = new ConcurrentHashMap<>();
    private AutoCloseable updatedSubscription;
    private AutoCloseable changeSubscription;

    private record Subscriber(SseEmitter emitter, Set<StreamVisibility> visibility) {
        private Subscriber {
            visibility = visibility == null || visibility.isEmpty()
                ? Set.of(StreamVisibility.CUSTOMER)
                : Set.copyOf(visibility);
        }
    }

    @Autowired
    public SessionRuntimeStreamService(
        SessionRuntimeRepository repository,
        SessionRuntimeReplayStore replayStore,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec,
        RedisSharedStateProperties properties,
        SessionChannelActivityRelay channelActivityRelay,
        MeterRegistry meterRegistry
    ) {
        this.repository = repository;
        this.replayStore = replayStore;
        this.pubSubBus = pubSubBus;
        this.keyspace = keyspace;
        this.codec = codec;
        this.properties = properties;
        this.channelActivityRelay = channelActivityRelay == null ? SessionChannelActivityRelay.noop() : channelActivityRelay;
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
        this(repository, replayStore, pubSubBus, keyspace, codec, properties, SessionChannelActivityRelay.noop(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
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
        return connect(
            sessionId,
            lastEventId,
            principalName,
            EnumSet.of(StreamVisibility.CUSTOMER, StreamVisibility.OPERATOR, StreamVisibility.DEVELOPER)
        );
    }

    public SseEmitter connect(
        String sessionId,
        String lastEventId,
        String principalName,
        Set<StreamVisibility> visibility
    ) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(emitter, visibility);
        emittersBySession.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArraySet<>()).add(subscriber);
        Runnable cleanup = () -> unregister(sessionId, subscriber);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        try {
            SessionRuntimeStreamReplayResult replay = replayStore.replayAfter(sessionId, lastEventId);
            if (replay.matchedLastEventId()) {
                replay.events().stream()
                    .filter(event -> isVisibleTo(event, subscriber.visibility()))
                    .forEach(event -> send(emitter, event));
            } else {
                SessionRuntimeStreamEvent snapshot = snapshotEvent(sessionId);
                replayStore.append(snapshot);
                send(emitter, snapshot);
            }
            repository.findSessionChangeStamp(sessionId)
                .ifPresent(stamp -> observedFingerprints.put(sessionId, stamp.fingerprint()));
        } catch (RuntimeException error) {
            unregister(sessionId, subscriber);
            throw error;
        }
        LOGGER.info("session runtime stream connected sessionId={} principal={}", sessionId, principalName);
        return emitter;
    }

    public boolean acceptStreamFrame(AgentTurnStreamFrame frame) {
        validateFrame(frame);
        channelActivityRelay.relay(frame);
        Optional<SessionRuntimeStreamEvent> event = projectFrame(frame);
        if (event.isEmpty()) {
            return true;
        }
        if (replayStore.append(event.orElseThrow())) {
            pubSubBus.publish(keyspace.sseChannelSessionUpdated(), codec.write(event.orElseThrow()));
        }
        return true;
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
        SessionRuntimeStreamEvent event = SessionRuntimeStreamEvent.snapshot(
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
        return SessionRuntimeStreamEvent.snapshot(
            snapshotEventId(detail.session()),
            "SESSION_SNAPSHOT",
            Instant.now(),
            sessionId,
            detail
        );
    }

    private static String snapshotEventId(SessionRuntimeDtos.SessionRuntimeSessionDto session) {
        return "session-snapshot:" + session.id() + ":" + session.latestMessageSequence() + ":" + session.latestEventSequence();
    }

    private SessionRuntimeDetailDto loadDetail(String sessionId) {
        return new SessionRuntimeDetailDto(
            repository.findSession(sessionId).orElseThrow(),
            repository.listMessages(sessionId),
            repository.listEvents(sessionId),
            repository.listPlaybookRuns(sessionId)
        );
    }

    private void pushToLocalEmitters(SessionRuntimeStreamEvent event) {
        CopyOnWriteArraySet<Subscriber> subscribers = emittersBySession.get(event.sessionId());
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        for (Subscriber subscriber : subscribers) {
            if (!isVisibleTo(event, subscriber.visibility())) {
                continue;
            }
            try {
                send(subscriber.emitter(), event);
            } catch (RuntimeException error) {
                unregister(event.sessionId(), subscriber);
            }
        }
    }

    private boolean hasLocalSubscribers(String sessionId) {
        CopyOnWriteArraySet<Subscriber> emitters = emittersBySession.get(sessionId);
        return emitters != null && !emitters.isEmpty();
    }

    private Optional<SessionRuntimeStreamEvent> projectFrame(AgentTurnStreamFrame frame) {
        if (frame.kind() == AgentTurnStreamFrameKind.FINAL_OUTCOME
            || frame.kind() == AgentTurnStreamFrameKind.PROVIDER_DEBUG
            || frame.visibility() == StreamVisibility.INTERNAL) {
            return Optional.empty();
        }
        return switch (frame.kind()) {
            case USER_NOTICE -> Optional.of(progressEvent(
                frame,
                "USER_NOTICE",
                "RUNNING",
                stringPayload(frame, "text", "正在处理")
            ));
            case TURN_STARTED -> Optional.of(progressEvent(frame, "TURN_STARTED", "STARTED", "已收到"));
            case MODEL_STARTED -> Optional.of(progressEvent(frame, "MODEL_STARTED", "RUNNING", "模型处理中"));
            case MODEL_COMPLETED -> Optional.of(progressEvent(
                frame,
                "MODEL_COMPLETED",
                stringPayload(frame, "status", "SUCCEEDED"),
                "模型处理完成"
            ));
            case ACTION_TOOL_STARTED -> Optional.of(progressEvent(
                frame,
                "ACTION_TOOL_STARTED",
                "RUNNING",
                stringPayload(frame, "toolName", "工具处理中")
            ));
            case ACTION_TOOL_ARGUMENT_DELTA -> Optional.of(progressEvent(
                frame,
                "ACTION_TOOL_ARGUMENT_DELTA",
                "RUNNING",
                "工具参数生成中"
            ));
            case ACTION_TOOL_COMPLETED -> Optional.of(progressEvent(
                frame,
                "ACTION_TOOL_COMPLETED",
                stringPayload(frame, "status", "SUCCEEDED"),
                stringPayload(frame, "toolName", "工具处理完成")
            ));
            case TOOL_PROGRESS -> Optional.of(progressEvent(
                frame,
                stringPayload(frame, "label", "TOOL_PROGRESS"),
                stringPayload(frame, "status", "RUNNING"),
                stringPayload(frame, "label", "正在处理")
            ));
            case REPLY_BLOCK_STARTED -> Optional.of(draftEvent(frame, SessionReplyDraftOperation.STARTED));
            case REPLY_BLOCK_DELTA -> Optional.of(draftEvent(frame, SessionReplyDraftOperation.DELTA));
            case REPLY_BLOCK_SNAPSHOT -> Optional.of(draftEvent(frame, SessionReplyDraftOperation.SNAPSHOT));
            case REPLY_BLOCK_COMPLETED -> Optional.of(draftEvent(frame, SessionReplyDraftOperation.COMPLETED));
            case ERROR -> Optional.of(SessionRuntimeStreamEvent.streamError(
                "stream-error:" + frame.sessionId() + ":" + frame.turnId() + ":" + frame.seq(),
                frame.occurredAt(),
                frame.sessionId(),
                frame.turnId(),
                stringPayload(frame, "code", "STREAM_ERROR"),
                stringPayload(frame, "message", "stream error"),
                booleanPayload(frame, "retryable", false),
                mapPayload(frame, "details")
            ));
            case PROVIDER_DEBUG, FINAL_OUTCOME -> Optional.empty();
        };
    }

    private SessionRuntimeStreamEvent progressEvent(
        AgentTurnStreamFrame frame,
        String phase,
        String status,
        String title
    ) {
        return SessionRuntimeStreamEvent.progress(
            "progress:" + frame.sessionId() + ":" + frame.turnId() + ":" + frame.seq(),
            frame.occurredAt(),
            frame.sessionId(),
            frame.turnId(),
            frame.visibility(),
            phase,
            status,
            title,
            mapPayload(frame, "detail")
        );
    }

    private SessionRuntimeStreamEvent draftEvent(AgentTurnStreamFrame frame, SessionReplyDraftOperation operation) {
        return SessionRuntimeStreamEvent.draft(
            "draft:" + frame.sessionId() + ":" + frame.turnId() + ":" + frame.seq(),
            frame.occurredAt(),
            frame.sessionId(),
            frame.turnId(),
            "draft:" + frame.turnId(),
            operation,
            stringPayload(frame, "blockId", "block-1"),
            blockTypePayload(frame),
            stringPayload(frame, "delta", null),
            replyDraftTextPayload(frame)
        );
    }

    private void validateFrame(AgentTurnStreamFrame frame) {
        if (frame == null) {
            throw badFrame("frame is required");
        }
        if (!AgentTurnStreamFrame.PROTOCOL.equals(frame.protocol())) {
            throw badFrame("unsupported stream protocol");
        }
        if (isBlank(frame.sessionId()) || isBlank(frame.turnId()) || isBlank(frame.turnExecutionId())) {
            throw badFrame("sessionId, turnId, and turnExecutionId are required");
        }
        if (frame.seq() <= 0) {
            throw badFrame("seq must be positive");
        }
        String expectedFrameId = frame.turnExecutionId() + ":" + frame.seq();
        if (!expectedFrameId.equals(frame.frameId())) {
            throw badFrame("frameId must equal turnExecutionId:seq");
        }
        if (frame.kind() == null || frame.visibility() == null || frame.occurredAt() == null) {
            throw badFrame("kind, visibility, and occurredAt are required");
        }
        validateCustomerFrame(frame);
    }

    private static ResponseStatusException badFrame(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static void validateCustomerFrame(AgentTurnStreamFrame frame) {
        if (frame.visibility() != StreamVisibility.CUSTOMER) {
            return;
        }
        if (!CUSTOMER_VISIBLE_FRAME_KINDS.contains(frame.kind())) {
            throw badFrame("customer stream frame kind is not allowed");
        }
        if (containsInternalPayloadField(frame.payload())) {
            throw badFrame("customer stream payload contains internal fields");
        }
        if (frame.kind() == AgentTurnStreamFrameKind.USER_NOTICE) {
            validateCustomerUserNotice(frame);
            return;
        }
        validateCustomerReplyDraft(frame);
    }

    private static void validateCustomerUserNotice(AgentTurnStreamFrame frame) {
        if (!CUSTOMER_USER_NOTICE_KEYS.containsAll(frame.payload().keySet())) {
            throw badFrame("customer user notice payload only allows label and text");
        }
        String label = stringPayload(frame, "label", null);
        String text = stringPayload(frame, "text", null);
        if (isBlank(label) || !CUSTOMER_USER_NOTICE_LABELS.contains(label)) {
            throw badFrame("customer user notice label is not allowed");
        }
        if (isBlank(text) || containsInternalTextToken(text)) {
            throw badFrame("customer user notice text is not allowed");
        }
    }

    private static void validateCustomerReplyDraft(AgentTurnStreamFrame frame) {
        if (frame.kind() == AgentTurnStreamFrameKind.REPLY_BLOCK_COMPLETED) {
            validateCustomerReplyCompleted(frame);
            return;
        }
        if (!CUSTOMER_REPLY_DRAFT_KEYS.containsAll(frame.payload().keySet())) {
            throw badFrame("customer reply draft payload only allows draft text fields");
        }
        String blockType = stringPayload(frame, "blockType", SessionMessageBlockType.TEXT.name());
        if (!SessionMessageBlockType.TEXT.name().equals(blockType)) {
            throw badFrame("customer reply draft only allows text blocks");
        }
        String delta = stringPayload(frame, "delta", null);
        String text = stringPayload(frame, "text", null);
        if (containsInternalTextToken(delta) || containsInternalTextToken(text)) {
            throw badFrame("customer reply draft text is not allowed");
        }
        if (frame.kind() == AgentTurnStreamFrameKind.REPLY_BLOCK_DELTA && isBlank(delta)) {
            throw badFrame("customer reply draft delta requires text");
        }
        if (frame.kind() == AgentTurnStreamFrameKind.REPLY_BLOCK_SNAPSHOT && isBlank(text)) {
            throw badFrame("customer reply draft snapshot requires text");
        }
    }

    private static void validateCustomerReplyCompleted(AgentTurnStreamFrame frame) {
        if (!frame.payload().keySet().equals(CUSTOMER_REPLY_COMPLETED_KEYS)) {
            throw badFrame("customer reply completed payload only allows blockId and block");
        }
        if (isBlank(stringPayload(frame, "blockId", null))) {
            throw badFrame("customer reply completed blockId is required");
        }
        Object block = frame.payload().get("block");
        if (!(block instanceof Map<?, ?> blockMap)) {
            throw badFrame("customer reply completed block is required");
        }
        if (!blockMap.keySet().equals(CUSTOMER_TEXT_BLOCK_KEYS)) {
            throw badFrame("customer reply completed text block contains unsupported fields");
        }
        Object type = blockMap.get("type");
        if (!SessionMessageBlockType.TEXT.name().equals(type)) {
            throw badFrame("customer reply completed only allows text blocks");
        }
        Object text = blockMap.get("text");
        if (!(text instanceof String textValue) || isBlank(textValue) || containsInternalTextToken(textValue)) {
            throw badFrame("customer reply completed text is not allowed");
        }
    }

    private static boolean containsInternalPayloadField(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase();
                if (INTERNAL_PAYLOAD_KEY_TOKENS.stream().anyMatch(key::contains)
                    || containsInternalPayloadField(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().anyMatch(SessionRuntimeStreamService::containsInternalPayloadField);
        }
        return false;
    }

    private static boolean containsInternalTextToken(String value) {
        if (value == null) {
            return false;
        }
        String text = value.toLowerCase();
        return INTERNAL_TEXT_TOKENS.stream().anyMatch(text::contains);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isVisibleTo(SessionRuntimeStreamEvent event, Set<StreamVisibility> visibility) {
        return event.visibility() == null || (
            event.visibility() != StreamVisibility.INTERNAL && visibility.contains(event.visibility())
        );
    }

    private static String stringPayload(AgentTurnStreamFrame frame, String key, String defaultValue) {
        Object value = frame.payload().get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static Boolean booleanPayload(AgentTurnStreamFrame frame, String key, boolean defaultValue) {
        Object value = frame.payload().get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapPayload(AgentTurnStreamFrame frame, String key) {
        Object value = frame.payload().get(key);
        return value instanceof Map<?, ?> map
            ? Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) map))
            : Map.of();
    }

    private static SessionMessageBlockType blockTypePayload(AgentTurnStreamFrame frame) {
        String value = replyBlockPayload(frame, "type", stringPayload(frame, "blockType", SessionMessageBlockType.TEXT.name()));
        try {
            return SessionMessageBlockType.valueOf(value);
        } catch (IllegalArgumentException error) {
            return SessionMessageBlockType.TEXT;
        }
    }

    private static String replyDraftTextPayload(AgentTurnStreamFrame frame) {
        return replyBlockPayload(frame, "text", stringPayload(frame, "text", null));
    }

    private static String replyBlockPayload(AgentTurnStreamFrame frame, String key, String defaultValue) {
        Object block = frame.payload().get("block");
        if (block instanceof Map<?, ?> blockMap) {
            Object value = blockMap.get(key);
            return value == null ? defaultValue : String.valueOf(value);
        }
        return defaultValue;
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

    private void unregister(String sessionId, Subscriber subscriber) {
        CopyOnWriteArraySet<Subscriber> emitters = emittersBySession.get(sessionId);
        if (emitters == null) {
            return;
        }
        emitters.remove(subscriber);
        if (emitters.isEmpty()) {
            emittersBySession.remove(sessionId);
            observedFingerprints.remove(sessionId);
        }
    }
}
