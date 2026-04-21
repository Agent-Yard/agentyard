package com.lynxus.worker.session;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.PlaybookRunStatus;
import com.lynxus.contracts.session.SessionContracts.SessionActorType;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import com.lynxus.contracts.session.SessionContracts.SessionEventType;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionPersistenceActivitiesImplTest {
    @Test
    void shouldPublishRuntimeChangeNoticeAfterProjectionWrites() {
        JdbcSessionProjectionRepository repository = mock(JdbcSessionProjectionRepository.class);
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
        SessionEvent event = new SessionEvent(
            "event-1",
            "session-1",
            1L,
            SessionEventType.USER_MESSAGE,
            Instant.parse("2026-04-21T00:00:01Z"),
            SessionActorType.USER,
            "customer-1",
            Map.of(),
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
        activities.appendEvent(event);
        activities.savePlaybookRun(playbookRun);

        verify(publisher, times(3)).publishSessionChanged("session-1");
        verify(repository).saveSession(session);
        verify(repository).appendEvent(event);
        verify(repository).savePlaybookRun(playbookRun);
    }
}
