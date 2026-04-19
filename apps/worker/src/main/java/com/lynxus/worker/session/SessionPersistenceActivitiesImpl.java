package com.lynxus.worker.session;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import org.springframework.stereotype.Component;

@Component
public class SessionPersistenceActivitiesImpl implements SessionPersistenceActivities {
    private final JdbcSessionProjectionRepository repository;

    public SessionPersistenceActivitiesImpl(JdbcSessionProjectionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void saveSession(SessionRecord session) {
        repository.saveSession(session);
    }

    @Override
    public void appendEvent(SessionEvent event) {
        repository.appendEvent(event);
    }

    @Override
    public void savePlaybookRun(PlaybookRun playbookRun) {
        repository.savePlaybookRun(playbookRun);
    }
}
