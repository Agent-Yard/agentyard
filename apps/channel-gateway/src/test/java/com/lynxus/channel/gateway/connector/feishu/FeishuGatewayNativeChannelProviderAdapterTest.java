package com.lynxus.channel.gateway.connector.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.channel.ChannelContracts;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FeishuGatewayNativeChannelProviderAdapterTest {
    @Test
    void finalDeliverySendsCardWithBoundedFeishuIdempotencyUuid() {
        CapturingCredentialProvider credentialProvider = new CapturingCredentialProvider();
        CapturingMessageSender messageSender = new CapturingMessageSender();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            credentialProvider,
            messageSender,
            FeishuTypingReactionLifecycle.NOOP,
            new InMemoryStreamingCardStore(),
            new ObjectMapper()
        );
        ChannelOutboundFrame frame = finalFrame();

        adapter.consumeOutboundFrame(profile(), frame);

        assertEquals("account-1", credentialProvider.accountId);
        assertEquals(1, messageSender.createCardCommands.size());
        assertTrue(messageSender.createCardCommands.getFirst().cardJson().contains("\"schema\":\"2.0\""));
        assertTrue(messageSender.createCardCommands.getFirst().cardJson().contains("\"content\":\"hello\\nworld\""));
        assertEquals("chat_id", messageSender.sendCardCommands.getFirst().receiveIdType());
        assertEquals("chat-1", messageSender.sendCardCommands.getFirst().receiveId());
        assertEquals("card-1", messageSender.sendCardCommands.getFirst().cardId());
        assertEquals("cof-b756d04622f455de1bce7530f2c6eadf", messageSender.sendCardCommands.getFirst().uuid());
        assertTrue(messageSender.sendCardCommands.getFirst().uuid().length() <= 50);
    }

    @Test
    void finalDeliverySkipsUnsupportedBlocksWithoutFailingCheckpointPath() {
        CapturingCredentialProvider credentialProvider = new CapturingCredentialProvider();
        CapturingMessageSender messageSender = new CapturingMessageSender();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            credentialProvider,
            messageSender,
            FeishuTypingReactionLifecycle.NOOP,
            new InMemoryStreamingCardStore(),
            new ObjectMapper()
        );
        ChannelOutboundFrame frame = finalFrameWithBlocks(List.of(
            Map.of("type", "IMAGE", "url", "https://example.invalid/image.png"),
            Map.of("type", "FILE", "fileId", "file-1")
        ));

        adapter.consumeOutboundFrame(profile(), frame);

        assertNull(credentialProvider.accountId);
        assertEquals(0, messageSender.createCardCommands.size());
        assertEquals(0, messageSender.sendCardCommands.size());
    }

    @Test
    void typingStartCreatesStreamingCardBeforeFirstDraftUpdate() {
        CapturingTypingReactionLifecycle typingLifecycle = new CapturingTypingReactionLifecycle();
        CapturingMessageSender messageSender = new CapturingMessageSender();
        InMemoryStreamingCardStore store = new InMemoryStreamingCardStore();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            messageSender,
            typingLifecycle,
            store,
            new ObjectMapper()
        );

        adapter.consumeOutboundFrame(profile(), typingFrameWithMessageId("session-message-reply-1"));
        adapter.consumeOutboundFrame(profile(), draftUpdateFrame(2L, "hel"));

        assertEquals(List.of("session-1", "session-1"), typingLifecycle.deletedSessions);
        assertEquals(1, messageSender.createCardCommands.size());
        String initialCardJson = messageSender.createCardCommands.getFirst().cardJson();
        assertTrue(initialCardJson.contains("\"streaming_mode\":true"));
        assertTrue(initialCardJson.contains("\"print_strategy\":\"fast\""));
        assertTrue(initialCardJson.contains("\"content\":\"思考中...\""));
        assertFalse(initialCardJson.contains("hel"));
        assertFalse(initialCardJson.contains("\"header\""));
        assertEquals(1, messageSender.sendCardCommands.size());
        assertEquals("card-1", messageSender.sendCardCommands.getFirst().cardId());
        assertEquals(1, messageSender.updateTextCommands.size());
        assertEquals("hel", messageSender.updateTextCommands.getFirst().content());
        assertEquals(1, messageSender.updateTextCommands.getFirst().sequence());
        FeishuStreamingReplyCardState state = store.find(FeishuStreamingReplyCardKey.fromFrame(draftUpdateFrame(2L, "ignored"))).orElseThrow();
        assertEquals("hel", state.content());
        assertEquals(1, state.sequence());
        assertEquals(2L, state.lastSourceSeq());
    }

    @Test
    void draftUpdatePreservesWhitespaceOnlyDeltaOnPreparedStreamingCard() {
        CapturingMessageSender messageSender = new CapturingMessageSender();
        InMemoryStreamingCardStore store = new InMemoryStreamingCardStore();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            messageSender,
            FeishuTypingReactionLifecycle.NOOP,
            store,
            new ObjectMapper()
        );

        adapter.consumeOutboundFrame(profile(), typingFrameWithMessageId("session-message-reply-1"));
        adapter.consumeOutboundFrame(profile(), draftUpdateFrame(2L, "\n "));

        assertEquals(1, messageSender.updateTextCommands.size());
        assertEquals("\n ", messageSender.updateTextCommands.getFirst().content());
        FeishuStreamingReplyCardState state = store.find(FeishuStreamingReplyCardKey.fromFrame(draftUpdateFrame(2L, "ignored"))).orElseThrow();
        assertEquals("\n ", state.content());
        assertEquals(1, state.sequence());
        assertEquals(2L, state.lastSourceSeq());
    }

    @Test
    void draftUpdateTreatsFeishuStreamingModeClosedAsStaleFrame() {
        StreamingModeClosedMessageSender messageSender = new StreamingModeClosedMessageSender();
        InMemoryStreamingCardStore store = new InMemoryStreamingCardStore();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            messageSender,
            FeishuTypingReactionLifecycle.NOOP,
            store,
            new ObjectMapper()
        );

        adapter.consumeOutboundFrame(profile(), typingFrameWithMessageId("session-message-reply-1"));
        adapter.consumeOutboundFrame(profile(), draftUpdateFrame(2L, "late"));
        adapter.consumeOutboundFrame(profile(), draftUpdateFrame(3L, " ignored"));

        assertEquals(1, messageSender.updateTextCommands.size());
        FeishuStreamingReplyCardState state = store.find(FeishuStreamingReplyCardKey.fromFrame(draftUpdateFrame(3L, "ignored"))).orElseThrow();
        assertTrue(state.closed());
        assertEquals("", state.content());
        assertEquals(2L, state.lastSourceSeq());
    }

    @Test
    void firstDraftWaitsUntilPreparedCardMessageHasBeenSent() throws Exception {
        BlockingMessageSender messageSender = new BlockingMessageSender();
        InMemoryStreamingCardStore store = new InMemoryStreamingCardStore();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            messageSender,
            FeishuTypingReactionLifecycle.NOOP,
            store,
            new FeishuStreamingReplyCardReadiness(Duration.ofSeconds(5)),
            new ObjectMapper()
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> typingStart = executor.submit(() -> adapter.consumeOutboundFrame(
                profile(),
                typingFrameWithMessageId("session-message-reply-1")
            ));
            assertTrue(messageSender.sendStarted.await(5, TimeUnit.SECONDS));

            Future<?> firstDraft = executor.submit(() -> adapter.consumeOutboundFrame(profile(), draftUpdateFrame(2L, "hel")));

            assertThrows(TimeoutException.class, () -> firstDraft.get(100, TimeUnit.MILLISECONDS));
            messageSender.releaseSend();
            typingStart.get(5, TimeUnit.SECONDS);
            firstDraft.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, messageSender.createCardCommands.size());
        assertEquals(1, messageSender.sendCardCommands.size());
        assertEquals(1, messageSender.updateTextCommands.size());
        assertEquals("hel", messageSender.updateTextCommands.getFirst().content());
        assertEquals(1, messageSender.updateTextCommands.getFirst().sequence());
    }

    @Test
    void typingStopDeletesPreparedEmptyCardWhenNoDraftArrived() throws Exception {
        CapturingMessageSender messageSender = new CapturingMessageSender();
        InMemoryStreamingCardStore store = new InMemoryStreamingCardStore();
        FeishuStreamingReplyCardProperties properties = streamingProperties(Duration.ZERO);
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            messageSender,
            FeishuTypingReactionLifecycle.NOOP,
            store,
            new FeishuStreamingReplyCardReadiness(),
            properties,
            new ObjectMapper()
        );

        try {
            adapter.consumeOutboundFrame(profile(), typingFrameWithMessageId("session-message-reply-1"));
            adapter.consumeOutboundFrame(profile(), typingStopFrameWithMessageId(5L, "session-message-reply-1"));

            waitUntil(() -> messageSender.deleteMessageCommands.size() == 1);

            assertEquals(1, messageSender.createCardCommands.size());
            assertEquals(1, messageSender.sendCardCommands.size());
            assertEquals("external-card-message-1", messageSender.deleteMessageCommands.getFirst().externalMessageId());
            assertEquals(1, messageSender.updateSettingsCommands.size());
            assertTrue(messageSender.updateSettingsCommands.getFirst().settings().contains("\"streaming_mode\":false"));
            assertTrue(store.find(FeishuStreamingReplyCardKey.fromFrame(typingFrameWithMessageId("session-message-reply-1"))).isEmpty());
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void typingStopDoesNotDeletePreparedCardAfterWhitespaceDraftDelta() throws Exception {
        CapturingMessageSender messageSender = new CapturingMessageSender();
        InMemoryStreamingCardStore store = new InMemoryStreamingCardStore();
        FeishuStreamingReplyCardProperties properties = streamingProperties(Duration.ZERO);
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            messageSender,
            FeishuTypingReactionLifecycle.NOOP,
            store,
            new FeishuStreamingReplyCardReadiness(),
            properties,
            new ObjectMapper()
        );

        try {
            adapter.consumeOutboundFrame(profile(), typingFrameWithMessageId("session-message-reply-1"));
            adapter.consumeOutboundFrame(profile(), draftUpdateFrame(2L, "\n "));
            adapter.consumeOutboundFrame(profile(), typingStopFrameWithMessageId(5L, "session-message-reply-1"));

            assertFalse(messageSender.deleteMessageCalled.await(100, TimeUnit.MILLISECONDS));
            assertEquals(0, messageSender.deleteMessageCommands.size());
            FeishuStreamingReplyCardState state = store.find(FeishuStreamingReplyCardKey.fromFrame(typingFrameWithMessageId("session-message-reply-1"))).orElseThrow();
            assertTrue(state.closed());
            assertEquals("\n ", state.content());
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void finalDeliveryAfterTypingStopReusesPreparedCardBeforeEmptyCleanup() {
        CapturingMessageSender messageSender = new CapturingMessageSender();
        InMemoryStreamingCardStore store = new InMemoryStreamingCardStore();
        FeishuStreamingReplyCardProperties properties = streamingProperties(Duration.ofMinutes(1));
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            messageSender,
            FeishuTypingReactionLifecycle.NOOP,
            store,
            new FeishuStreamingReplyCardReadiness(),
            properties,
            new ObjectMapper()
        );

        try {
            adapter.consumeOutboundFrame(profile(), typingFrameWithMessageId("session-message-reply-1"));
            adapter.consumeOutboundFrame(profile(), typingStopFrameWithMessageId(5L, "session-message-reply-1"));
            adapter.consumeOutboundFrame(profile(), finalFrameWithMessageId("session-message-reply-1", List.of(
                Map.of("type", "TEXT", "text", "当前处理遇到问题，请稍后再试")
            )));

            assertEquals(1, messageSender.createCardCommands.size());
            assertEquals(1, messageSender.sendCardCommands.size());
            assertEquals(0, messageSender.deleteMessageCommands.size());
            assertEquals(1, messageSender.updateTextCommands.size());
            assertEquals("当前处理遇到问题，请稍后再试", messageSender.updateTextCommands.getFirst().content());
            assertEquals(1, messageSender.updateSettingsCommands.size());
            FeishuStreamingReplyCardState state = store.find(FeishuStreamingReplyCardKey.fromFrame(typingFrameWithMessageId("session-message-reply-1"))).orElseThrow();
            assertTrue(state.closed());
            assertEquals("当前处理遇到问题，请稍后再试", state.content());
            assertEquals(5L, state.lastSourceSeq());
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void draftUpdateCreatesOneStreamingCardPerReplyAndUpdatesWithAccumulatedContent() {
        CapturingCredentialProvider credentialProvider = new CapturingCredentialProvider();
        CapturingMessageSender messageSender = new CapturingMessageSender();
        InMemoryStreamingCardStore store = new InMemoryStreamingCardStore();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            credentialProvider,
            messageSender,
            FeishuTypingReactionLifecycle.NOOP,
            store,
            new ObjectMapper()
        );

        adapter.consumeOutboundFrame(profile(), draftUpdateFrame(2L, "hel"));
        adapter.consumeOutboundFrame(profile(), draftUpdateFrame(3L, "lo"));

        assertEquals("account-1", credentialProvider.accountId);
        assertEquals(1, messageSender.createCardCommands.size());
        String initialCardJson = messageSender.createCardCommands.getFirst().cardJson();
        assertTrue(initialCardJson.contains("\"streaming_mode\":true"));
        assertTrue(initialCardJson.contains("\"print_strategy\":\"fast\""));
        assertTrue(initialCardJson.contains("\"element_id\":\"markdown_1\""));
        assertTrue(initialCardJson.contains("\"content\":\"hel\""));
        assertFalse(initialCardJson.contains("\"header\""));
        assertEquals(1, messageSender.sendCardCommands.size());
        assertEquals("card-1", messageSender.sendCardCommands.getFirst().cardId());
        assertEquals(1, messageSender.updateTextCommands.size());
        assertEquals("hello", messageSender.updateTextCommands.getFirst().content());
        assertEquals(1, messageSender.updateTextCommands.getFirst().sequence());
        FeishuStreamingReplyCardState state = store.find(FeishuStreamingReplyCardKey.fromFrame(draftUpdateFrame(3L, "ignored"))).orElseThrow();
        assertEquals("hello", state.content());
        assertEquals(1, state.sequence());
        assertEquals(3L, state.lastSourceSeq());
    }

    @Test
    void draftCompleteClosesStreamingModeWithoutCreatingAnotherCard() {
        CapturingMessageSender messageSender = new CapturingMessageSender();
        InMemoryStreamingCardStore store = new InMemoryStreamingCardStore();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            messageSender,
            FeishuTypingReactionLifecycle.NOOP,
            store,
            new ObjectMapper()
        );

        adapter.consumeOutboundFrame(profile(), draftUpdateFrame(2L, "hello"));
        adapter.consumeOutboundFrame(profile(), draftCompleteFrame(4L, Map.of("type", "TEXT", "text", "hello")));

        assertEquals(1, messageSender.createCardCommands.size());
        assertEquals(1, messageSender.updateSettingsCommands.size());
        assertTrue(messageSender.updateSettingsCommands.getFirst().settings().contains("\"streaming_mode\":false"));
        assertEquals(1, messageSender.updateSettingsCommands.getFirst().sequence());
        FeishuStreamingReplyCardState state = store.find(FeishuStreamingReplyCardKey.fromFrame(draftUpdateFrame(4L, "ignored"))).orElseThrow();
        assertTrue(state.closed());
    }

    @Test
    void finalDeliveryAfterDraftDiscardReusesPreparedCardForFailureReply() {
        CapturingMessageSender messageSender = new CapturingMessageSender();
        InMemoryStreamingCardStore store = new InMemoryStreamingCardStore();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            messageSender,
            FeishuTypingReactionLifecycle.NOOP,
            store,
            new ObjectMapper()
        );

        adapter.consumeOutboundFrame(profile(), typingFrameWithMessageId("session-message-reply-1"));
        adapter.consumeOutboundFrame(profile(), draftDiscardFrame(2L, "session-message-reply-1", "WORKER_STREAM_STALL"));
        adapter.consumeOutboundFrame(profile(), finalFrameWithMessageId("session-message-reply-1", List.of(
            Map.of("type", "TEXT", "text", "当前处理遇到问题，请稍后再试")
        )));

        assertEquals(1, messageSender.createCardCommands.size());
        assertEquals(1, messageSender.sendCardCommands.size());
        assertEquals(1, messageSender.updateTextCommands.size());
        assertEquals("当前处理遇到问题，请稍后再试", messageSender.updateTextCommands.getFirst().content());
        assertEquals(1, messageSender.updateSettingsCommands.size());
        FeishuStreamingReplyCardState state = store.find(FeishuStreamingReplyCardKey.fromFrame(typingFrameWithMessageId("session-message-reply-1"))).orElseThrow();
        assertTrue(state.closed());
        assertEquals("当前处理遇到问题，请稍后再试", state.content());
    }

    @Test
    void finalDeliveryClosesExistingStreamingCardInsteadOfSendingDuplicateFinalCard() {
        CapturingMessageSender messageSender = new CapturingMessageSender();
        InMemoryStreamingCardStore store = new InMemoryStreamingCardStore();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            messageSender,
            FeishuTypingReactionLifecycle.NOOP,
            store,
            new ObjectMapper()
        );

        adapter.consumeOutboundFrame(profile(), draftUpdateFrame(2L, "hel"));
        adapter.consumeOutboundFrame(profile(), finalFrameWithMessageId("session-message-reply-1", List.of(
            Map.of("type", "TEXT", "text", "hello")
        )));

        assertEquals(1, messageSender.createCardCommands.size());
        assertEquals(1, messageSender.sendCardCommands.size());
        assertEquals(1, messageSender.updateTextCommands.size());
        assertEquals("hello", messageSender.updateTextCommands.getFirst().content());
        assertEquals(1, messageSender.updateSettingsCommands.size());
    }

    @Test
    void finalDeliveryTreatsFeishuStreamingModeClosedAsAlreadyClosed() {
        StreamingModeClosedMessageSender messageSender = new StreamingModeClosedMessageSender();
        InMemoryStreamingCardStore store = new InMemoryStreamingCardStore();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            messageSender,
            FeishuTypingReactionLifecycle.NOOP,
            store,
            new ObjectMapper()
        );

        messageSender.failUpdateText = false;
        adapter.consumeOutboundFrame(profile(), draftUpdateFrame(2L, "hel"));
        messageSender.failUpdateText = true;

        adapter.consumeOutboundFrame(profile(), finalFrameWithMessageId("session-message-reply-1", List.of(
            Map.of("type", "TEXT", "text", "hello")
        )));

        assertEquals(1, messageSender.updateTextCommands.size());
        assertEquals(0, messageSender.updateSettingsCommands.size());
        FeishuStreamingReplyCardState state = store.find(FeishuStreamingReplyCardKey.fromFrame(draftUpdateFrame(2L, "ignored"))).orElseThrow();
        assertTrue(state.closed());
        assertEquals("hel", state.content());
    }

    @Test
    void draftCompleteTreatsFeishuStreamingModeClosedAsAlreadyClosed() {
        StreamingModeClosedMessageSender messageSender = new StreamingModeClosedMessageSender();
        InMemoryStreamingCardStore store = new InMemoryStreamingCardStore();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            messageSender,
            FeishuTypingReactionLifecycle.NOOP,
            store,
            new ObjectMapper()
        );

        messageSender.failUpdateText = false;
        adapter.consumeOutboundFrame(profile(), draftUpdateFrame(2L, "hello"));
        messageSender.failUpdateSettings = true;

        adapter.consumeOutboundFrame(profile(), draftCompleteFrame(4L, Map.of("type", "TEXT", "text", "hello")));

        assertEquals(1, messageSender.updateSettingsCommands.size());
        FeishuStreamingReplyCardState state = store.find(FeishuStreamingReplyCardKey.fromFrame(draftUpdateFrame(4L, "ignored"))).orElseThrow();
        assertTrue(state.closed());
        assertEquals("hello", state.content());
    }

    @Test
    void typingStartClearsTypingReactionAndPreparesStreamingCard() {
        CapturingTypingReactionLifecycle typingLifecycle = new CapturingTypingReactionLifecycle();
        CapturingMessageSender messageSender = new CapturingMessageSender();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            messageSender,
            typingLifecycle,
            new InMemoryStreamingCardStore(),
            new ObjectMapper()
        );
        ChannelOutboundFrame frame = typingFrame();

        adapter.consumeOutboundFrame(profile(), frame);

        assertEquals(List.of("session-1"), typingLifecycle.deletedSessions);
        assertEquals(1, messageSender.createCardCommands.size());
        assertTrue(messageSender.createCardCommands.getFirst().cardJson().contains("\"content\":\"思考中...\""));
        assertEquals(1, messageSender.sendCardCommands.size());
        assertEquals(0, messageSender.updateTextCommands.size());
    }

    @Test
    void descriptorDeclaresTypingAndDraftSupportForStreamingCards() {
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            new CapturingMessageSender(),
            FeishuTypingReactionLifecycle.NOOP,
            new InMemoryStreamingCardStore(),
            new ObjectMapper()
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> outbound = (Map<String, Object>) adapter.descriptor().get("outbound");

        assertEquals(true, outbound.get("supportsTyping"));
        assertEquals(true, outbound.get("supportsDraftUpdate"));
    }

    @Test
    void descriptorKeepsAppIdOnAccountConfigAndSecretsOnCredential() {
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            new CapturingMessageSender(),
            FeishuTypingReactionLifecycle.NOOP,
            new InMemoryStreamingCardStore(),
            new ObjectMapper()
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> accountConfigSchema = (Map<String, Object>) adapter.descriptor().get("accountConfigSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> accountProperties = (Map<String, Object>) accountConfigSchema.get("properties");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> accountConfigUiSchema =
            (List<Map<String, Object>>) adapter.descriptor().get("accountConfigUiSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> credentialSchema = (Map<String, Object>) adapter.descriptor().get("credentialSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> credentialProperties = (Map<String, Object>) credentialSchema.get("properties");
        @SuppressWarnings("unchecked")
        List<String> credentialRequired = (List<String>) credentialSchema.get("required");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> credentialUiSchema =
            (List<Map<String, Object>>) adapter.descriptor().get("credentialUiSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> profileConfigSchema = (Map<String, Object>) adapter.descriptor().get("configSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> profileProperties = (Map<String, Object>) profileConfigSchema.get("properties");

        assertTrue(accountProperties.containsKey("appId"));
        assertEquals(List.of("appId"), accountConfigSchema.get("required"));
        assertEquals(List.of("/appId"), accountConfigUiSchema.stream()
            .map(field -> field.get("key"))
            .toList());
        assertEquals(true, accountConfigUiSchema.getFirst().get("required"));
        assertTrue(credentialProperties.containsKey("appSecret"));
        assertTrue(credentialProperties.containsKey("verificationToken"));
        assertTrue(credentialProperties.containsKey("encryptKey"));
        assertEquals(List.of("appSecret"), credentialRequired);
        assertEquals(List.of("/appSecret", "/verificationToken", "/encryptKey"), credentialUiSchema.stream()
            .map(field -> field.get("key"))
            .toList());
        assertEquals(true, credentialUiSchema.get(0).get("required"));
        assertEquals(false, credentialUiSchema.get(1).get("required"));
        assertEquals(false, credentialUiSchema.get(2).get("required"));
        assertTrue(profileProperties.containsKey("receiveIdType"));
        assertEquals(false, profileProperties.containsKey("appId"));
        assertEquals(false, profileProperties.containsKey("verificationToken"));
        assertEquals(false, profileProperties.containsKey("encryptKey"));
    }

    private static ChannelGatewayProfile profile() {
        Instant now = Instant.parse("2026-05-05T00:00:00Z");
        return new ChannelGatewayProfile(
            "profile-1",
            FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE,
            "Feishu",
            ChannelProfileStatus.ACTIVE,
            true,
            Map.of("receiveIdType", "chat_id"),
            null,
            "account-1",
            false,
            1,
            now,
            now
        );
    }

    private static FeishuStreamingReplyCardProperties streamingProperties(Duration emptyCardDeleteDelay) {
        FeishuStreamingReplyCardProperties properties = new FeishuStreamingReplyCardProperties();
        properties.setEmptyCardDeleteDelay(emptyCardDeleteDelay);
        return properties;
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static ChannelOutboundFrame finalFrame() {
        return finalFrameWithMessageId("message-1", List.of(
            Map.of("type", "TEXT", "text", "hello"),
            Map.of("type", "TEXT", "text", "world")
        ));
    }

    private static ChannelOutboundFrame finalFrameWithBlocks(List<Map<String, Object>> messageBlocks) {
        return finalFrameWithMessageId("message-1", messageBlocks);
    }

    private static ChannelOutboundFrame finalFrameWithMessageId(String messageId, List<Map<String, Object>> messageBlocks) {
        String frameId = "channel-profile-d28237bb:session-v2-b50aa165:session-message-4b8e6432-b10a-358c-a77e-197599f37579:FINAL_DELIVERY";
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            frameId,
            "profile-1",
            FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE,
            "assistant-1",
            "chat-1",
            "session-1",
            null,
            null,
            null,
            1L,
            ChannelOutboundFrameKind.FINAL_DELIVERY,
            Instant.parse("2026-05-05T00:00:00Z"),
            frameId,
            Map.of(
                "sessionMessageId", messageId,
                "messageSequence", 1,
                "messageBlocks", messageBlocks
            ),
            null
        );
    }

    private static ChannelOutboundFrame typingFrame() {
        return typingFrameWithMessageId("message-1");
    }

    private static ChannelOutboundFrame typingFrameWithMessageId(String messageId) {
        String frameId = "profile-1:turn-1:1:TYPING_START";
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            frameId,
            "profile-1",
            FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE,
            "assistant-1",
            "chat-1",
            "session-1",
            "turn-1",
            "turn-1:exec-1",
            1L,
            null,
            ChannelOutboundFrameKind.TYPING_START,
            Instant.parse("2026-05-05T00:00:00Z"),
            frameId,
            Map.of("messageId", messageId),
            null
        );
    }

    private static ChannelOutboundFrame typingStopFrameWithMessageId(long sourceSeq, String messageId) {
        String frameId = "profile-1:turn-1:" + sourceSeq + ":TYPING_STOP";
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            frameId,
            "profile-1",
            FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE,
            "assistant-1",
            "chat-1",
            "session-1",
            "turn-1",
            "exec-1",
            sourceSeq,
            null,
            ChannelOutboundFrameKind.TYPING_STOP,
            Instant.parse("2026-05-05T00:00:00Z"),
            frameId,
            Map.of("messageId", messageId),
            null
        );
    }

    private static ChannelOutboundFrame draftDiscardFrame(long sourceSeq, String messageId, String reason) {
        String frameId = "profile-1:exec-1:" + sourceSeq + ":DRAFT_DISCARD";
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            frameId,
            "profile-1",
            FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE,
            "assistant-1",
            "chat-1",
            "session-1",
            "turn-1",
            "exec-1",
            sourceSeq,
            null,
            ChannelOutboundFrameKind.DRAFT_DISCARD,
            Instant.parse("2026-05-05T00:00:00Z"),
            frameId,
            Map.of(
                "messageId", messageId,
                "reason", reason
            ),
            null
        );
    }

    private static ChannelOutboundFrame draftUpdateFrame(long sourceSeq, String delta) {
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            "profile-1:exec-1:" + sourceSeq + ":DRAFT_UPDATE",
            "profile-1",
            FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE,
            "assistant-1",
            "chat-1",
            "session-1",
            "turn-1",
            "exec-1",
            sourceSeq,
            null,
            ChannelOutboundFrameKind.DRAFT_UPDATE,
            Instant.parse("2026-05-05T00:00:00Z"),
            "profile-1:exec-1:" + sourceSeq + ":DRAFT_UPDATE",
            Map.of(
                "messageId", "session-message-reply-1",
                "blockId", "reply-block-1",
                "blockType", "TEXT",
                "delta", delta
            ),
            null
        );
    }

    private static ChannelOutboundFrame draftCompleteFrame(long sourceSeq, Map<String, Object> block) {
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            "profile-1:exec-1:" + sourceSeq + ":DRAFT_COMPLETE",
            "profile-1",
            FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE,
            "assistant-1",
            "chat-1",
            "session-1",
            "turn-1",
            "exec-1",
            sourceSeq,
            null,
            ChannelOutboundFrameKind.DRAFT_COMPLETE,
            Instant.parse("2026-05-05T00:00:00Z"),
            "profile-1:exec-1:" + sourceSeq + ":DRAFT_COMPLETE",
            Map.of(
                "messageId", "session-message-reply-1",
                "blockId", "reply-block-1",
                "blockType", "TEXT",
                "block", block
            ),
            null
        );
    }

    private static final class CapturingCredentialProvider implements FeishuCredentialProvider {
        private String accountId;

        @Override
        public FeishuAppCredential resolve(String accountId) {
            this.accountId = accountId;
            return new FeishuAppCredential(accountId, "app-id", "secret");
        }
    }

    private static class CapturingMessageSender implements FeishuMessageSender {
        protected final List<FeishuCreateCardCommand> createCardCommands = new ArrayList<>();
        protected final List<FeishuSendInteractiveCardCommand> sendCardCommands = new ArrayList<>();
        protected final List<FeishuDeleteMessageCommand> deleteMessageCommands = new ArrayList<>();
        protected final List<FeishuUpdateCardTextCommand> updateTextCommands = new ArrayList<>();
        protected final List<FeishuUpdateCardSettingsCommand> updateSettingsCommands = new ArrayList<>();
        protected final CountDownLatch deleteMessageCalled = new CountDownLatch(1);

        @Override
        public FeishuCreateCardResult createCard(FeishuCreateCardCommand command) {
            createCardCommands.add(command);
            return new FeishuCreateCardResult("card-" + createCardCommands.size(), Map.of());
        }

        @Override
        public FeishuSendInteractiveCardResult sendInteractiveCard(FeishuSendInteractiveCardCommand command) {
            sendCardCommands.add(command);
            return new FeishuSendInteractiveCardResult("external-card-message-" + sendCardCommands.size(), Map.of());
        }

        @Override
        public void deleteMessage(FeishuDeleteMessageCommand command) {
            deleteMessageCommands.add(command);
            deleteMessageCalled.countDown();
        }

        @Override
        public void updateCardText(FeishuUpdateCardTextCommand command) {
            updateTextCommands.add(command);
        }

        @Override
        public void updateCardSettings(FeishuUpdateCardSettingsCommand command) {
            updateSettingsCommands.add(command);
        }
    }

    private static final class BlockingMessageSender extends CapturingMessageSender {
        private final CountDownLatch sendStarted = new CountDownLatch(1);
        private final CountDownLatch sendAllowed = new CountDownLatch(1);

        @Override
        public FeishuSendInteractiveCardResult sendInteractiveCard(FeishuSendInteractiveCardCommand command) {
            sendStarted.countDown();
            try {
                if (!sendAllowed.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release Feishu card send");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while blocking Feishu card send", error);
            }
            return super.sendInteractiveCard(command);
        }

        private void releaseSend() {
            sendAllowed.countDown();
        }
    }

    private static final class StreamingModeClosedMessageSender extends CapturingMessageSender {
        private boolean failUpdateText = true;
        private boolean failUpdateSettings = false;

        @Override
        public void updateCardText(FeishuUpdateCardTextCommand command) {
            super.updateCardText(command);
            if (failUpdateText) {
                throw streamingModeClosed("feishu update card text failed");
            }
        }

        @Override
        public void updateCardSettings(FeishuUpdateCardSettingsCommand command) {
            super.updateCardSettings(command);
            if (failUpdateSettings) {
                throw streamingModeClosed("feishu update card settings failed");
            }
        }

        private static FeishuApiException streamingModeClosed(String prefix) {
            return new FeishuApiException(
                prefix,
                300309,
                "ErrMsg: streaming mode is closed; ",
                "request-1"
            );
        }
    }

    private static final class CapturingTypingReactionLifecycle implements FeishuTypingReactionLifecycle {
        private final List<String> deletedSessions = new ArrayList<>();

        @Override
        public void deleteTypingReactionOnFirstOutboundFrame(
            ChannelGatewayProfile profile,
            String sessionId,
            String externalConversationId
        ) {
            deletedSessions.add(sessionId);
        }
    }

    private static final class InMemoryStreamingCardStore implements FeishuStreamingReplyCardStore {
        private final Map<FeishuStreamingReplyCardKey, FeishuStreamingReplyCardState> states = new LinkedHashMap<>();

        @Override
        public synchronized Optional<FeishuStreamingReplyCardState> find(FeishuStreamingReplyCardKey key) {
            return Optional.ofNullable(states.get(key));
        }

        @Override
        public synchronized void save(FeishuStreamingReplyCardState state) {
            states.put(state.key(), state);
        }

        @Override
        public synchronized void delete(FeishuStreamingReplyCardKey key) {
            states.remove(key);
        }
    }
}
