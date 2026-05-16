package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionContracts.AgentTurnTransientFrame;
import com.lynxus.contracts.session.SessionContracts.AgentTurnTransientFrameKind;
import com.lynxus.contracts.session.SessionContracts.ErrorPayload;
import com.lynxus.contracts.session.SessionContracts.ModelCompletedPayload;
import com.lynxus.contracts.session.SessionContracts.ReplyBlockCompletedPayload;
import com.lynxus.contracts.session.SessionContracts.ReplyBlockDeltaPayload;
import com.lynxus.contracts.session.SessionContracts.SessionMessageBlockType;
import com.lynxus.contracts.session.SessionContracts.SessionReplyDraftOperation;
import com.lynxus.contracts.session.SessionContracts.StreamVisibility;
import com.lynxus.contracts.session.SessionContracts.TextMessageBlock;
import com.lynxus.contracts.session.SessionContracts.ToolCompletedPayload;
import com.lynxus.contracts.session.SessionContracts.ToolStartedPayload;
import com.lynxus.contracts.session.SessionContracts.TurnCompletedPayload;
import com.lynxus.contracts.session.SessionContracts.TurnCompletionStatus;
import com.lynxus.contracts.session.SessionContracts.TurnStartedPayload;
import com.lynxus.contracts.session.SessionRuntimeChangeNotice;
import com.lynxus.platform.session.SessionRuntimeDtos.SessionRuntimeDetailDto;
import com.lynxus.platform.session.SessionRuntimeStreamDtos.SessionRuntimeStreamEvent;
import com.lynxus.platform.session.SessionRuntimeStreamDtos.SessionRuntimeStreamReplayResult;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class SessionRuntimeStreamService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionRuntimeStreamService.class);
    private static final Set<AgentTurnTransientFrameKind> CUSTOMER_VISIBLE_FRAME_KINDS = EnumSet.of(
        AgentTurnTransientFrameKind.REPLY_BLOCK_DELTA,
        AgentTurnTransientFrameKind.REPLY_BLOCK_COMPLETED
    );
    private static final Set<String> CUSTOMER_TEXT_BLOCK_KEYS = Set.of("type", "text");
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
    private final SessionChannelActivityRelay channelActivityRelay;
    private final SessionRuntimeStreamFrameDeduplicator frameDeduplicator;
    private final Function<Long, SseEmitter> emitterFactory;
    private final Counter noticeMaterializedCounter;
    private final Counter fallbackPollCounter;
    private final Counter actionToolStartedCounter;
    private final Counter rejectedCustomerFrameCounter;
    private final Timer ttftTimer;
    private final Timer assistantTextStreamDurationTimer;
    private final Map<String, CopyOnWriteArraySet<Subscriber>> emittersBySession = new ConcurrentHashMap<>();
    private final Map<String, String> observedFingerprints = new ConcurrentHashMap<>();
    private final Map<String, Instant> turnStartedAtByExecution = new ConcurrentHashMap<>();
    private final Map<String, Instant> assistantTextStartedAtByExecution = new ConcurrentHashMap<>();
    private final Set<String> ttftRecordedExecutions = ConcurrentHashMap.newKeySet();
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
        SessionChannelActivityRelay channelActivityRelay,
        MeterRegistry meterRegistry,
        SessionRuntimeStreamFrameDeduplicator frameDeduplicator
    ) {
        this(
            repository,
            replayStore,
            pubSubBus,
            keyspace,
            codec,
            channelActivityRelay,
            meterRegistry,
            SseEmitter::new,
            frameDeduplicator
        );
    }

    SessionRuntimeStreamService(
        SessionRuntimeRepository repository,
        SessionRuntimeReplayStore replayStore,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec,
        SessionChannelActivityRelay channelActivityRelay,
        MeterRegistry meterRegistry
    ) {
        this(
            repository,
            replayStore,
            pubSubBus,
            keyspace,
            codec,
            channelActivityRelay,
            meterRegistry,
            SseEmitter::new,
            SessionRuntimeStreamFrameDeduplicator.noop()
        );
    }

    SessionRuntimeStreamService(
        SessionRuntimeRepository repository,
        SessionRuntimeReplayStore replayStore,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec,
        Function<Long, SseEmitter> emitterFactory
    ) {
        this(
            repository,
            replayStore,
            pubSubBus,
            keyspace,
            codec,
            SessionChannelActivityRelay.noop(),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            emitterFactory,
            SessionRuntimeStreamFrameDeduplicator.noop()
        );
    }

    private SessionRuntimeStreamService(
        SessionRuntimeRepository repository,
        SessionRuntimeReplayStore replayStore,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec,
        SessionChannelActivityRelay channelActivityRelay,
        MeterRegistry meterRegistry,
        Function<Long, SseEmitter> emitterFactory,
        SessionRuntimeStreamFrameDeduplicator frameDeduplicator
    ) {
        this.repository = repository;
        this.replayStore = replayStore;
        this.pubSubBus = pubSubBus;
        this.keyspace = keyspace;
        this.codec = codec;
        this.channelActivityRelay = channelActivityRelay == null ? SessionChannelActivityRelay.noop() : channelActivityRelay;
        this.frameDeduplicator = frameDeduplicator == null
            ? SessionRuntimeStreamFrameDeduplicator.noop()
            : frameDeduplicator;
        this.emitterFactory = Objects.requireNonNull(emitterFactory, "emitterFactory");
        this.noticeMaterializedCounter = Counter.builder("lynxus.shared_state.runtime.notice.materialized")
            .description("Number of runtime change notices materialized into SSE update events")
            .register(meterRegistry);
        this.fallbackPollCounter = Counter.builder("lynxus.shared_state.runtime.poll.fallback")
            .description("Number of runtime SSE updates materialized by polling fallback")
            .register(meterRegistry);
        this.actionToolStartedCounter = Counter.builder("lynxus.runtime_stream.action_tool.started")
            .description("Number of action tool calls started in runtime stream frames")
            .register(meterRegistry);
        this.rejectedCustomerFrameCounter = Counter.builder("lynxus.runtime_stream.customer_frame.rejected")
            .description("Number of rejected customer-visible runtime stream frames")
            .register(meterRegistry);
        this.ttftTimer = Timer.builder("lynxus.runtime_stream.ttft")
            .description("Time from TURN_STARTED to first assistant text delta")
            .register(meterRegistry);
        this.assistantTextStreamDurationTimer = Timer.builder("lynxus.runtime_stream.assistant_text.duration")
            .description("Duration of assistant customer-visible text draft streaming")
            .register(meterRegistry);
    }

    SessionRuntimeStreamService(
        SessionRuntimeRepository repository,
        SessionRuntimeReplayStore replayStore,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec
    ) {
        this(repository, replayStore, pubSubBus, keyspace, codec, SessionChannelActivityRelay.noop(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
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

    @EventListener(ContextClosedEvent.class)
    void onContextClosed() {
        try {
            close();
        } catch (Exception error) {
            LOGGER.warn("failed to close session runtime stream service before context shutdown", error);
        }
    }

    @PreDestroy
    void close() throws Exception {
        Exception failure = null;
        AutoCloseable subscription = updatedSubscription;
        updatedSubscription = null;
        failure = closeSubscription(subscription, failure);

        subscription = changeSubscription;
        changeSubscription = null;
        failure = closeSubscription(subscription, failure);

        completeLocalEmitters();

        if (failure != null) {
            throw failure;
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
        SseEmitter emitter = Objects.requireNonNull(emitterFactory.apply(0L), "emitter");
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

    public boolean acceptStreamFrame(AgentTurnTransientFrame frame) {
        try {
            validateFrame(frame);
        } catch (ResponseStatusException error) {
            recordRejectedFrame(frame, error);
            throw error;
        }
        if (!frameDeduplicator.claim(frame)) {
            recordDuplicateFrame(frame);
            return false;
        }
        recordAcceptedFrame(frame);
        channelActivityRelay.relay(frame);
        for (SessionRuntimeStreamEvent event : projectFrame(frame)) {
            if (replayStore.append(event)) {
                pubSubBus.publish(keyspace.sseChannelSessionUpdated(), codec.write(event));
            }
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

    private List<SessionRuntimeStreamEvent> projectFrame(AgentTurnTransientFrame frame) {
        if (frame.visibility() == StreamVisibility.INTERNAL) {
            return List.of();
        }
        return switch (frame.kind()) {
            case TURN_STARTED -> List.of(turnStartedProgressEvent(frame));
            case MODEL_STARTED -> List.of(progressEvent(frame, "MODEL_STARTED", "RUNNING", "模型处理中"));
            case MODEL_COMPLETED -> List.of(modelCompletedProgressEvent(frame));
            case ACTION_TOOL_STARTED -> List.of(toolStartedProgressEvent(frame));
            case ACTION_TOOL_COMPLETED -> List.of(toolCompletedProgressEvent(frame));
            case REPLY_BLOCK_DELTA -> List.of(draftEvent(frame, SessionReplyDraftOperation.DELTA));
            case REPLY_BLOCK_COMPLETED -> List.of(draftEvent(frame, SessionReplyDraftOperation.COMPLETED));
            case TURN_COMPLETED -> List.of(turnCompletedProgressEvent(frame));
            case ERROR -> errorEvents(frame);
        };
    }

    private SessionRuntimeStreamEvent turnStartedProgressEvent(AgentTurnTransientFrame frame) {
        TurnStartedPayload payload = (TurnStartedPayload) frame.payload();
        return progressEvent(
            frame,
            "TURN_STARTED",
            "STARTED",
            "已收到",
            Map.of("replyMessageId", payload.replyMessageId())
        );
    }

    private SessionRuntimeStreamEvent turnCompletedProgressEvent(AgentTurnTransientFrame frame) {
        TurnCompletedPayload payload = (TurnCompletedPayload) frame.payload();
        return progressEvent(
            frame,
            "TURN_COMPLETED",
            payload.status().name(),
            payload.status() == TurnCompletionStatus.SUCCEEDED ? "处理完成" : "处理失败",
            Map.of("replyMessageId", payload.replyMessageId())
        );
    }

    private SessionRuntimeStreamEvent modelCompletedProgressEvent(AgentTurnTransientFrame frame) {
        ModelCompletedPayload payload = (ModelCompletedPayload) frame.payload();
        return progressEvent(frame, "MODEL_COMPLETED", payload.status().name(), "模型处理完成");
    }

    private SessionRuntimeStreamEvent toolStartedProgressEvent(AgentTurnTransientFrame frame) {
        ToolStartedPayload payload = (ToolStartedPayload) frame.payload();
        return progressEvent(frame, "ACTION_TOOL_STARTED", "RUNNING", payload.toolName());
    }

    private SessionRuntimeStreamEvent toolCompletedProgressEvent(AgentTurnTransientFrame frame) {
        ToolCompletedPayload payload = (ToolCompletedPayload) frame.payload();
        return progressEvent(frame, "ACTION_TOOL_COMPLETED", payload.status().name(), payload.toolName());
    }

    private List<SessionRuntimeStreamEvent> errorEvents(AgentTurnTransientFrame frame) {
        ErrorPayload payload = (ErrorPayload) frame.payload();
        List<SessionRuntimeStreamEvent> events = new ArrayList<>();
        events.add(draftEvent(frame, SessionReplyDraftOperation.DISCARD));
        events.add(SessionRuntimeStreamEvent.streamError(
            "stream-error:" + frame.sessionId() + ":" + frame.turnId() + ":" + frame.seq(),
            frame.occurredAt(),
            frame.sessionId(),
            frame.turnId(),
            payload.code(),
            payload.message(),
            payload.retryable(),
            payload.details()
        ));
        return List.copyOf(events);
    }

    private SessionRuntimeStreamEvent progressEvent(
        AgentTurnTransientFrame frame,
        String phase,
        String status,
        String title
    ) {
        return progressEvent(frame, phase, status, title, Map.of());
    }

    private SessionRuntimeStreamEvent progressEvent(
        AgentTurnTransientFrame frame,
        String phase,
        String status,
        String title,
        Map<String, Object> detail
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
            detail
        );
    }

    private SessionRuntimeStreamEvent draftEvent(AgentTurnTransientFrame frame, SessionReplyDraftOperation operation) {
        return SessionRuntimeStreamEvent.draft(
            "draft:" + frame.sessionId() + ":" + frame.turnId() + ":" + frame.seq(),
            frame.occurredAt(),
            frame.sessionId(),
            frame.turnId(),
            replyMessageIdPayload(frame),
            operation,
            replyBlockIdPayload(frame),
            blockTypePayload(frame),
            replyDeltaPayload(frame),
            replyDraftTextPayload(frame)
        );
    }

    private void recordAcceptedFrame(AgentTurnTransientFrame frame) {
        LOGGER.info(
            "session runtime stream frame accepted sessionId={} turnId={} turnExecutionId={} streamSeq={} frameKind={} visibility={}",
            frame.sessionId(),
            frame.turnId(),
            frame.turnExecutionId(),
            frame.seq(),
            frame.kind(),
            frame.visibility()
        );
        String key = frame.turnExecutionId();
        switch (frame.kind()) {
            case TURN_STARTED -> turnStartedAtByExecution.put(key, frame.occurredAt());
            case REPLY_BLOCK_DELTA -> recordAssistantTextDelta(frame, key);
            case REPLY_BLOCK_COMPLETED -> recordAssistantTextCompleted(frame, key);
            case ACTION_TOOL_STARTED -> actionToolStartedCounter.increment();
            case ERROR, TURN_COMPLETED -> cleanupStreamObservation(key);
            case MODEL_STARTED,
                MODEL_COMPLETED,
                ACTION_TOOL_COMPLETED -> {
            }
        }
    }

    private void recordAssistantTextDelta(AgentTurnTransientFrame frame, String key) {
        assistantTextStartedAtByExecution.putIfAbsent(key, frame.occurredAt());
        if (ttftRecordedExecutions.add(key)) {
            Instant startedAt = turnStartedAtByExecution.get(key);
            if (startedAt != null) {
                ttftTimer.record(nonNegativeDuration(startedAt, frame.occurredAt()));
            }
        }
    }

    private void recordAssistantTextCompleted(AgentTurnTransientFrame frame, String key) {
        Instant startedAt = assistantTextStartedAtByExecution.remove(key);
        if (startedAt != null) {
            assistantTextStreamDurationTimer.record(nonNegativeDuration(startedAt, frame.occurredAt()));
        }
        turnStartedAtByExecution.remove(key);
        ttftRecordedExecutions.remove(key);
    }

    private void cleanupStreamObservation(String turnExecutionId) {
        turnStartedAtByExecution.remove(turnExecutionId);
        assistantTextStartedAtByExecution.remove(turnExecutionId);
        ttftRecordedExecutions.remove(turnExecutionId);
    }

    private void recordRejectedFrame(AgentTurnTransientFrame frame, ResponseStatusException error) {
        rejectedCustomerFrameCounter.increment();
        LOGGER.warn(
            "session runtime stream frame rejected sessionId={} turnId={} turnExecutionId={} streamSeq={} frameKind={} visibility={} reason={}",
            frame == null ? null : frame.sessionId(),
            frame == null ? null : frame.turnId(),
            frame == null ? null : frame.turnExecutionId(),
            frame == null ? null : frame.seq(),
            frame == null ? null : frame.kind(),
            frame == null ? null : frame.visibility(),
            error.getReason()
        );
    }

    private void recordDuplicateFrame(AgentTurnTransientFrame frame) {
        LOGGER.info(
            "session runtime stream frame deduped sessionId={} turnId={} turnExecutionId={} streamSeq={} frameKind={} visibility={} frameId={}",
            frame.sessionId(),
            frame.turnId(),
            frame.turnExecutionId(),
            frame.seq(),
            frame.kind(),
            frame.visibility(),
            frame.frameId()
        );
    }

    private static Duration nonNegativeDuration(Instant startedAt, Instant completedAt) {
        Duration duration = Duration.between(startedAt, completedAt);
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private void validateFrame(AgentTurnTransientFrame frame) {
        if (frame == null) {
            throw badFrame("frame is required");
        }
        if (!AgentTurnTransientFrame.PROTOCOL.equals(frame.protocol())) {
            throw badFrame("unsupported transient stream protocol");
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

    private static void validateCustomerFrame(AgentTurnTransientFrame frame) {
        if (frame.visibility() != StreamVisibility.CUSTOMER) {
            return;
        }
        if (!CUSTOMER_VISIBLE_FRAME_KINDS.contains(frame.kind())) {
            throw badFrame("customer stream frame kind is not allowed");
        }
        validateCustomerReplyDraft(frame);
    }

    private static void validateCustomerReplyDraft(AgentTurnTransientFrame frame) {
        if (frame.payload() instanceof ReplyBlockDeltaPayload payload) {
            if (isBlank(payload.replyMessageId()) || isBlank(payload.blockId())) {
                throw badFrame("customer reply draft replyMessageId and blockId are required");
            }
            if (payload.blockType() != SessionMessageBlockType.TEXT) {
                throw badFrame("customer reply draft only allows text blocks");
            }
            if (isEmpty(payload.delta()) || containsInternalTextToken(payload.delta())) {
                throw badFrame("customer reply draft delta is not allowed");
            }
            return;
        }
        if (frame.payload() instanceof ReplyBlockCompletedPayload) {
            validateCustomerReplyCompleted(frame);
            return;
        }
        throw badFrame("customer reply draft payload type is not allowed");
    }

    private static void validateCustomerReplyCompleted(AgentTurnTransientFrame frame) {
        if (!(frame.payload() instanceof ReplyBlockCompletedPayload payload)) {
            throw badFrame("customer reply completed payload type is required");
        }
        if (isBlank(payload.replyMessageId()) || isBlank(payload.blockId())) {
            throw badFrame("customer reply completed replyMessageId and blockId are required");
        }
        Object block = payload.block();
        if (!(block instanceof Map<?, ?> blockMap)) {
            if (block instanceof TextMessageBlock textBlock
                && textBlock.type() == SessionMessageBlockType.TEXT
                && !isBlank(textBlock.text())
                && !containsInternalTextToken(textBlock.text())) {
                return;
            }
            throw badFrame("customer reply completed text block is required");
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

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static boolean isVisibleTo(SessionRuntimeStreamEvent event, Set<StreamVisibility> visibility) {
        return event.visibility() == null || (
            event.visibility() != StreamVisibility.INTERNAL && visibility.contains(event.visibility())
        );
    }

    private static SessionMessageBlockType blockTypePayload(AgentTurnTransientFrame frame) {
        if (frame.payload() instanceof ReplyBlockDeltaPayload payload) {
            return payload.blockType();
        }
        String value = replyBlockPayload(frame, "type", SessionMessageBlockType.TEXT.name());
        try {
            return SessionMessageBlockType.valueOf(value);
        } catch (IllegalArgumentException error) {
            return SessionMessageBlockType.TEXT;
        }
    }

    private static String replyDeltaPayload(AgentTurnTransientFrame frame) {
        return frame.payload() instanceof ReplyBlockDeltaPayload payload ? payload.delta() : null;
    }

    private static String replyDraftTextPayload(AgentTurnTransientFrame frame) {
        return replyBlockPayload(frame, "text", null);
    }

    private static String replyMessageIdPayload(AgentTurnTransientFrame frame) {
        if (frame.payload() instanceof ReplyBlockDeltaPayload payload) {
            return payload.replyMessageId();
        }
        if (frame.payload() instanceof ReplyBlockCompletedPayload payload) {
            return payload.replyMessageId();
        }
        if (frame.payload() instanceof ErrorPayload payload && payload.replyMessageId() != null) {
            return payload.replyMessageId();
        }
        return "draft:" + frame.turnId();
    }

    private static String replyBlockIdPayload(AgentTurnTransientFrame frame) {
        if (frame.payload() instanceof ReplyBlockDeltaPayload payload) {
            return payload.blockId();
        }
        if (frame.payload() instanceof ReplyBlockCompletedPayload payload) {
            return payload.blockId();
        }
        return "block-1";
    }

    private static String replyBlockPayload(AgentTurnTransientFrame frame, String key, String defaultValue) {
        if (!(frame.payload() instanceof ReplyBlockCompletedPayload payload)) {
            return defaultValue;
        }
        Object block = payload.block();
        if (block instanceof TextMessageBlock textBlock) {
            return switch (key) {
                case "type" -> textBlock.type() == null ? defaultValue : textBlock.type().name();
                case "text" -> textBlock.text() == null ? defaultValue : textBlock.text();
                default -> defaultValue;
            };
        }
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

    private Exception closeSubscription(AutoCloseable subscription, Exception failure) {
        if (subscription == null) {
            return failure;
        }
        try {
            subscription.close();
            return failure;
        } catch (Exception error) {
            if (failure == null) {
                return error;
            }
            failure.addSuppressed(error);
            return failure;
        }
    }

    private void completeLocalEmitters() {
        for (Map.Entry<String, CopyOnWriteArraySet<Subscriber>> entry : emittersBySession.entrySet()) {
            for (Subscriber subscriber : entry.getValue()) {
                try {
                    subscriber.emitter().complete();
                } catch (RuntimeException error) {
                    LOGGER.warn("failed to complete session runtime stream emitter sessionId={}", entry.getKey(), error);
                }
            }
        }
        emittersBySession.clear();
        observedFingerprints.clear();
        turnStartedAtByExecution.clear();
        assistantTextStartedAtByExecution.clear();
        ttftRecordedExecutions.clear();
    }
}
