package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import java.util.List;
import java.util.Optional;

import static com.lynxus.platform.session.SessionRuntimeDtos.*;

public interface SessionRuntimeRepository {
    List<SessionRuntimeSessionDto> listSessions();

    Optional<SessionRuntimeSessionDto> findSession(String sessionId);

    Optional<SessionRuntimeSessionDto> findActiveSession(String customerId, String assistantId);

    Optional<SessionRuntimeChangeStamp> findSessionChangeStamp(String sessionId);

    void saveSession(SessionRuntimeSessionDto session);

    List<SessionEvent> listEvents(String sessionId);

    long nextEventSequence(String sessionId);

    void appendEvent(SessionEvent event);

    List<PlaybookRun> listPlaybookRuns(String sessionId);

    void savePlaybookRun(PlaybookRun playbookRun);

    record SessionRuntimeChangeStamp(
        String sessionId,
        java.time.Instant sessionUpdatedAt,
        long latestEventSequence,
        java.time.Instant latestPlaybookRunUpdatedAt
    ) {
        public String fingerprint() {
            long sessionMillis = sessionUpdatedAt == null ? 0L : sessionUpdatedAt.toEpochMilli();
            long playbookMillis = latestPlaybookRunUpdatedAt == null ? 0L : latestPlaybookRunUpdatedAt.toEpochMilli();
            return sessionId + ":" + sessionMillis + ":" + latestEventSequence + ":" + playbookMillis;
        }
    }
}
