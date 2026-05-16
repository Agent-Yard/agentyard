package com.lynxus.worker.session;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import com.lynxus.contracts.session.SessionContracts.SessionMessage;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SessionPersistenceActivitiesImpl implements SessionPersistenceActivities {
    private final JooqSessionProjectionRepository repository;
    private final SessionRuntimeChangePublisher changePublisher;

    public SessionPersistenceActivitiesImpl(
        JooqSessionProjectionRepository repository,
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
    public PlatformTurnAllocation allocatePlatformTurn(
        String sessionId,
        String triggerType,
        String dedupKey,
        String sourceEventId,
        java.util.Map<String, Object> metadata
    ) {
        var turn = repository.allocatePlatformTurn(sessionId, triggerType, dedupKey, sourceEventId, metadata);
        String persistedSourceEventId = stringValue(turn.metadata().get("sourceEventId"));
        changePublisher.publishSessionChanged(sessionId);
        return new PlatformTurnAllocation(
            turn.turnId(),
            turn.sessionId(),
            turn.triggerType(),
            turn.dedupKey(),
            persistedSourceEventId,
            turn.metadata()
        );
    }

    @Override
    public List<SessionMessage> appendSessionMessages(
        String sessionId,
        String turnId,
        List<SessionMessageAppendRecord> messages
    ) {
        List<SessionMessage> appended = repository.appendSessionMessages(sessionId, turnId, messages);
        changePublisher.publishSessionChanged(sessionId);
        return appended;
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
    public void appendLlmUsage(List<LlmUsageRecord> records) {
        repository.appendLlmUsage(records);
    }

    @Override
    public void savePlaybookRun(PlaybookRun playbookRun) {
        repository.savePlaybookRun(playbookRun);
        changePublisher.publishSessionChanged(playbookRun.sessionId());
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
