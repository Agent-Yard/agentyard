package com.lynxus.worker.session;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionPersistenceActivitiesImplTest {
    @Test
    void shouldPublishRuntimeChangeNoticeAfterProjectionWrites() {
        JooqSessionProjectionRepository repository = mock(JooqSessionProjectionRepository.class);
        SessionRuntimeChangePublisher publisher = mock(SessionRuntimeChangePublisher.class);
        SessionPersistenceActivitiesImpl activities = new SessionPersistenceActivitiesImpl(repository, publisher);
        SessionPersistenceActivities.SessionRecord session = new SessionPersistenceActivities.SessionRecord(
            "session-1",
            "scenario-1",
            "Session",
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
            Map.of(),
            null,
            Instant.parse("2026-04-21T00:00:00Z"),
            Instant.parse("2026-04-21T00:00:00Z"),
            null
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
            Instant.parse("2026-04-21T00:00:01Z"),
            SessionActorType.AGENT,
            "agent-1",
            Map.of(),
            null,
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
            Map.of(),
            Map.of(),
            null,
            Instant.parse("2026-04-21T00:00:02Z"),
            Instant.parse("2026-04-21T00:00:02Z"),
            null
        );

        activities.saveSession(session);
        activities.appendMessage(message);
        activities.appendEvent(event);
        activities.appendLlmUsage(List.of(new SessionPersistenceActivities.LlmUsageRecord(
            "usage-1",
            "SESSION_OWNER_MODEL",
            "session-1",
            "event-1",
            "USER_MESSAGE",
            null,
            "scenario-1",
            "customer-1",
            "assistant-1",
            "1.0.0",
            "agent-1",
            "OPENAI_COMPATIBLE",
            "model-1",
            "model-ver-1",
            "gpt-test",
            true,
            10,
            5,
            15,
            Map.of("prompt_tokens", 10),
            1,
            0,
            Instant.parse("2026-04-21T00:00:01Z")
        )));
        activities.savePlaybookRun(playbookRun);

        verify(publisher, times(4)).publishSessionChanged("session-1");
        verify(repository).saveSession(session);
        verify(repository).appendMessage(message);
        verify(repository).appendEvent(event);
        verify(repository).appendLlmUsage(List.of(new SessionPersistenceActivities.LlmUsageRecord(
            "usage-1",
            "SESSION_OWNER_MODEL",
            "session-1",
            "event-1",
            "USER_MESSAGE",
            null,
            "scenario-1",
            "customer-1",
            "assistant-1",
            "1.0.0",
            "agent-1",
            "OPENAI_COMPATIBLE",
            "model-1",
            "model-ver-1",
            "gpt-test",
            true,
            10,
            5,
            15,
            Map.of("prompt_tokens", 10),
            1,
            0,
            Instant.parse("2026-04-21T00:00:01Z")
        )));
        verify(repository).savePlaybookRun(playbookRun);
    }
}
