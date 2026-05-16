package com.lynxus.platform.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.PlaybookRunStatus;
import com.lynxus.contracts.session.SessionContracts.SessionActorType;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import com.lynxus.contracts.session.SessionContracts.SessionEventType;
import com.lynxus.contracts.session.SessionContracts.SessionMessage;
import com.lynxus.contracts.session.SessionContracts.SessionMessageProducerType;
import com.lynxus.contracts.session.SessionContracts.SessionMessageRole;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSender;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSenderType;
import com.lynxus.contracts.session.SessionContracts.SessionMessageStatus;
import com.lynxus.platform.testing.EmbeddedPostgresTestDatabase;
import com.lynxus.persistence.session.SessionRuntimeStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.JSONB;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static com.lynxus.persistence.jooq.Tables.CHANNEL_SESSION_BINDING_SNAPSHOT;
import static com.lynxus.persistence.jooq.Tables.SESSION_RUNTIME_MESSAGE;
import static com.lynxus.persistence.jooq.Tables.SESSION_RUNTIME_SESSION;
import static com.lynxus.persistence.jooq.Tables.SESSION_RUNTIME_TURN;

class JooqSessionRuntimeRepositoryTest {
    private static EmbeddedPostgresTestDatabase database;

    private JooqSessionRuntimeRepository repository;

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
        repository = new JooqSessionRuntimeRepository(database.dsl(), new ObjectMapper());
    }

    @Test
    void shouldPersistSessionEventsPlaybookRunsAndChangeStamp() {
        SessionRuntimeDtos.SessionRuntimeSessionDto session = new SessionRuntimeDtos.SessionRuntimeSessionDto(
            "session-1",
            "scenario-1",
            "退款会话",
            "customer-1",
            "assistant-1",
            "Assistant",
            "1.0.0",
            "ACTIVE",
            "agent-1",
            "agent-1",
            null,
            false,
            false,
            false,
            false,
            Map.of("channel", "web"),
            null,
            Instant.parse("2026-04-21T00:00:00Z"),
            Instant.parse("2026-04-21T00:00:00Z"),
            null,
            0L,
            0L
        );
        SessionMessage message = new SessionMessage(
            "message-1",
            "session-1",
            1L,
            "turn-1",
            0,
            SessionMessageProducerType.EXTERNAL,
            null,
            null,
            Instant.parse("2026-04-21T00:00:01Z"),
            SessionMessageRole.USER,
            new SessionMessageSender(SessionMessageSenderType.CUSTOMER, "customer-1", "customer-1"),
            SessionMessageStatus.SENT,
            List.of(Map.of("type", "TEXT", "text", "退款")),
            Map.of(),
            null,
            null,
            null,
            Instant.parse("2026-04-21T00:00:01Z"),
            Instant.parse("2026-04-21T00:00:01Z")
        );
        SessionEvent event = new SessionEvent(
            "event-1",
            "session-1",
            1L,
            SessionEventType.OWNER_SWITCH,
            Instant.parse("2026-04-21T00:00:02Z"),
            SessionActorType.AGENT,
            "agent-1",
            Map.of("fromAgentId", "agent-0", "toAgentId", "agent-1"),
            message.messageId(),
            null,
            null
        );
        PlaybookRun playbookRun = new PlaybookRun(
            "run-1",
            "session-1",
            "event-1",
            "playbook-1",
            "agent-1",
            PlaybookRunStatus.RUNNING,
            Map.of("amount", 1),
            Map.of(),
            null,
            Instant.parse("2026-04-21T00:00:02Z"),
            Instant.parse("2026-04-21T00:00:03Z"),
            null
        );

        repository.saveSession(session);
        repository.appendMessage(message);
        repository.appendEvent(event);
        repository.savePlaybookRun(playbookRun);

        assertEquals(1, repository.listSessions().size());
        assertEquals(1L, repository.nextMessageSequence("session-unknown"));
        assertEquals(2L, repository.nextMessageSequence("session-1"));
        assertEquals(1L, repository.nextEventSequence("session-unknown"));
        assertEquals(2L, repository.nextEventSequence("session-1"));
        assertEquals("session-1", repository.findActiveSession("customer-1", "assistant-1").orElseThrow().id());
        assertEquals("message-1", repository.listMessages("session-1").getFirst().messageId());
        assertEquals("event-1", repository.listEvents("session-1").getFirst().eventId());
        assertEquals("run-1", repository.listPlaybookRuns("session-1").getFirst().runId());

        SessionRuntimeRepository.SessionRuntimeChangeStamp stamp = repository.findSessionChangeStamp("session-1").orElseThrow();
        assertEquals(1L, stamp.latestMessageSequence());
        assertEquals(1L, stamp.latestEventSequence());
        assertTrue(stamp.fingerprint().startsWith("session-1:"));
    }

    @Test
    void listsChannelOutboundFinalMessagesByFinalSequenceNotCreatedAt() {
        Instant now = Instant.parse("2026-05-02T00:00:00Z");
        repository.saveSession(session("session-1", "assistant-1", now));
        repository.saveSession(session("session-2", "assistant-1", now));
        insertSnapshot("binding-1", "session-1", "channel-profile-1", "assistant-1", "chat-1", now);
        insertSnapshot("binding-2", "session-2", "channel-profile-1", "assistant-1", "chat-2", now);
        repository.appendMessage(message(
            "message-final-1",
            "session-1",
            1L,
            SessionMessageRole.ASSISTANT,
            Instant.parse("2026-05-02T00:00:10Z")
        ));
        repository.appendMessage(message(
            "message-final-2",
            "session-2",
            1L,
            SessionMessageRole.ASSISTANT,
            Instant.parse("2026-05-02T00:00:01Z")
        ));
        repository.appendMessage(message(
            "message-user-3",
            "session-2",
            2L,
            SessionMessageRole.USER,
            Instant.parse("2026-05-02T00:00:20Z")
        ));

        var messages = repository.listChannelOutboundFinalMessages("channel-profile-1", 0L, 10);

        assertEquals(List.of("message-final-1", "message-final-2"), messages.stream().map(item -> item.messageId()).toList());
        assertTrue(messages.get(0).finalSequence() < messages.get(1).finalSequence());
    }

    @Test
    void shouldEnforceActiveSessionIdentityAndTurnMessageSchema() {
        insertSessionRow("web-session-1", "WEB", null, null, "customer-1", "assistant-1", "IDLE");

        assertThrows(DataAccessException.class, () ->
            insertSessionRow("web-session-duplicate", "WEB", null, null, "customer-1", "assistant-1", "ACTIVE")
        );

        insertSessionRow("web-session-ended", "WEB", null, null, "customer-1", "assistant-1", "ENDED");
        insertSessionRow("channel-session-1", "CHANNEL", "channel-profile-1", "chat-1", "customer-1", "assistant-1", "IDLE");
        insertSessionRow("channel-session-2", "CHANNEL", "channel-profile-1", "chat-2", "customer-1", "assistant-1", "IDLE");

        assertThrows(DataAccessException.class, () ->
            insertSessionRow(
                "channel-session-duplicate",
                "CHANNEL",
                "channel-profile-1",
                "chat-1",
                "customer-1",
                "assistant-1",
                "ACTIVE"
            )
        );

        database.dsl().insertInto(SESSION_RUNTIME_TURN)
            .set(SESSION_RUNTIME_TURN.TURN_ID, "turn-1")
            .set(SESSION_RUNTIME_TURN.SESSION_ID, "web-session-1")
            .set(SESSION_RUNTIME_TURN.DEDUP_KEY, "dedup-1")
            .set(SESSION_RUNTIME_TURN.TRIGGER_TYPE, "USER_TURN")
            .set(SESSION_RUNTIME_TURN.STATUS, "WORKFLOW_ACCEPTED")
            .set(SESSION_RUNTIME_TURN.INPUT_ALLOCATIONS, JSONB.valueOf("""
                [{"requestIndex":0,"messageId":"message-1","clientMessageId":"client-1"}]
                """))
            .set(SESSION_RUNTIME_TURN.ACCEPTED_INPUT_MESSAGE_IDS, JSONB.valueOf("[\"message-1\"]"))
            .set(SESSION_RUNTIME_TURN.DUPLICATE_EXTERNAL_MESSAGE_IDS, JSONB.valueOf("[]"))
            .set(SESSION_RUNTIME_TURN.MESSAGE_IDS, JSONB.valueOf("[\"message-1\"]"))
            .set(SESSION_RUNTIME_TURN.TEMPORAL_UPDATE_ID, "turn-1")
            .set(SESSION_RUNTIME_TURN.METADATA, JSONB.valueOf("{}"))
            .set(SESSION_RUNTIME_TURN.CREATED_AT, OffsetDateTime.parse("2026-05-02T00:00:00Z"))
            .set(SESSION_RUNTIME_TURN.UPDATED_AT, OffsetDateTime.parse("2026-05-02T00:00:01Z"))
            .execute();

        insertMessageRow("message-1", "web-session-1", 1L, "turn-1", 0, "external-1");

        assertEquals("WORKFLOW_ACCEPTED", database.dsl()
            .select(SESSION_RUNTIME_TURN.STATUS)
            .from(SESSION_RUNTIME_TURN)
            .where(SESSION_RUNTIME_TURN.TURN_ID.eq("turn-1"))
            .fetchOne(SESSION_RUNTIME_TURN.STATUS));
        assertThrows(DataAccessException.class, () ->
            insertMessageRow("message-2", "web-session-1", 2L, "turn-1", 0, "external-2")
        );
        assertThrows(DataAccessException.class, () ->
            insertMessageRow("message-3", "web-session-1", 3L, "turn-2", 0, "external-1")
        );
    }

    @Test
    void createOrReuseActiveSessionUsesScopeAwareDbIdentity() {
        Instant now = Instant.parse("2026-05-03T00:00:00Z");
        SessionRuntimeStore.SessionRuntimeSessionData first = repository.createOrReuseActiveSession(storeSession(
            "web-session-1",
            "WEB",
            null,
            null,
            "customer-1",
            "assistant-1",
            now
        ));
        SessionRuntimeStore.SessionRuntimeSessionData replay = repository.createOrReuseActiveSession(storeSession(
            "web-session-replay",
            "WEB",
            null,
            null,
            "customer-1",
            "assistant-1",
            now.plusSeconds(1)
        ));

        assertEquals("web-session-1", first.id());
        assertEquals(first.id(), replay.id());
        assertEquals("web-session-1", repository.findActiveSession("customer-1", "assistant-1").orElseThrow().id());

        SessionRuntimeStore.SessionRuntimeSessionData channelChat1 = repository.createOrReuseActiveSession(storeSession(
            "channel-session-1",
            "CHANNEL",
            "channel-profile-1",
            "chat-1",
            "customer-1",
            "assistant-1",
            now
        ));
        SessionRuntimeStore.SessionRuntimeSessionData channelChat2 = repository.createOrReuseActiveSession(storeSession(
            "channel-session-2",
            "CHANNEL",
            "channel-profile-1",
            "chat-2",
            "customer-1",
            "assistant-1",
            now
        ));
        SessionRuntimeStore.SessionRuntimeSessionData channelReplay = repository.createOrReuseActiveSession(storeSession(
            "channel-session-replay",
            "CHANNEL",
            "channel-profile-1",
            "chat-1",
            "customer-1",
            "assistant-1",
            now.plusSeconds(1)
        ));

        assertEquals("channel-session-1", channelChat1.id());
        assertEquals("channel-session-2", channelChat2.id());
        assertEquals(channelChat1.id(), channelReplay.id());
        assertNotEquals(channelChat1.id(), channelChat2.id());
        assertEquals("web-session-1", repository.findActiveSession("customer-1", "assistant-1").orElseThrow().id());
    }

    @Test
    void concurrentCreateOrReuseActiveSessionReturnsOneDbSelectedSession() throws Exception {
        Instant now = Instant.parse("2026-05-03T00:00:00Z");
        int workerCount = 8;
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(workerCount);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int index = 0; index < workerCount; index++) {
                int requestIndex = index;
                calls.add(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return repository.createOrReuseActiveSession(storeSession(
                        "web-session-concurrent-" + requestIndex,
                        "WEB",
                        null,
                        null,
                        "customer-concurrent",
                        "assistant-concurrent",
                        now.plusMillis(requestIndex)
                    )).id();
                });
            }
            var futures = calls.stream().map(executor::submit).toList();
            start.countDown();

            Set<String> sessionIds = new HashSet<>();
            for (var future : futures) {
                sessionIds.add(future.get(10, TimeUnit.SECONDS));
            }

            assertEquals(1, sessionIds.size());
            assertEquals(1, database.dsl()
                .selectCount()
                .from(SESSION_RUNTIME_SESSION)
                .where(SESSION_RUNTIME_SESSION.ENTRY_SCOPE.eq("WEB"))
                .and(SESSION_RUNTIME_SESSION.CUSTOMER_ID.eq("customer-concurrent"))
                .and(SESSION_RUNTIME_SESSION.ASSISTANT_ID.eq("assistant-concurrent"))
                .and(SESSION_RUNTIME_SESSION.STATUS.ne("ENDED"))
                .fetchOne(0, int.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void workerProjectionUpdatePreservesApiOwnedIdentityAndSequence() {
        Instant createdAt = Instant.parse("2026-05-03T00:00:00Z");
        repository.createOrReuseActiveSession(storeSession(
            "channel-session-1",
            "CHANNEL",
            "channel-profile-1",
            "chat-1",
            "customer-1",
            "assistant-1",
            createdAt
        ));
        database.dsl().update(SESSION_RUNTIME_SESSION)
            .set(SESSION_RUNTIME_SESSION.NEXT_MESSAGE_SEQUENCE, 7L)
            .where(SESSION_RUNTIME_SESSION.ID.eq("channel-session-1"))
            .execute();

        repository.updateSessionProjection(new SessionRuntimeStore.SessionRuntimeSessionData(
            "channel-session-1",
            "scenario-updated",
            "updated title",
            "WEB",
            null,
            null,
            "wrong-customer",
            "wrong-assistant",
            "Assistant Updated",
            "2.0.0",
            "ACTIVE",
            "agent-2",
            "agent-2",
            "run-1",
            true,
            true,
            true,
            true,
            Map.of("phase", "updated"),
            99L,
            42L,
            Instant.parse("2026-05-03T01:00:00Z"),
            createdAt.plusSeconds(10),
            createdAt.plusSeconds(20),
            null,
            0L,
            0L
        ));

        var row = database.dsl().selectFrom(SESSION_RUNTIME_SESSION)
            .where(SESSION_RUNTIME_SESSION.ID.eq("channel-session-1"))
            .fetchOne();
        assertEquals("CHANNEL", row.getEntryScope());
        assertEquals("channel-profile-1", row.getChannelProfileId());
        assertEquals("chat-1", row.getExternalConversationId());
        assertEquals("customer-1", row.getCustomerId());
        assertEquals("assistant-1", row.getAssistantId());
        assertEquals(Instant.parse("2026-05-03T00:00:00Z"), row.getCreatedAt().toInstant());
        assertEquals(7L, row.getNextMessageSequence());
        assertEquals("ACTIVE", row.getStatus());
        assertEquals("agent-2", row.getCurrentOwnerAgentId());
        assertEquals(42L, row.getSharedStateRevision());

        assertThrows(IllegalStateException.class, () ->
            repository.updateSessionProjection(storeSession("missing-session", "WEB", null, null, "customer-2", "assistant-2", createdAt))
        );
    }

    @Test
    void createOrReuseTurnRestoresAllocatedIdsBySessionDedupKey() {
        Instant now = Instant.parse("2026-05-03T00:00:00Z");
        repository.createOrReuseActiveSession(storeSession("session-turn-1", "WEB", null, null, "customer-1", "assistant-1", now));

        SessionRuntimeStore.SessionRuntimeTurnData first = repository.createOrReuseTurn(turn(
            "turn-1",
            "session-turn-1",
            "dedup-1",
            List.of(Map.of(
                "requestIndex", 0,
                "messageId", "message-1",
                "clientMessageId", "client-1"
            )),
            List.of("message-1"),
            now
        ));
        SessionRuntimeStore.SessionRuntimeTurnData replay = repository.createOrReuseTurn(turn(
            "turn-reallocated",
            "session-turn-1",
            "dedup-1",
            List.of(Map.of("requestIndex", 0, "messageId", "message-reallocated")),
            List.of("message-reallocated"),
            now.plusSeconds(1)
        ));

        assertEquals("turn-1", first.turnId());
        assertEquals(first.turnId(), replay.turnId());
        assertEquals(List.of("message-1"), replay.acceptedInputMessageIds());
        assertEquals("client-1", replay.inputAllocations().getFirst().get("clientMessageId"));
        assertEquals("turn-1", repository.findTurnByDedupKey("session-turn-1", "dedup-1").orElseThrow().turnId());
    }

    @Test
    void allocatePlatformTurnRestoresSameTurnWithEmptyInputAndMessageIds() {
        Instant now = Instant.parse("2026-05-03T00:00:00Z");
        repository.createOrReuseActiveSession(storeSession("session-platform-turn-1", "WEB", null, null, "customer-1", "assistant-1", now));

        SessionRuntimeStore.SessionRuntimeTurnData first = repository.allocatePlatformTurn(
            "session-platform-turn-1",
            "PLAYBOOK_COMPLETED",
            "session-event-1",
            "session-event-1",
            Map.of("playbookRunId", "run-1")
        );
        SessionRuntimeStore.SessionRuntimeTurnData replay = repository.allocatePlatformTurn(
            "session-platform-turn-1",
            "PLAYBOOK_COMPLETED",
            "session-event-1",
            "session-event-1",
            Map.of("playbookRunId", "run-1", "ignoredReplayField", true)
        );

        assertEquals(first.turnId(), replay.turnId());
        assertEquals("platform:PLAYBOOK_COMPLETED:session-event-1", replay.dedupKey());
        assertEquals("ALLOCATED_IDS", replay.status());
        assertEquals(List.of(), replay.inputAllocations());
        assertEquals(List.of(), replay.acceptedInputMessageIds());
        assertEquals(List.of(), replay.messageIds());
        assertEquals("session-event-1", replay.metadata().get("sourceEventId"));
        assertEquals("session-event-1", replay.metadata().get("platformDedupKey"));
        assertEquals("run-1", replay.metadata().get("playbookRunId"));
    }

    @Test
    void allocatePlatformTurnDoesNotReuseExternalInputTurnWithSameRawDedupKey() {
        Instant now = Instant.parse("2026-05-03T00:00:00Z");
        repository.createOrReuseActiveSession(storeSession("session-platform-turn-2", "WEB", null, null, "customer-1", "assistant-1", now));
        repository.createOrReuseTurn(turn(
            "turn-user-raw-collision",
            "session-platform-turn-2",
            "session-event-1",
            List.of(Map.of(
                "requestIndex", 0,
                "messageId", "message-user-1",
                "clientMessageId", "client-1"
            )),
            List.of("message-user-1"),
            now
        ));

        SessionRuntimeStore.SessionRuntimeTurnData platformTurn = repository.allocatePlatformTurn(
            "session-platform-turn-2",
            "PLAYBOOK_COMPLETED",
            "session-event-1",
            "session-event-1",
            Map.of("playbookRunId", "run-1")
        );

        assertEquals("turn-user-raw-collision", repository.findTurnByDedupKey("session-platform-turn-2", "session-event-1").orElseThrow().turnId());
        assertEquals("platform:PLAYBOOK_COMPLETED:session-event-1", platformTurn.dedupKey());
        assertTrue(!"turn-user-raw-collision".equals(platformTurn.turnId()));
        assertEquals(List.of(), platformTurn.acceptedInputMessageIds());
    }

    @Test
    void allocatePlatformTurnRejectsCanonicalCollisionWithExternalInputTurn() {
        Instant now = Instant.parse("2026-05-03T00:00:00Z");
        repository.createOrReuseActiveSession(storeSession("session-platform-turn-3", "WEB", null, null, "customer-1", "assistant-1", now));
        repository.createOrReuseTurn(turn(
            "turn-user-canonical-collision",
            "session-platform-turn-3",
            "platform:PLAYBOOK_COMPLETED:session-event-1",
            List.of(Map.of(
                "requestIndex", 0,
                "messageId", "message-user-1",
                "clientMessageId", "client-1"
            )),
            List.of("message-user-1"),
            now
        ));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> repository.allocatePlatformTurn(
            "session-platform-turn-3",
            "PLAYBOOK_COMPLETED",
            "session-event-1",
            "session-event-1",
            Map.of("playbookRunId", "run-1")
        ));

        assertEquals(
            "platform turn dedup key collision: triggerType mismatch for platform:PLAYBOOK_COMPLETED:session-event-1",
            error.getMessage()
        );
    }

    @Test
    void appendSessionMessagesAllocatesSequenceTurnIndexAndUpdatesTurnMessageIds() {
        Instant now = Instant.parse("2026-05-03T00:00:00Z");
        repository.createOrReuseActiveSession(storeSession("session-append-1", "WEB", null, null, "customer-1", "assistant-1", now));
        repository.createOrReuseTurn(turn("turn-append-1", "session-append-1", "dedup-append-1", List.of(), List.of(), now));

        assertThrows(IllegalArgumentException.class, () -> repository.appendSessionMessages(
            "session-append-1",
            "missing-turn",
            List.of(appendData(
                "message-requires-existing-turn",
                SessionMessageProducerType.PLATFORM,
                null,
                null,
                SessionMessageRole.SYSTEM,
                new SessionMessageSender(SessionMessageSenderType.SYSTEM, "system", "system"),
                now
            ))
        ));

        List<SessionMessage> firstBatch = repository.appendSessionMessages("session-append-1", "turn-append-1", List.of(
            appendData(
                "message-external-1",
                SessionMessageProducerType.EXTERNAL,
                "external-1",
                "client-1",
                SessionMessageRole.USER,
                new SessionMessageSender(SessionMessageSenderType.CUSTOMER, "customer-1", "customer-1"),
                now
            ),
            appendData(
                "message-platform-1",
                SessionMessageProducerType.PLATFORM,
                null,
                null,
                SessionMessageRole.ASSISTANT,
                new SessionMessageSender(SessionMessageSenderType.AGENT, "agent-1", "agent-1"),
                now.plusSeconds(1)
            )
        ));
        List<SessionMessage> secondBatch = repository.appendSessionMessages("session-append-1", "turn-append-1", List.of(
            appendData(
                "message-platform-2",
                SessionMessageProducerType.PLATFORM,
                null,
                null,
                SessionMessageRole.SYSTEM,
                new SessionMessageSender(SessionMessageSenderType.SYSTEM, "system", "system"),
                now.plusSeconds(2)
            )
        ));
        List<SessionMessage> replay = repository.appendSessionMessages("session-append-1", "turn-append-1", List.of(
            appendData(
                "message-platform-2",
                SessionMessageProducerType.PLATFORM,
                null,
                null,
                SessionMessageRole.SYSTEM,
                new SessionMessageSender(SessionMessageSenderType.SYSTEM, "system", "system"),
                now.plusSeconds(2)
            )
        ));

        assertEquals(List.of(1L, 2L), firstBatch.stream().map(SessionMessage::sequence).toList());
        assertEquals(List.of(0, 1), firstBatch.stream().map(SessionMessage::turnIndex).toList());
        assertEquals(3L, secondBatch.getFirst().sequence());
        assertEquals(2, secondBatch.getFirst().turnIndex());
        assertEquals("message-platform-2", replay.getFirst().messageId());
        assertEquals(3L, database.dsl().select(DSL.max(SESSION_RUNTIME_MESSAGE.SEQUENCE))
            .from(SESSION_RUNTIME_MESSAGE)
            .where(SESSION_RUNTIME_MESSAGE.SESSION_ID.eq("session-append-1"))
            .fetchOne(0, Long.class));
        assertEquals(4L, database.dsl().select(SESSION_RUNTIME_SESSION.NEXT_MESSAGE_SEQUENCE)
            .from(SESSION_RUNTIME_SESSION)
            .where(SESSION_RUNTIME_SESSION.ID.eq("session-append-1"))
            .fetchOne(0, Long.class));

        SessionRuntimeStore.SessionRuntimeTurnData turn = repository.findTurn("turn-append-1").orElseThrow();
        assertEquals("MESSAGES_APPENDED", turn.status());
        assertEquals(List.of("message-external-1", "message-platform-1", "message-platform-2"), turn.messageIds());
    }

    @Test
    void concurrentAppendSessionMessagesSerializesSequenceAndTurnIndexWithSessionRowLock() throws Exception {
        Instant now = Instant.parse("2026-05-03T00:00:00Z");
        repository.createOrReuseActiveSession(storeSession("session-concurrent-append", "WEB", null, null, "customer-1", "assistant-1", now));
        repository.createOrReuseTurn(turn("turn-concurrent-append", "session-concurrent-append", "dedup-concurrent-append", List.of(), List.of(), now));

        int workerCount = 8;
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(workerCount);
        try {
            List<Callable<SessionMessage>> calls = new ArrayList<>();
            for (int index = 0; index < workerCount; index++) {
                int requestIndex = index;
                calls.add(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return repository.appendSessionMessages("session-concurrent-append", "turn-concurrent-append", List.of(
                        appendData(
                            "message-concurrent-" + requestIndex,
                            SessionMessageProducerType.PLATFORM,
                            null,
                            null,
                            SessionMessageRole.ASSISTANT,
                            new SessionMessageSender(SessionMessageSenderType.AGENT, "agent-1", "agent-1"),
                            now.plusMillis(requestIndex)
                        )
                    )).getFirst();
                });
            }
            var futures = calls.stream().map(executor::submit).toList();
            start.countDown();

            Set<Long> sequences = new HashSet<>();
            Set<Integer> turnIndexes = new HashSet<>();
            for (var future : futures) {
                SessionMessage message = future.get(10, TimeUnit.SECONDS);
                sequences.add(message.sequence());
                turnIndexes.add(message.turnIndex());
            }

            assertEquals(Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), sequences);
            assertEquals(Set.of(0, 1, 2, 3, 4, 5, 6, 7), turnIndexes);
            assertEquals(8, repository.findTurn("turn-concurrent-append").orElseThrow().messageIds().size());
        } finally {
            executor.shutdownNow();
        }
    }

    private static SessionRuntimeStore.SessionRuntimeSessionData storeSession(
        String sessionId,
        String entryScope,
        String channelProfileId,
        String externalConversationId,
        String customerId,
        String assistantId,
        Instant now
    ) {
        return new SessionRuntimeStore.SessionRuntimeSessionData(
            sessionId,
            "scenario-1",
            "session " + sessionId,
            entryScope,
            channelProfileId,
            externalConversationId,
            customerId,
            assistantId,
            "Assistant",
            "1.0.0",
            "IDLE",
            "agent-1",
            "agent-1",
            null,
            false,
            false,
            false,
            false,
            Map.of(),
            1L,
            0L,
            null,
            now,
            now,
            null,
            0L,
            0L
        );
    }

    private static SessionRuntimeStore.SessionRuntimeTurnData turn(
        String turnId,
        String sessionId,
        String dedupKey,
        List<Map<String, Object>> inputAllocations,
        List<String> acceptedInputMessageIds,
        Instant now
    ) {
        return new SessionRuntimeStore.SessionRuntimeTurnData(
            turnId,
            sessionId,
            dedupKey,
            "USER_TURN",
            "ALLOCATED_IDS",
            inputAllocations,
            acceptedInputMessageIds,
            List.of(),
            List.of(),
            turnId,
            Map.of(),
            now,
            now,
            null
        );
    }

    private static SessionRuntimeStore.SessionMessageAppendData appendData(
        String messageId,
        SessionMessageProducerType producerType,
        String externalMessageId,
        String clientMessageId,
        SessionMessageRole role,
        SessionMessageSender sender,
        Instant occurredAt
    ) {
        return new SessionRuntimeStore.SessionMessageAppendData(
            messageId,
            producerType,
            externalMessageId,
            clientMessageId,
            occurredAt,
            role,
            sender,
            SessionMessageStatus.SENT,
            List.of(Map.of("type", "TEXT", "text", messageId)),
            Map.of(),
            null,
            role == SessionMessageRole.ASSISTANT ? "agent-1" : null,
            null,
            occurredAt.plusMillis(1),
            occurredAt.plusMillis(1)
        );
    }

    private static SessionRuntimeDtos.SessionRuntimeSessionDto session(String sessionId, String assistantId, Instant now) {
        return new SessionRuntimeDtos.SessionRuntimeSessionDto(
            sessionId,
            "scenario-1",
            "session " + sessionId,
            "customer-" + sessionId,
            assistantId,
            "Assistant",
            "1.0.0",
            "ACTIVE",
            "agent-1",
            "agent-1",
            null,
            false,
            false,
            false,
            false,
            Map.of(),
            null,
            now,
            now,
            null,
            0L,
            0L
        );
    }

    private static SessionMessage message(
        String messageId,
        String sessionId,
        long sequence,
        SessionMessageRole role,
        Instant createdAt
    ) {
        return new SessionMessage(
            messageId,
            sessionId,
            sequence,
            "turn-" + messageId,
            0,
            role == SessionMessageRole.USER ? SessionMessageProducerType.EXTERNAL : SessionMessageProducerType.PLATFORM,
            null,
            null,
            createdAt,
            role,
            new SessionMessageSender(SessionMessageSenderType.AGENT, "agent-1", "agent-1"),
            SessionMessageStatus.SENT,
            List.of(Map.of("type", "TEXT", "text", messageId)),
            Map.of(),
            null,
            "agent-1",
            null,
            createdAt,
            createdAt
        );
    }

    private void insertSnapshot(
        String bindingId,
        String sessionId,
        String channelProfileId,
        String assistantId,
        String externalConversationId,
        Instant now
    ) {
        database.dsl().insertInto(CHANNEL_SESSION_BINDING_SNAPSHOT)
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_ID, bindingId)
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.SESSION_ID, sessionId)
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.CHANNEL_PROFILE_ID, channelProfileId)
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.PROVIDER_TYPE, "provider.acme")
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.EXTERNAL_CONVERSATION_ID, externalConversationId)
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.ASSISTANT_ID, assistantId)
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_STATUS, "ACTIVE")
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.PROFILE_STATUS, "ACTIVE")
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.PROFILE_REVISION, 1L)
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.UPDATED_AT, java.time.OffsetDateTime.parse(now.toString()))
            .execute();
    }

    private void insertSessionRow(
        String sessionId,
        String entryScope,
        String channelProfileId,
        String externalConversationId,
        String customerId,
        String assistantId,
        String status
    ) {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T00:00:00Z");
        database.dsl().insertInto(SESSION_RUNTIME_SESSION)
            .set(SESSION_RUNTIME_SESSION.ID, sessionId)
            .set(SESSION_RUNTIME_SESSION.SCENARIO_ID, "scenario-1")
            .set(SESSION_RUNTIME_SESSION.TITLE, "session " + sessionId)
            .set(SESSION_RUNTIME_SESSION.ENTRY_SCOPE, entryScope)
            .set(SESSION_RUNTIME_SESSION.CHANNEL_PROFILE_ID, channelProfileId)
            .set(SESSION_RUNTIME_SESSION.EXTERNAL_CONVERSATION_ID, externalConversationId)
            .set(SESSION_RUNTIME_SESSION.CUSTOMER_ID, customerId)
            .set(SESSION_RUNTIME_SESSION.ASSISTANT_ID, assistantId)
            .set(SESSION_RUNTIME_SESSION.ASSISTANT_NAME, "Assistant")
            .set(SESSION_RUNTIME_SESSION.ASSISTANT_RELEASE_VERSION, "1.0.0")
            .set(SESSION_RUNTIME_SESSION.STATUS, status)
            .set(SESSION_RUNTIME_SESSION.PRIMARY_AGENT_ID, "agent-1")
            .set(SESSION_RUNTIME_SESSION.CURRENT_OWNER_AGENT_ID, "agent-1")
            .set(SESSION_RUNTIME_SESSION.AGENT_TURN_ACTIVE, false)
            .set(SESSION_RUNTIME_SESSION.SESSION_HUMAN_HANDOFF_ACTIVE, false)
            .set(SESSION_RUNTIME_SESSION.PENDING_OWNER_REEVALUATION, false)
            .set(SESSION_RUNTIME_SESSION.DRAINING, false)
            .set(SESSION_RUNTIME_SESSION.SHARED_STATE, JSONB.valueOf("{}"))
            .set(SESSION_RUNTIME_SESSION.NEXT_MESSAGE_SEQUENCE, 1L)
            .set(SESSION_RUNTIME_SESSION.SHARED_STATE_REVISION, 0L)
            .set(SESSION_RUNTIME_SESSION.CREATED_AT, now)
            .set(SESSION_RUNTIME_SESSION.UPDATED_AT, now)
            .execute();
    }

    private void insertMessageRow(
        String messageId,
        String sessionId,
        long sequence,
        String turnId,
        int turnIndex,
        String externalMessageId
    ) {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T00:00:00Z");
        database.dsl().insertInto(SESSION_RUNTIME_MESSAGE)
            .set(SESSION_RUNTIME_MESSAGE.MESSAGE_ID, messageId)
            .set(SESSION_RUNTIME_MESSAGE.SESSION_ID, sessionId)
            .set(SESSION_RUNTIME_MESSAGE.SEQUENCE, sequence)
            .set(SESSION_RUNTIME_MESSAGE.TURN_ID, turnId)
            .set(SESSION_RUNTIME_MESSAGE.TURN_INDEX, turnIndex)
            .set(SESSION_RUNTIME_MESSAGE.PRODUCER_TYPE, SessionMessageProducerType.EXTERNAL.name())
            .set(SESSION_RUNTIME_MESSAGE.EXTERNAL_MESSAGE_ID, externalMessageId)
            .set(SESSION_RUNTIME_MESSAGE.OCCURRED_AT, now)
            .set(SESSION_RUNTIME_MESSAGE.ROLE, SessionMessageRole.USER.name())
            .set(SESSION_RUNTIME_MESSAGE.SENDER_TYPE, SessionMessageSenderType.CUSTOMER.name())
            .set(SESSION_RUNTIME_MESSAGE.SENDER_ID, "customer-1")
            .set(SESSION_RUNTIME_MESSAGE.SENDER_NAME, "customer-1")
            .set(SESSION_RUNTIME_MESSAGE.STATUS, SessionMessageStatus.SENT.name())
            .set(SESSION_RUNTIME_MESSAGE.BLOCKS, JSONB.valueOf("[{\"type\":\"TEXT\",\"text\":\"hello\"}]"))
            .set(SESSION_RUNTIME_MESSAGE.METADATA, JSONB.valueOf("{}"))
            .set(SESSION_RUNTIME_MESSAGE.CREATED_AT, now)
            .set(SESSION_RUNTIME_MESSAGE.UPDATED_AT, now)
            .execute();
    }
}
