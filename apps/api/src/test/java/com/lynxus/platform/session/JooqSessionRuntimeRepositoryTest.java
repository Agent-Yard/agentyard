package com.lynxus.platform.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.PlaybookRunStatus;
import com.lynxus.contracts.session.SessionContracts.SessionActorType;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import com.lynxus.contracts.session.SessionContracts.SessionEventType;
import com.lynxus.platform.testing.EmbeddedPostgresTestDatabase;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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
            0L
        );
        SessionEvent event = new SessionEvent(
            "event-1",
            "session-1",
            1L,
            SessionEventType.USER_MESSAGE,
            Instant.parse("2026-04-21T00:00:01Z"),
            SessionActorType.USER,
            "customer-1",
            Map.of("text", "退款"),
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
        repository.appendEvent(event);
        repository.savePlaybookRun(playbookRun);

        assertEquals(1, repository.listSessions().size());
        assertEquals(1L, repository.nextEventSequence("session-unknown"));
        assertEquals(2L, repository.nextEventSequence("session-1"));
        assertEquals("session-1", repository.findActiveSession("customer-1", "assistant-1").orElseThrow().id());
        assertEquals("event-1", repository.listEvents("session-1").getFirst().eventId());
        assertEquals("run-1", repository.listPlaybookRuns("session-1").getFirst().runId());

        SessionRuntimeRepository.SessionRuntimeChangeStamp stamp = repository.findSessionChangeStamp("session-1").orElseThrow();
        assertEquals(1L, stamp.latestEventSequence());
        assertTrue(stamp.fingerprint().startsWith("session-1:"));
    }
}
