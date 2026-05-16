package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.channel.gateway.extension.GatewayNativeChannelProviderAdapter;
import com.lynxus.contracts.channel.ChannelContracts;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class FeishuGatewayNativeChannelProviderAdapter implements GatewayNativeChannelProviderAdapter {
    private static final Logger log = LoggerFactory.getLogger(FeishuGatewayNativeChannelProviderAdapter.class);
    public static final String PROVIDER_TYPE = "feishu";
    private static final String DEFAULT_RECEIVE_ID_TYPE = "chat_id";
    private static final int FEISHU_UUID_MAX_LENGTH = 50;
    private static final int FEISHU_STREAMING_MODE_CLOSED_CODE = 300309;
    private static final String INITIAL_STREAMING_REPLY_PLACEHOLDER = "思考中...";

    private final FeishuCredentialProvider credentialProvider;
    private final FeishuMessageSender messageSender;
    private final FeishuTypingReactionLifecycle typingReactionLifecycle;
    private final FeishuStreamingReplyCardStore streamingCardStore;
    private final FeishuStreamingReplyCardReadiness streamingCardReadiness;
    private final FeishuStreamingReplyCardProperties streamingCardProperties;
    private final ScheduledExecutorService emptyCardCleanupExecutor;
    private final ObjectMapper objectMapper;

    public FeishuGatewayNativeChannelProviderAdapter() {
        this(
            null,
            null,
            FeishuTypingReactionLifecycle.NOOP,
            null,
            new FeishuStreamingReplyCardReadiness(),
            new FeishuStreamingReplyCardProperties(),
            newEmptyCardCleanupExecutor(),
            null
        );
    }

    FeishuGatewayNativeChannelProviderAdapter(
        FeishuCredentialProvider credentialProvider,
        FeishuMessageSender messageSender,
        FeishuTypingReactionLifecycle typingReactionLifecycle,
        FeishuStreamingReplyCardStore streamingCardStore,
        ObjectMapper objectMapper
    ) {
        this(
            credentialProvider,
            messageSender,
            typingReactionLifecycle,
            streamingCardStore,
            new FeishuStreamingReplyCardReadiness(),
            new FeishuStreamingReplyCardProperties(),
            newEmptyCardCleanupExecutor(),
            objectMapper
        );
    }

    FeishuGatewayNativeChannelProviderAdapter(
        FeishuCredentialProvider credentialProvider,
        FeishuMessageSender messageSender,
        FeishuTypingReactionLifecycle typingReactionLifecycle,
        FeishuStreamingReplyCardStore streamingCardStore,
        FeishuStreamingReplyCardReadiness streamingCardReadiness,
        ObjectMapper objectMapper
    ) {
        this(
            credentialProvider,
            messageSender,
            typingReactionLifecycle,
            streamingCardStore,
            streamingCardReadiness,
            new FeishuStreamingReplyCardProperties(),
            newEmptyCardCleanupExecutor(),
            objectMapper
        );
    }

    @Autowired
    public FeishuGatewayNativeChannelProviderAdapter(
        FeishuCredentialProvider credentialProvider,
        FeishuMessageSender messageSender,
        FeishuTypingReactionLifecycle typingReactionLifecycle,
        FeishuStreamingReplyCardStore streamingCardStore,
        FeishuStreamingReplyCardReadiness streamingCardReadiness,
        FeishuStreamingReplyCardProperties streamingCardProperties,
        ObjectMapper objectMapper
    ) {
        this(
            credentialProvider,
            messageSender,
            typingReactionLifecycle,
            streamingCardStore,
            streamingCardReadiness,
            streamingCardProperties,
            newEmptyCardCleanupExecutor(),
            objectMapper
        );
    }

    FeishuGatewayNativeChannelProviderAdapter(
        FeishuCredentialProvider credentialProvider,
        FeishuMessageSender messageSender,
        FeishuTypingReactionLifecycle typingReactionLifecycle,
        FeishuStreamingReplyCardStore streamingCardStore,
        FeishuStreamingReplyCardReadiness streamingCardReadiness,
        FeishuStreamingReplyCardProperties streamingCardProperties,
        ScheduledExecutorService emptyCardCleanupExecutor,
        ObjectMapper objectMapper
    ) {
        this.credentialProvider = credentialProvider;
        this.messageSender = messageSender;
        this.typingReactionLifecycle = typingReactionLifecycle == null ? FeishuTypingReactionLifecycle.NOOP : typingReactionLifecycle;
        this.streamingCardStore = streamingCardStore;
        this.streamingCardReadiness = streamingCardReadiness == null ? new FeishuStreamingReplyCardReadiness() : streamingCardReadiness;
        this.streamingCardProperties = streamingCardProperties == null ? new FeishuStreamingReplyCardProperties() : streamingCardProperties;
        this.emptyCardCleanupExecutor = emptyCardCleanupExecutor == null ? newEmptyCardCleanupExecutor() : emptyCardCleanupExecutor;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    void shutdown() {
        emptyCardCleanupExecutor.shutdownNow();
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public Map<String, Object> descriptor() {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("providerType", PROVIDER_TYPE);
        descriptor.put("title", "Feishu");
        descriptor.put("description", "Gateway-native Feishu channel provider with SDK long-connection inbound and streaming card outbound support.");
        descriptor.put("accountConfigSchema", accountConfigSchema());
        descriptor.put("accountConfigUiSchema", accountConfigUiSchema());
        descriptor.put("credentialSchema", credentialSchema());
        descriptor.put("credentialUiSchema", credentialUiSchema());
        descriptor.put("configSchema", configSchema());
        descriptor.put("configUiSchema", configUiSchema());
        descriptor.put("defaultConfig", Map.of("receiveIdType", DEFAULT_RECEIVE_ID_TYPE));
        descriptor.put("outbound", Map.of(
            "mode", "FRAME_STREAM",
            "supportsTyping", true,
            "supportsDraftUpdate", true,
            "supportsFinalDelivery", true,
            "requiresIdempotentFinalDelivery", true
        ));
        descriptor.put("jobDefinitions", List.of());
        descriptor.put("endpoints", Map.of());
        return Map.copyOf(descriptor);
    }

    @Override
    public List<OutboundFrameDispatch> prepareOutboundFrames(ChannelGatewayProfile profile, List<ChannelOutboundFrame> frames) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }
        List<OutboundFrameDispatch> dispatches = new ArrayList<>();
        DraftUpdateRun draftRun = null;
        for (ChannelOutboundFrame frame : frames) {
            DraftUpdateKey key = DraftUpdateKey.from(frame);
            if (key != null && draftRun != null && draftRun.canAppend(key, frame)) {
                draftRun.append(frame);
                continue;
            }
            if (draftRun != null) {
                dispatches.add(draftRun.toDispatch());
                draftRun = null;
            }
            if (key == null) {
                dispatches.add(new OutboundFrameDispatch(frame, List.of(frame)));
            } else {
                draftRun = new DraftUpdateRun(key, frame);
            }
        }
        if (draftRun != null) {
            dispatches.add(draftRun.toDispatch());
        }
        return List.copyOf(dispatches);
    }

    @Override
    public void consumeOutboundFrame(ChannelGatewayProfile profile, ChannelOutboundFrame frame) {
        typingReactionLifecycle.deleteTypingReactionOnFirstOutboundFrame(profile, frame.sessionId(), frame.externalConversationId());
        switch (frame.kind()) {
            case TYPING_START -> consumeTypingStart(profile, frame);
            case TYPING_STOP -> consumeTypingStop(profile, frame);
            case DRAFT_UPDATE -> consumeDraftUpdate(profile, frame);
            case DRAFT_COMPLETE -> consumeDraftComplete(profile, frame);
            case DRAFT_DISCARD -> consumeDraftDiscard(profile, frame);
            case FINAL_DELIVERY -> consumeFinalDelivery(profile, frame);
        }
    }

    private void consumeTypingStart(ChannelGatewayProfile profile, ChannelOutboundFrame frame) {
        requireStreamingDependencies();
        FeishuStreamingReplyCardKey key = FeishuStreamingReplyCardKey.fromFrame(frame);
        if (streamingCardStore.find(key).isPresent()) {
            return;
        }
        FeishuAppCredential credential = credentialProvider.resolve(profile.accountId());
        createStreamingCardIfAbsent(profile, frame, key, credential, "", INITIAL_STREAMING_REPLY_PLACEHOLDER);
    }

    private void consumeTypingStop(ChannelGatewayProfile profile, ChannelOutboundFrame frame) {
        if (streamingCardStore == null) {
            return;
        }
        Optional<FeishuStreamingReplyCardState> existing = streamingCardStore.find(FeishuStreamingReplyCardKey.fromFrame(frame));
        if (existing.isPresent()) {
            FeishuStreamingReplyCardState state = existing.orElseThrow();
            if (alreadyProcessed(state, frame) && state.closed()) {
                return;
            }
        }
        closeExistingStreamingCard(profile, frame, Optional.empty())
            .ifPresent(state -> schedulePreparedEmptyCardDeletionIfBlank(profile, state));
    }

    private void schedulePreparedEmptyCardDeletionIfBlank(
        ChannelGatewayProfile profile,
        FeishuStreamingReplyCardState state
    ) {
        if (!state.content().isEmpty()) {
            return;
        }
        schedulePreparedEmptyCardDeletion(profile, state.key());
    }

    private void schedulePreparedEmptyCardDeletion(
        ChannelGatewayProfile profile,
        FeishuStreamingReplyCardKey key
    ) {
        requireStreamingDependencies();
        Duration delay = streamingCardProperties.getEmptyCardDeleteDelay();
        try {
            emptyCardCleanupExecutor.schedule(
                () -> deletePreparedEmptyCardIfStillEmpty(profile, key),
                delay.toMillis(),
                TimeUnit.MILLISECONDS
            );
        } catch (RuntimeException error) {
            log.warn(
                "failed to schedule empty Feishu streaming reply card cleanup: channelProfileId={}, sessionId={}, replyMessageId={}",
                key.channelProfileId(),
                key.sessionId(),
                key.replyMessageId(),
                error
            );
        }
    }

    private void deletePreparedEmptyCardIfStillEmpty(
        ChannelGatewayProfile profile,
        FeishuStreamingReplyCardKey key
    ) {
        Optional<FeishuStreamingReplyCardState> current = streamingCardStore.find(key);
        if (current.isEmpty()) {
            return;
        }
        FeishuStreamingReplyCardState state = current.orElseThrow();
        if (!state.content().isEmpty()) {
            return;
        }
        if (state.externalMessageId() == null || state.externalMessageId().isBlank()) {
            return;
        }
        try {
            FeishuAppCredential credential = credentialProvider.resolve(profile.accountId());
            messageSender.deleteMessage(new FeishuMessageSender.FeishuDeleteMessageCommand(
                credential,
                state.externalMessageId()
            ));
            streamingCardStore.delete(state.key());
        } catch (RuntimeException error) {
            log.warn(
                "failed to delete empty Feishu streaming reply card message: channelProfileId={}, sessionId={}, replyMessageId={}, externalMessageId={}",
                state.key().channelProfileId(),
                state.key().sessionId(),
                state.key().replyMessageId(),
                state.externalMessageId(),
                error
            );
        }
    }

    private void consumeDraftUpdate(ChannelGatewayProfile profile, ChannelOutboundFrame frame) {
        requireStreamingDependencies();
        String delta = textPayload(frame, "delta").orElse(null);
        if (delta == null || delta.isEmpty()) {
            return;
        }
        FeishuStreamingReplyCardKey key = FeishuStreamingReplyCardKey.fromFrame(frame);
        Optional<FeishuStreamingReplyCardState> existing = streamingCardStore.find(key);
        if (existing.isEmpty()) {
            existing = streamingCardReadiness.awaitReady(key, () -> streamingCardStore.find(key));
        }
        if (existing.isPresent() && alreadyProcessed(existing.orElseThrow(), frame)) {
            return;
        }
        FeishuAppCredential credential = credentialProvider.resolve(profile.accountId());
        if (existing.isEmpty()) {
            createStreamingCardIfAbsent(profile, frame, key, credential, delta, delta);
            return;
        }
        FeishuStreamingReplyCardState state = existing.orElseThrow();
        if (state.closed()) {
            return;
        }
        String content = state.content() + delta;
        int sequence = state.sequence() + 1;
        try {
            messageSender.updateCardText(new FeishuMessageSender.FeishuUpdateCardTextCommand(
                credential,
                state.cardId(),
                state.elementId(),
                content,
                sequence,
                feishuUuid(frame, "text")
            ));
        } catch (RuntimeException error) {
            if (isFeishuStreamingModeClosed(error)) {
                streamingCardStore.save(state.closed(sequence, advanceSourceSeq(state, frame), frame.occurredAt()));
                return;
            }
            throw error;
        }
        streamingCardStore.save(state.withContent(content, sequence, advanceSourceSeq(state, frame), frame.occurredAt()));
    }

    private FeishuStreamingReplyCardState createStreamingCardIfAbsent(
        ChannelGatewayProfile profile,
        ChannelOutboundFrame frame,
        FeishuStreamingReplyCardKey key,
        FeishuAppCredential credential,
        String initialContent,
        String displayContent
    ) {
        try (FeishuStreamingReplyCardReadiness.Reservation _ = streamingCardReadiness.begin(key)) {
            Optional<FeishuStreamingReplyCardState> existing = streamingCardStore.find(key);
            if (existing.isPresent()) {
                return existing.orElseThrow();
            }
            return createStreamingCard(profile, frame, key, credential, initialContent, displayContent);
        }
    }

    private FeishuStreamingReplyCardState createStreamingCard(
        ChannelGatewayProfile profile,
        ChannelOutboundFrame frame,
        FeishuStreamingReplyCardKey key,
        FeishuAppCredential credential,
        String initialContent,
        String displayContent
    ) {
        FeishuMessageSender.FeishuCreateCardResult card = messageSender.createCard(
            new FeishuMessageSender.FeishuCreateCardCommand(
                credential,
                FeishuCardJsonFactory.streamingReplyCard(objectMapper, displayContent)
            )
        );
        FeishuMessageSender.FeishuSendInteractiveCardResult message = messageSender.sendInteractiveCard(
            new FeishuMessageSender.FeishuSendInteractiveCardCommand(
                credential,
                receiveIdType(profile.config()),
                frame.externalConversationId(),
                card.cardId(),
                feishuUuid(frame)
            )
        );
        FeishuStreamingReplyCardState state = new FeishuStreamingReplyCardState(
            key,
            frame.externalConversationId(),
            card.cardId(),
            message.externalMessageId(),
            FeishuCardJsonFactory.STREAMING_MARKDOWN_ELEMENT_ID,
            initialContent,
            0,
            frame.sourceSeq(),
            false,
            frame.occurredAt()
        );
        streamingCardStore.save(state);
        return state;
    }

    private void consumeDraftComplete(ChannelGatewayProfile profile, ChannelOutboundFrame frame) {
        closeExistingStreamingCard(profile, frame, completedBlockText(profile, frame))
            .ifPresent(state -> schedulePreparedEmptyCardDeletionIfBlank(profile, state));
    }

    private void consumeDraftDiscard(ChannelGatewayProfile profile, ChannelOutboundFrame frame) {
        closeExistingStreamingCard(profile, frame, Optional.empty())
            .ifPresent(state -> schedulePreparedEmptyCardDeletionIfBlank(profile, state));
    }

    private void consumeFinalDelivery(ChannelGatewayProfile profile, ChannelOutboundFrame frame) {
        Optional<String> text = finalText(profile, frame);
        FeishuStreamingReplyCardKey key = FeishuStreamingReplyCardKey.fromFrame(frame);
        Optional<FeishuStreamingReplyCardState> existing = streamingCardStore == null ? Optional.empty() : streamingCardStore.find(key);
        if (existing.isPresent()) {
            closeExistingStreamingCard(profile, frame, text);
            return;
        }
        if (text.isEmpty()) {
            return;
        }
        requireStreamingDependencies();
        FeishuAppCredential credential = credentialProvider.resolve(profile.accountId());
        FeishuMessageSender.FeishuCreateCardResult card = messageSender.createCard(
            new FeishuMessageSender.FeishuCreateCardCommand(
                credential,
                FeishuCardJsonFactory.finalReplyCard(objectMapper, text.orElseThrow())
            )
        );
        messageSender.sendInteractiveCard(new FeishuMessageSender.FeishuSendInteractiveCardCommand(
            credential,
            receiveIdType(profile.config()),
            frame.externalConversationId(),
            card.cardId(),
            feishuUuid(frame)
        ));
    }

    private Optional<FeishuStreamingReplyCardState> closeExistingStreamingCard(
        ChannelGatewayProfile profile,
        ChannelOutboundFrame frame,
        Optional<String> finalContent
    ) {
        if (streamingCardStore == null) {
            return Optional.empty();
        }
        Optional<FeishuStreamingReplyCardState> existing = streamingCardStore.find(FeishuStreamingReplyCardKey.fromFrame(frame));
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        FeishuStreamingReplyCardState state = existing.orElseThrow();
        if (alreadyProcessed(state, frame)
            && state.closed()
            && (finalContent.isEmpty() || finalContent.orElseThrow().equals(state.content()))) {
            return Optional.of(state);
        }
        requireStreamingDependencies();
        FeishuAppCredential credential = credentialProvider.resolve(profile.accountId());
        FeishuStreamingReplyCardState current = state;
        if (finalContent.isPresent() && !finalContent.orElseThrow().equals(current.content())) {
            int contentSequence = current.sequence() + 1;
            try {
                messageSender.updateCardText(new FeishuMessageSender.FeishuUpdateCardTextCommand(
                    credential,
                    current.cardId(),
                    current.elementId(),
                    finalContent.orElseThrow(),
                    contentSequence,
                    feishuUuid(frame, "text")
                ));
            } catch (RuntimeException error) {
                if (isFeishuStreamingModeClosed(error)) {
                    current = current.closed(contentSequence, advanceSourceSeq(current, frame), frame.occurredAt());
                    streamingCardStore.save(current);
                    return Optional.of(current);
                }
                throw error;
            }
            current = current.withContent(finalContent.orElseThrow(), contentSequence, advanceSourceSeq(current, frame), frame.occurredAt());
        }
        if (!current.closed()) {
            int closeSequence = current.sequence() + 1;
            try {
                messageSender.updateCardSettings(new FeishuMessageSender.FeishuUpdateCardSettingsCommand(
                    credential,
                    current.cardId(),
                    FeishuCardJsonFactory.streamingOffSettings(objectMapper),
                    closeSequence,
                    feishuUuid(frame, "settings")
                ));
            } catch (RuntimeException error) {
                if (isFeishuStreamingModeClosed(error)) {
                    current = current.closed(closeSequence, advanceSourceSeq(current, frame), frame.occurredAt());
                    streamingCardStore.save(current);
                    return Optional.of(current);
                }
                throw error;
            }
            current = current.closed(closeSequence, advanceSourceSeq(current, frame), frame.occurredAt());
        }
        streamingCardStore.save(current);
        return Optional.of(current);
    }

    private static Map<String, Object> accountConfigSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "appId", Map.of("type", "string", "minLength", 1)
            ),
            "required", List.of("appId"),
            "additionalProperties", false
        );
    }

    private static List<Map<String, Object>> accountConfigUiSchema() {
        return List.of(
            Map.of(
                "key", "/appId",
                "label", "App ID",
                "component", "text",
                "required", true,
                "order", 10
            )
        );
    }

    private static Map<String, Object> credentialSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "appSecret", Map.of("type", "string", "minLength", 1),
                "verificationToken", Map.of("type", "string"),
                "encryptKey", Map.of("type", "string")
            ),
            "required", List.of("appSecret"),
            "additionalProperties", false
        );
    }

    private static List<Map<String, Object>> credentialUiSchema() {
        return List.of(
            Map.of(
                "key", "/appSecret",
                "label", "App Secret",
                "component", "password",
                "required", true,
                "secret", true,
                "order", 10
            ),
            Map.of(
                "key", "/verificationToken",
                "label", "Verification Token",
                "component", "password",
                "required", false,
                "secret", true,
                "order", 20
            ),
            Map.of(
                "key", "/encryptKey",
                "label", "Encrypt Key",
                "component", "password",
                "required", false,
                "secret", true,
                "order", 30
            )
        );
    }

    private static Map<String, Object> configSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "receiveIdType", Map.of(
                    "type", "string",
                    "enum", List.of("chat_id", "open_id", "user_id", "union_id", "email"),
                    "default", DEFAULT_RECEIVE_ID_TYPE
                )
            ),
            "additionalProperties", false
        );
    }

    private static List<Map<String, Object>> configUiSchema() {
        return List.of(Map.of(
            "key", "/receiveIdType",
            "label", "Receive ID类型",
            "component", "select",
            "description", "飞书如何解读出站请求中的 receive_id。保留 Chat ID 以用于正常对话回复。"
                + "仅当外部会话 ID 存储了该标识符类型时，再选择用户标识符。",
            "options", List.of(
                Map.of("label", "Chat ID", "value", "chat_id"),
                Map.of("label", "Open ID", "value", "open_id"),
                Map.of("label", "User ID", "value", "user_id"),
                Map.of("label", "Union ID", "value", "union_id"),
                Map.of("label", "Email", "value", "email")
            ),
            "order", 10
        ));
    }

    private static String receiveIdType(Map<String, Object> profileConfig) {
        Object value = profileConfig == null ? null : profileConfig.get("receiveIdType");
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return DEFAULT_RECEIVE_ID_TYPE;
    }

    private void requireStreamingDependencies() {
        if (credentialProvider == null
            || messageSender == null
            || streamingCardStore == null
            || streamingCardReadiness == null
            || streamingCardProperties == null
            || emptyCardCleanupExecutor == null
            || objectMapper == null) {
            throw new IllegalStateException("Feishu gateway-native outbound dependencies are not configured");
        }
    }

    private static ScheduledExecutorService newEmptyCardCleanupExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "feishu-empty-card-cleanup");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    private static String feishuUuid(ChannelOutboundFrame frame) {
        String seed = frame.idempotencyKey() == null || frame.idempotencyKey().isBlank()
            ? frame.frameId()
            : frame.idempotencyKey();
        if (seed.length() <= FEISHU_UUID_MAX_LENGTH) {
            return seed;
        }
        return ChannelContracts.channelOutboundFrameIdempotencyKey(seed);
    }

    private static String feishuUuid(ChannelOutboundFrame frame, String operation) {
        String seed = feishuUuid(frame) + ":" + operation;
        if (seed.length() <= FEISHU_UUID_MAX_LENGTH) {
            return seed;
        }
        return ChannelContracts.channelOutboundFrameIdempotencyKey(seed);
    }

    private static boolean alreadyProcessed(FeishuStreamingReplyCardState state, ChannelOutboundFrame frame) {
        return frame.sourceSeq() != null && state.lastSourceSeq() != null && frame.sourceSeq() <= state.lastSourceSeq();
    }

    private static Long advanceSourceSeq(FeishuStreamingReplyCardState state, ChannelOutboundFrame frame) {
        if (frame.sourceSeq() == null) {
            return state.lastSourceSeq();
        }
        if (state.lastSourceSeq() == null || frame.sourceSeq() > state.lastSourceSeq()) {
            return frame.sourceSeq();
        }
        return state.lastSourceSeq();
    }

    private static Optional<String> textPayload(ChannelOutboundFrame frame, String field) {
        Object value = frame.payload().get(field);
        return value instanceof String text && !text.isEmpty() ? Optional.of(text) : Optional.empty();
    }

    private static boolean isFeishuStreamingModeClosed(RuntimeException error) {
        return error instanceof FeishuApiException feishuError
            && feishuError.code() == FEISHU_STREAMING_MODE_CLOSED_CODE;
    }

    private record DraftUpdateKey(String channelProfileId, String sessionId, String replyMessageId, String blockId) {
        private static DraftUpdateKey from(ChannelOutboundFrame frame) {
            if (frame.kind() != ChannelOutboundFrameKind.DRAFT_UPDATE) {
                return null;
            }
            Optional<String> delta = textPayload(frame, "delta");
            if (delta.isEmpty()) {
                return null;
            }
            return new DraftUpdateKey(
                frame.channelProfileId(),
                frame.sessionId(),
                textPayload(frame, "replyMessageId").orElse(null),
                textPayload(frame, "blockId").orElse(null)
            );
        }

        private DraftUpdateKey {
            if (replyMessageId == null || blockId == null) {
                throw new IllegalArgumentException("replyMessageId and blockId are required for Feishu draft update coalescing");
            }
        }
    }

    private static final class DraftUpdateRun {
        private final DraftUpdateKey key;
        private final List<ChannelOutboundFrame> frames = new ArrayList<>();
        private final StringBuilder delta = new StringBuilder();
        private Map<String, Object> payloadWithoutDelta;

        private DraftUpdateRun(DraftUpdateKey key, ChannelOutboundFrame firstFrame) {
            this.key = key;
            append(firstFrame);
        }

        private boolean canAppend(DraftUpdateKey candidateKey, ChannelOutboundFrame frame) {
            return key.equals(candidateKey) && payloadWithoutDelta.equals(payloadWithoutDelta(frame));
        }

        private void append(ChannelOutboundFrame frame) {
            if (frames.isEmpty()) {
                payloadWithoutDelta = payloadWithoutDelta(frame);
            }
            frames.add(frame);
            delta.append(textPayload(frame, "delta").orElseThrow());
        }

        private OutboundFrameDispatch toDispatch() {
            if (frames.size() == 1) {
                ChannelOutboundFrame frame = frames.getFirst();
                return new OutboundFrameDispatch(frame, List.of(frame));
            }
            ChannelOutboundFrame first = frames.getFirst();
            ChannelOutboundFrame last = frames.getLast();
            Map<String, Object> payload = new LinkedHashMap<>(last.payload());
            payload.put("delta", delta.toString());
            String idempotencyKey = ChannelContracts.channelOutboundFrameIdempotencyKey(
                first.idempotencyKey() + ":" + last.idempotencyKey() + ":feishu-draft-update-coalesced"
            );
            ChannelOutboundFrame coalesced = new ChannelOutboundFrame(
                first.protocol(),
                first.channelProfileId() + ":" + first.sessionId() + ":" + last.sourceSeq() + ":DRAFT_UPDATE:COALESCED",
                first.channelProfileId(),
                first.providerType(),
                first.assistantId(),
                first.externalConversationId(),
                first.sessionId(),
                last.turnId(),
                last.turnExecutionId(),
                last.sourceSeq(),
                null,
                ChannelOutboundFrameKind.DRAFT_UPDATE,
                last.occurredAt(),
                idempotencyKey,
                payload,
                last.traceContext()
            );
            return new OutboundFrameDispatch(coalesced, frames);
        }

        private static Map<String, Object> payloadWithoutDelta(ChannelOutboundFrame frame) {
            Map<String, Object> payload = new LinkedHashMap<>(frame.payload());
            payload.remove("delta");
            return Map.copyOf(payload);
        }
    }

    private static Optional<String> completedBlockText(ChannelGatewayProfile profile, ChannelOutboundFrame frame) {
        Object rawBlock = frame.payload().get("block");
        if (!(rawBlock instanceof Map<?, ?> block)) {
            return Optional.empty();
        }
        return blockText(profile, frame, block, 0);
    }

    private static Optional<String> finalText(ChannelGatewayProfile profile, ChannelOutboundFrame frame) {
        Object rawBlocks = frame.payload().get("messageBlocks");
        if (!(rawBlocks instanceof List<?> blocks) || blocks.isEmpty()) {
            log.info(
                "skipping Feishu native final delivery with no renderable message blocks: channelProfileId={}, frameId={}, sessionId={}, sessionMessageId={}",
                frame.channelProfileId(),
                frame.frameId(),
                frame.sessionId(),
                frame.payload().get("sessionMessageId")
            );
            return Optional.empty();
        }
        List<String> parts = new ArrayList<>();
        int index = 0;
        for (Object rawBlock : blocks) {
            int blockIndex = index++;
            if (!(rawBlock instanceof Map<?, ?> block)) {
                log.info(
                    "skipping unsupported Feishu native final block: channelProfileId={}, frameId={}, sessionId={}, sessionMessageId={}, blockIndex={}, blockType={}",
                    frame.channelProfileId(),
                    frame.frameId(),
                    frame.sessionId(),
                    frame.payload().get("sessionMessageId"),
                    blockIndex,
                    "UNKNOWN"
                );
                continue;
            }
            Optional<String> text = blockText(profile, frame, block, blockIndex);
            if (text.isPresent()) {
                parts.add(text.orElseThrow());
                continue;
            }
        }
        if (parts.isEmpty()) {
            log.info(
                "skipping Feishu native final delivery with no text content: channelProfileId={}, providerType={}, frameId={}, sessionId={}, sessionMessageId={}",
                profile.id(),
                profile.providerType(),
                frame.frameId(),
                frame.sessionId(),
                frame.payload().get("sessionMessageId")
            );
            return Optional.empty();
        }
        return Optional.of(String.join("\n", parts));
    }

    private static Optional<String> blockText(
        ChannelGatewayProfile profile,
        ChannelOutboundFrame frame,
        Map<?, ?> block,
        int blockIndex
    ) {
        Object rawType = block.get("type");
        String type = rawType == null ? "TEXT" : String.valueOf(rawType);
        if ("TEXT".equals(type)) {
            Object text = block.get("text");
            return text instanceof String value && !value.isBlank() ? Optional.of(value) : Optional.empty();
        }
        if ("RICH_TEXT".equals(type)) {
            Object content = block.get("content");
            return content instanceof String value && !value.isBlank() ? Optional.of(value) : Optional.empty();
        }
        log.info(
            "skipping unsupported Feishu native final block: channelProfileId={}, providerType={}, frameId={}, sessionId={}, sessionMessageId={}, blockIndex={}, blockType={}",
            profile.id(),
            profile.providerType(),
            frame.frameId(),
            frame.sessionId(),
            frame.payload().get("sessionMessageId"),
            blockIndex,
            type
        );
        return Optional.empty();
    }
}
