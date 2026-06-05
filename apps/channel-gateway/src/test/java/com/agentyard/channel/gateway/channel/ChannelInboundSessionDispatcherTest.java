package com.agentyard.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.agentyard.channel.gateway.extension.ExtensionRegistrationProperties;
import com.agentyard.channel.gateway.extension.ExtensionRegistrationService;
import com.agentyard.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.agentyard.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelInboundTurnMessageStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelInboundTurnStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelAttachment;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelConversation;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelInboundTurn;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelMessageRole;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelMessageSender;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelSenderType;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelTraceContext;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelTurnMessage;
import com.agentyard.contracts.session.SessionContracts.AcceptedSessionMessageAllocation;
import com.agentyard.contracts.session.SessionContracts.ChannelInboundSessionTurnRequest;
import com.agentyard.contracts.session.SessionContracts.ChannelInboundSessionTurnResponse;
import com.agentyard.contracts.session.SessionContracts.SessionMessageDeliveryStatus;
import com.agentyard.extension.sdk.protocol.DescriptorType;
import com.agentyard.extension.sdk.registration.ExtensionRegistrationLoader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChannelInboundSessionDispatcherTest {
    private static final String PROVIDER_TYPE = "feishu";
    private static EmbeddedPostgresTestDatabase database;

    private ChannelAdminRepository repository;
    private NormalizedChannelTurnIngestService ingestService;
    private CapturingSessionRuntimeClient runtimeClient;

    @BeforeAll
    static void startDatabase() throws Exception {
        database = new EmbeddedPostgresTestDatabase();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        database.close();
    }

    @BeforeEach
    void setUp() {
        database.reset();
        repository = new ChannelAdminRepository(database.dsl(), new ObjectMapper());
        ingestService = new NormalizedChannelTurnIngestService(repository, registrationService());
        runtimeClient = new CapturingSessionRuntimeClient();
        createProfile();
    }

    @Test
    void dispatchesMultiMessageTurnOnceAndStoresReturnedSessionId() {
        NormalizedChannelInboundTurn turn = turn("feishu:turn:1", List.of(
            message("evt-1", "msg-1", "hello", List.of()),
            message("evt-2", "msg-2", null, List.of(
                new NormalizedChannelAttachment("att-image", "file-image", "photo.png", "image/png", "https://example.test/photo.png", 100L, Map.of()),
                new NormalizedChannelAttachment("att-file", "file-pdf", "report.pdf", "application/pdf", "https://example.test/report.pdf", 200L, Map.of())
            ))
        ));
        ChannelInboundSessionDispatcher dispatcher = new ChannelInboundSessionDispatcher(repository, runtimeClient);

        var result = dispatcher.dispatch(turn, ingestService.ingest(turn, headers(turn.dedupKey())));

        assertEquals(ChannelInboundTurnStatus.DISPATCHED, result.status());
        assertEquals("session-v2-abc12345", result.sessionId());
        assertEquals(1, runtimeClient.requests.size());
        ChannelInboundSessionTurnRequest request = runtimeClient.requests.getFirst();
        assertEquals("assistant-1", request.assistantId());
        assertEquals("user-1", request.customerId());
        assertEquals("chat-1", request.externalConversationId());
        assertEquals(2, request.messages().size());
        assertEquals("hello", ((Map<?, ?>) request.messages().getFirst().message().blocks().getFirst()).get("text"));
        List<Object> attachmentBlocks = request.messages().get(1).message().blocks();
        assertEquals("IMAGE", ((Map<?, ?>) attachmentBlocks.get(0)).get("type"));
        assertEquals("CARD", ((Map<?, ?>) attachmentBlocks.get(1)).get("type"));
        assertEquals("FILE_ATTACHMENT", ((Map<?, ?>) attachmentBlocks.get(1)).get("cardType"));
        assertEquals("session-v2-abc12345", repository.listBindings("channel-profile-1").getFirst().sessionId());
        assertEquals(List.of("session-message-msg-1", "session-message-msg-2"), result.acceptedMessageIds());
    }

    @Test
    void sameDedupKeyReplayWithChangedOrExtraMessagesIsRejectedBeforeDispatch() {
        ChannelInboundSessionDispatcher dispatcher = new ChannelInboundSessionDispatcher(repository, runtimeClient);
        NormalizedChannelInboundTurn original = turn("feishu:turn:replay", List.of(message("evt-1", "msg-1", "hello", List.of())));
        dispatcher.dispatch(original, ingestService.ingest(original, headers(original.dedupKey())));
        runtimeClient.requests.clear();
        ChannelInboundTurnAudit audit = repository.findInboundTurnByDedupKey(original.dedupKey()).orElseThrow();

        IllegalArgumentException changedMessage = assertThrows(IllegalArgumentException.class, () -> ingestService.ingest(
            turn(original.dedupKey(), List.of(message("evt-1", "msg-changed", "changed", List.of()))),
            headers(original.dedupKey())
        ));
        IllegalArgumentException extraMessage = assertThrows(IllegalArgumentException.class, () -> ingestService.ingest(
            turn(original.dedupKey(), List.of(
                message("evt-1", "msg-1", "hello", List.of()),
                message("evt-2", "msg-2", "extra", List.of())
            )),
            headers(original.dedupKey())
        ));

        assertEquals("normalized turn dedupKey replay messages do not match persisted turn", changedMessage.getMessage());
        assertEquals("normalized turn dedupKey replay messages do not match persisted turn", extraMessage.getMessage());
        assertEquals(0, runtimeClient.requests.size());
        assertEquals(1, repository.listInboundTurnMessages(audit.turnId()).size());
    }

    @Test
    void allDuplicateTurnRecordsAuditAndDoesNotCallApi() {
        ChannelInboundSessionDispatcher dispatcher = new ChannelInboundSessionDispatcher(repository, runtimeClient);
        NormalizedChannelInboundTurn first = turn("feishu:turn:first", List.of(message("evt-1", "msg-1", "hello", List.of())));
        dispatcher.dispatch(first, ingestService.ingest(first, headers(first.dedupKey())));
        runtimeClient.requests.clear();

        NormalizedChannelInboundTurn duplicate = turn("feishu:turn:duplicate", List.of(message("evt-2", "msg-1", "hello again", List.of())));
        var result = dispatcher.dispatch(duplicate, ingestService.ingest(duplicate, headers(duplicate.dedupKey())));

        assertEquals(ChannelInboundTurnStatus.DUPLICATE, result.status());
        assertEquals(List.of("msg-1"), result.duplicateExternalMessageIds());
        assertEquals(0, runtimeClient.requests.size());
        ChannelInboundTurnAudit audit = repository.findInboundTurnByDedupKey(duplicate.dedupKey()).orElseThrow();
        assertEquals(ChannelInboundTurnStatus.DUPLICATE, repository.findInboundTurnByDedupKey(duplicate.dedupKey()).orElseThrow().status());
        assertEquals(ChannelInboundTurnMessageStatus.DUPLICATE, repository.listInboundTurnMessages(audit.turnId()).getFirst().status());
    }

    @Test
    void replayAfterDispatchedPreservesActualSessionId() {
        ChannelInboundSessionDispatcher dispatcher = new ChannelInboundSessionDispatcher(repository, runtimeClient);
        NormalizedChannelInboundTurn turn = turn("feishu:turn:dispatched-replay", List.of(message("evt-1", "msg-1", "hello", List.of())));
        dispatcher.dispatch(turn, ingestService.ingest(turn, headers(turn.dedupKey())));
        runtimeClient.requests.clear();

        var replayed = dispatcher.dispatch(turn, ingestService.ingest(turn, headers(turn.dedupKey())));

        assertEquals(ChannelInboundTurnStatus.DISPATCHED, replayed.status());
        assertEquals("session-v2-abc12345", replayed.sessionId());
        assertEquals(0, runtimeClient.requests.size());
        assertEquals("session-v2-abc12345", repository.findInboundTurnByDedupKey(turn.dedupKey()).orElseThrow().sessionId());
    }

    @Test
    void allDuplicateTurnUsesDedupeSessionIdNotSessionMessageId() {
        ChannelInboundSessionDispatcher dispatcher = new ChannelInboundSessionDispatcher(repository, runtimeClient);
        NormalizedChannelInboundTurn first = turn("feishu:turn:first", List.of(message("evt-1", "msg-1", "hello", List.of())));
        dispatcher.dispatch(first, ingestService.ingest(first, headers(first.dedupKey())));
        runtimeClient.requests.clear();

        NormalizedChannelInboundTurn duplicate = turn("feishu:turn:duplicate-session", List.of(message("evt-2", "msg-1", "hello again", List.of())));
        var result = dispatcher.dispatch(duplicate, ingestService.ingest(duplicate, headers(duplicate.dedupKey())));

        assertEquals(ChannelInboundTurnStatus.DUPLICATE, result.status());
        assertEquals("session-v2-abc12345", result.sessionId());
        assertEquals(0, runtimeClient.requests.size());
        assertEquals("session-v2-abc12345", repository.findInboundTurnByDedupKey(duplicate.dedupKey()).orElseThrow().sessionId());
    }

    @Test
    void changedCustomerDoesNotReuseBindingSessionIdFromSameConversation() {
        ChannelInboundSessionDispatcher dispatcher = new ChannelInboundSessionDispatcher(repository, runtimeClient);
        NormalizedChannelInboundTurn first = turn("feishu:turn:identity-first", List.of(message("evt-1", "msg-1", "hello", List.of())));
        dispatcher.dispatch(first, ingestService.ingest(first, headers(first.dedupKey())));

        NormalizedChannelInboundTurn second = turnForUser(
            "feishu:turn:identity-second",
            "user-2",
            List.of(message("evt-2", "msg-2", "hello from another customer", List.of()))
        );
        dispatcher.dispatch(second, ingestService.ingest(second, headers(second.dedupKey())));

        ChannelInboundSessionTurnRequest request = runtimeClient.requests.getLast();
        assertEquals("user-2", request.customerId());
        assertNull(request.sessionId());
    }

    @Test
    void ingestRejectsMissingSenderNameBeforeDispatch() {
        NormalizedChannelInboundTurn turn = new NormalizedChannelInboundTurn(
            PROVIDER_TYPE,
            "channel-profile-1",
            "feishu:turn:missing-sender-name",
            "chat-1",
            "user-1",
            new NormalizedChannelConversation("chat-1", "P2P", null, Map.of()),
            new NormalizedChannelMessageSender(NormalizedChannelSenderType.CUSTOMER, "user-1", null, Map.of()),
            List.of(message("evt-1", "msg-1", "hello", List.of())),
            Map.of("externalConversationId", "chat-1"),
            Map.of("raw", "payload"),
            new NormalizedChannelTraceContext("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", null),
            Map.of("source", "test")
        );

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> ingestService.ingest(turn, headers(turn.dedupKey()))
        );

        assertEquals("normalizedTurn.sender.senderName is required", error.getMessage());
        assertEquals(0, runtimeClient.requests.size());
    }

    @Test
    void mixedDuplicateAndNewTurnDispatchesOnlyNewMessage() {
        ChannelInboundSessionDispatcher dispatcher = new ChannelInboundSessionDispatcher(repository, runtimeClient);
        NormalizedChannelInboundTurn first = turn("feishu:turn:first", List.of(message("evt-1", "msg-1", "hello", List.of())));
        dispatcher.dispatch(first, ingestService.ingest(first, headers(first.dedupKey())));
        runtimeClient.requests.clear();

        NormalizedChannelInboundTurn mixed = turn("feishu:turn:mixed", List.of(
            message("evt-2", "msg-1", "duplicate", List.of()),
            message("evt-3", "msg-2", "new", List.of())
        ));
        var result = dispatcher.dispatch(mixed, ingestService.ingest(mixed, headers(mixed.dedupKey())));

        assertEquals(ChannelInboundTurnStatus.PARTIALLY_DISPATCHED, result.status());
        assertEquals(1, runtimeClient.requests.size());
        assertEquals(1, runtimeClient.requests.getFirst().messages().size());
        assertEquals("msg-2", runtimeClient.requests.getFirst().messages().getFirst().externalMessageId());
        assertEquals(List.of("msg-1"), result.duplicateExternalMessageIds());
    }

    @Test
    void apiPreflightRejectionMarksReceivedMessagesRejected() {
        runtimeClient.rejectNext = true;
        ChannelInboundSessionDispatcher dispatcher = new ChannelInboundSessionDispatcher(repository, runtimeClient);
        NormalizedChannelInboundTurn turn = turn("feishu:turn:reject", List.of(message("evt-1", "msg-1", "hello", List.of())));

        var result = dispatcher.dispatch(turn, ingestService.ingest(turn, headers(turn.dedupKey())));

        ChannelInboundTurnAudit audit = repository.findInboundTurnByDedupKey(turn.dedupKey()).orElseThrow();
        assertEquals(ChannelInboundTurnStatus.REJECTED, result.status());
        assertEquals(ChannelInboundTurnStatus.REJECTED, audit.status());
        assertEquals(ChannelInboundTurnMessageStatus.REJECTED, repository.listInboundTurnMessages(audit.turnId()).getFirst().status());
    }

    @Test
    void dispatchFailureAfterDedupeClaimRetriesOnlyReceivedMessages() {
        runtimeClient.failNext = true;
        ChannelInboundSessionDispatcher dispatcher = new ChannelInboundSessionDispatcher(repository, runtimeClient);
        NormalizedChannelInboundTurn turn = turn("feishu:turn:retry", List.of(message("evt-1", "msg-1", "hello", List.of())));

        var failed = dispatcher.dispatch(turn, ingestService.ingest(turn, headers(turn.dedupKey())));

        ChannelInboundTurnAudit audit = repository.findInboundTurnByDedupKey(turn.dedupKey()).orElseThrow();
        assertEquals(ChannelInboundTurnStatus.FAILED, failed.status());
        assertEquals(ChannelInboundTurnMessageStatus.RECEIVED, repository.listInboundTurnMessages(audit.turnId()).getFirst().status());

        var retried = dispatcher.dispatch(turn, ingestService.ingest(turn, headers(turn.dedupKey())));

        assertEquals(ChannelInboundTurnStatus.DISPATCHED, retried.status());
        assertEquals(2, runtimeClient.requests.size());
        assertEquals("msg-1", runtimeClient.requests.getLast().messages().getFirst().externalMessageId());
    }

    private void createProfile() {
        Instant now = Instant.parse("2026-04-30T00:00:00Z");
        repository.createProfile(new ChannelGatewayProfile(
            "channel-profile-1",
            PROVIDER_TYPE,
            "Internal IM",
            ChannelProfileStatus.ACTIVE,
            true,
            Map.of(),
            new ChannelAssistantBinding("assistant-1", null),
            "integration-account-1",
            false,
            1,
            now,
            now
        ), null);
    }

    private static NormalizedChannelInboundTurn turn(String dedupKey, List<NormalizedChannelTurnMessage> messages) {
        return turnForUser(dedupKey, "user-1", messages);
    }

    private static NormalizedChannelInboundTurn turnForUser(
        String dedupKey,
        String externalUserId,
        List<NormalizedChannelTurnMessage> messages
    ) {
        return new NormalizedChannelInboundTurn(
            PROVIDER_TYPE,
            "channel-profile-1",
            dedupKey,
            "chat-1",
            externalUserId,
            new NormalizedChannelConversation("chat-1", "P2P", null, Map.of()),
            new NormalizedChannelMessageSender(NormalizedChannelSenderType.CUSTOMER, externalUserId, "Alice", Map.of()),
            messages,
            Map.of("externalConversationId", "chat-1"),
            Map.of("raw", "payload"),
            new NormalizedChannelTraceContext("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", null),
            Map.of("source", "test")
        );
    }

    private static NormalizedChannelTurnMessage message(
        String externalEventId,
        String externalMessageId,
        String text,
        List<NormalizedChannelAttachment> attachments
    ) {
        return new NormalizedChannelTurnMessage(
            externalEventId,
            externalMessageId,
            Instant.parse("2026-04-30T00:00:01Z"),
            NormalizedChannelMessageRole.USER,
            null,
            "TEXT",
            text,
            attachments,
            Map.of()
        );
    }

    private static NormalizedChannelEventHeaders headers(String dedupKey) {
        return new NormalizedChannelEventHeaders(
            ExtensionRegistrationLoader.CORE_CHANNEL_GATEWAY_REGISTRATION_ID,
            DescriptorType.CHANNEL_PROVIDER.wireValue(),
            PROVIDER_TYPE,
            "trace-1",
            "request-1",
            dedupKey
        );
    }

    private static ExtensionRegistrationService registrationService() {
        return new ExtensionRegistrationService(
            new ExtensionRegistrationProperties(null),
            "http://channel-gateway.example.com",
            "http://agent-runtime.example.com"
        );
    }

    private static final class CapturingSessionRuntimeClient implements ChannelSessionRuntimeClient {
        private final List<ChannelInboundSessionTurnRequest> requests = new ArrayList<>();
        private boolean rejectNext;
        private boolean failNext;

        @Override
        public ChannelInboundSessionTurnResponse dispatchInboundTurn(ChannelInboundSessionTurnRequest request) {
            requests.add(request);
            if (rejectNext) {
                rejectNext = false;
                throw new ChannelInboundSessionRejectedException("session is busy");
            }
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("api unavailable");
            }
            List<AcceptedSessionMessageAllocation> allocations = new ArrayList<>();
            for (int index = 0; index < request.messages().size(); index += 1) {
                String externalMessageId = request.messages().get(index).externalMessageId();
                allocations.add(new AcceptedSessionMessageAllocation(
                    index,
                    null,
                    "session-message-" + externalMessageId,
                    index
                ));
            }
            return new ChannelInboundSessionTurnResponse(
                "session-v2-abc12345",
                "session-turn-1",
                SessionMessageDeliveryStatus.ACCEPTED,
                allocations.stream().map(AcceptedSessionMessageAllocation::messageId).toList(),
                allocations,
                List.of(),
                null
            );
        }
    }
}
