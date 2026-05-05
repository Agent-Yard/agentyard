package com.lynxus.platform.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.PlaybookRunStatus;
import com.lynxus.contracts.session.SessionContracts.SessionActorType;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import com.lynxus.contracts.session.SessionContracts.SessionEventType;
import com.lynxus.contracts.session.SessionContracts.SessionMessage;
import com.lynxus.contracts.session.SessionContracts.SessionMessageRole;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSender;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSenderType;
import com.lynxus.contracts.session.SessionContracts.SessionMessageStatus;
import com.lynxus.platform.testing.EmbeddedPostgresTestDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static com.lynxus.persistence.jooq.Tables.CHANNEL_SESSION_BINDING_SNAPSHOT;

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
}
