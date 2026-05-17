package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import com.lynxus.contracts.session.SessionContracts.SessionMessage;
import com.lynxus.persistence.session.SessionRuntimeStore;
import java.util.List;
import java.util.Optional;

import static com.lynxus.platform.session.SessionRuntimeDtos.*;

public interface SessionRuntimeRepository {
    List<SessionRuntimeSessionDto> listSessions();

    Optional<SessionRuntimeSessionDto> findSession(String sessionId);

    Optional<SessionRuntimeSessionDto> findActiveSession(String customerId, String assistantId);

    Optional<SessionRuntimeSessionDto> findActiveChannelSession(
        String channelProfileId,
        String externalConversationId,
        String customerId,
        String assistantId
    );

    SessionRuntimeStore.SessionRuntimeSessionData createOrReuseActiveSession(
        SessionRuntimeStore.SessionRuntimeSessionData session
    );

    Optional<SessionRuntimeChangeStamp> findSessionChangeStamp(String sessionId);

    void saveSession(SessionRuntimeSessionDto session);

    void updateSessionProjection(SessionRuntimeStore.SessionRuntimeSessionData session);

    Optional<SessionRuntimeStore.SessionRuntimeTurnData> findTurn(String turnId);

    Optional<SessionRuntimeStore.SessionRuntimeTurnData> findTurnByDedupKey(String sessionId, String dedupKey);

    SessionRuntimeStore.SessionRuntimeTurnData createOrReuseTurn(SessionRuntimeStore.SessionRuntimeTurnData turn);

    SessionRuntimeStore.SessionRuntimeTurnData allocatePlatformTurn(
        String sessionId,
        String triggerType,
        String dedupKey,
        String sourceEventId,
        java.util.Map<String, Object> metadata
    );

    List<SessionMessage> listMessages(String sessionId);

    List<SessionMessage> listMessagesForTurn(String sessionId, String turnId);

    SessionRuntimeStore.SessionRuntimeTurnData updateTurnState(
        String sessionId,
        String turnId,
        String status,
        List<String> acceptedInputMessageIds,
        List<String> duplicateExternalMessageIds,
        List<String> messageIds,
        String temporalUpdateId,
        java.time.Instant completedAt
    );

    List<SessionRuntimeStore.ChannelOutboundFinalMessageData> listChannelOutboundFinalMessages(
        String channelProfileId,
        long afterFinalSequence,
        int limit
    );

    List<SessionEvent> listEvents(String sessionId);

    long nextMessageSequence(String sessionId);

    long nextEventSequence(String sessionId);

    List<SessionMessage> appendSessionMessages(
        String sessionId,
        String turnId,
        List<SessionRuntimeStore.SessionMessageAppendData> messages
    );

    void appendEvent(SessionEvent event);

    List<PlaybookRun> listPlaybookRuns(String sessionId);

    void savePlaybookRun(PlaybookRun playbookRun);

    record SessionRuntimeChangeStamp(
        String sessionId,
        java.time.Instant sessionUpdatedAt,
        long latestMessageSequence,
        long latestEventSequence,
        java.time.Instant latestPlaybookRunUpdatedAt
    ) {
        public String fingerprint() {
            long sessionMillis = sessionUpdatedAt == null ? 0L : sessionUpdatedAt.toEpochMilli();
            long playbookMillis = latestPlaybookRunUpdatedAt == null ? 0L : latestPlaybookRunUpdatedAt.toEpochMilli();
            return sessionId + ":" + sessionMillis + ":" + latestMessageSequence + ":" + latestEventSequence + ":" + playbookMillis;
        }
    }
}
