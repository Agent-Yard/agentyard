package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import com.lynxus.contracts.session.SessionContracts.SessionMessage;
import com.lynxus.persistence.jooqsupport.JooqJsonbSupport;
import com.lynxus.persistence.session.SessionRuntimeStore;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import static com.lynxus.platform.session.SessionRuntimeDtos.*;

@Repository
public class JooqSessionRuntimeRepository implements SessionRuntimeRepository {
    private final SessionRuntimeStore store;

    public JooqSessionRuntimeRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.store = new SessionRuntimeStore(dsl, new JooqJsonbSupport(objectMapper));
    }

    @Override
    public List<SessionRuntimeSessionDto> listSessions() {
        return store.listSessions().stream().map(this::toDto).toList();
    }

    @Override
    public Optional<SessionRuntimeSessionDto> findSession(String sessionId) {
        return store.findSession(sessionId).map(this::toDto);
    }

    @Override
    public Optional<SessionRuntimeSessionDto> findActiveSession(String customerId, String assistantId) {
        return store.findActiveSession(customerId, assistantId).map(this::toDto);
    }

    @Override
    public Optional<SessionRuntimeChangeStamp> findSessionChangeStamp(String sessionId) {
        return store.findSessionChangeStamp(sessionId)
            .map(item -> new SessionRuntimeChangeStamp(
                item.sessionId(),
                item.sessionUpdatedAt(),
                item.latestMessageSequence(),
                item.latestEventSequence(),
                item.latestPlaybookRunUpdatedAt()
            ));
    }

    @Override
    public void saveSession(SessionRuntimeSessionDto session) {
        store.saveSession(new SessionRuntimeStore.SessionRuntimeSessionData(
            session.id(),
            session.scenarioId(),
            session.title(),
            session.customerId(),
            session.assistantId(),
            session.assistantName(),
            session.assistantReleaseVersion(),
            session.status(),
            session.primaryAgentId(),
            session.currentOwnerAgentId(),
            session.activePlaybookRunId(),
            session.agentTurnActive(),
            session.sessionHumanHandoffActive(),
            session.pendingOwnerReevaluation(),
            session.draining(),
            session.sharedState(),
            session.idleDeadline(),
            session.createdAt(),
            session.updatedAt(),
            session.endedAt(),
            session.latestMessageSequence(),
            session.latestEventSequence()
        ));
    }

    @Override
    public List<SessionMessage> listMessages(String sessionId) {
        return store.listMessages(sessionId);
    }

    @Override
    public List<SessionRuntimeStore.ChannelOutboundFinalMessageData> listChannelOutboundFinalMessages(
        String channelProfileId,
        long afterFinalSequence,
        int limit
    ) {
        return store.listChannelOutboundFinalMessages(channelProfileId, afterFinalSequence, limit);
    }

    @Override
    public List<SessionEvent> listEvents(String sessionId) {
        return store.listEvents(sessionId);
    }

    @Override
    public long nextMessageSequence(String sessionId) {
        return store.nextMessageSequence(sessionId);
    }

    @Override
    public long nextEventSequence(String sessionId) {
        return store.nextEventSequence(sessionId);
    }

    @Override
    public void appendMessage(SessionMessage message) {
        store.appendMessage(message);
    }

    @Override
    public void appendEvent(SessionEvent event) {
        store.appendEvent(event);
    }

    @Override
    public List<PlaybookRun> listPlaybookRuns(String sessionId) {
        return store.listPlaybookRuns(sessionId);
    }

    @Override
    public void savePlaybookRun(PlaybookRun playbookRun) {
        store.savePlaybookRun(playbookRun);
    }

    private SessionRuntimeSessionDto toDto(SessionRuntimeStore.SessionRuntimeSessionData session) {
        return new SessionRuntimeSessionDto(
            session.id(),
            session.scenarioId(),
            session.title(),
            session.customerId(),
            session.assistantId(),
            session.assistantName(),
            session.assistantReleaseVersion(),
            session.status(),
            session.primaryAgentId(),
            session.currentOwnerAgentId(),
            session.activePlaybookRunId(),
            session.agentTurnActive(),
            session.sessionHumanHandoffActive(),
            session.pendingOwnerReevaluation(),
            session.draining(),
            session.sharedState(),
            session.idleDeadline(),
            session.createdAt(),
            session.updatedAt(),
            session.endedAt(),
            session.latestMessageSequence(),
            session.latestEventSequence()
        );
    }
}
