package com.lynxus.worker.session;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import org.springframework.stereotype.Component;

@Component
public class SessionPersistenceActivitiesImpl implements SessionPersistenceActivities {
    private final JdbcSessionProjectionRepository repository;
    private final SessionRuntimeChangePublisher changePublisher;

    public SessionPersistenceActivitiesImpl(
        JdbcSessionProjectionRepository repository,
        SessionRuntimeChangePublisher changePublisher
    ) {
        this.repository = repository;
        this.changePublisher = changePublisher;
    }

    @Override
    public void saveSession(SessionRecord session) {
        repository.saveSession(session);
        changePublisher.publishSessionChanged(session.id());
    }

    @Override
    public void appendEvent(SessionEvent event) {
        repository.appendEvent(event);
        changePublisher.publishSessionChanged(event.sessionId());
    }

    @Override
    public void appendPlatformEvent(PlatformEventRecord event) {
        repository.appendPlatformEvent(event);
    }

    @Override
    public void savePlaybookRun(PlaybookRun playbookRun) {
        repository.savePlaybookRun(playbookRun);
        changePublisher.publishSessionChanged(playbookRun.sessionId());
    }
}
