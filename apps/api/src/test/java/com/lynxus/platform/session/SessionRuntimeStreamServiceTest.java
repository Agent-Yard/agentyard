package com.lynxus.platform.session;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.contracts.session.SessionRuntimeChangeNotice;
import com.lynxus.platform.session.SessionRuntimeDtos.SessionRuntimeDetailDto;
import com.lynxus.platform.session.SessionRuntimeDtos.SessionRuntimeSessionDto;
import com.lynxus.platform.shared.redis.RedisSharedStateProperties;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SessionRuntimeStreamServiceTest {
    @Test
    void shouldPublishSessionUpdatedEventWhenObservedFingerprintChanges() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        RedisKeyspace keyspace = new RedisKeyspace();
        SessionRuntimeStreamService service = new SessionRuntimeStreamService(
            repository,
            replayStore,
            pubSubBus,
            keyspace,
            new RedisJsonCodec(new ObjectMapper()),
            new RedisSharedStateProperties("instance-a", Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofHours(24), Duration.ofMinutes(15), 128, Duration.ofSeconds(1))
        );
        SessionRuntimeSessionDto session = session("session-1", Instant.parse("2026-04-21T00:00:00Z"), 1L);
        SessionRuntimeRepository.SessionRuntimeChangeStamp initial = new SessionRuntimeRepository.SessionRuntimeChangeStamp(
            "session-1",
            session.updatedAt(),
            session.latestMessageSequence(),
            session.latestEventSequence(),
            null
        );
        SessionRuntimeRepository.SessionRuntimeChangeStamp updated = new SessionRuntimeRepository.SessionRuntimeChangeStamp(
            "session-1",
            session.updatedAt().plusSeconds(5),
            session.latestMessageSequence() + 1,
            session.latestEventSequence() + 1,
            null
        );

        when(replayStore.replayAfter("session-1", null)).thenReturn(new SessionRuntimeStreamDtos.SessionRuntimeStreamReplayResult(false, List.of()));
        when(repository.findSession("session-1")).thenReturn(Optional.of(session));
        when(repository.listMessages("session-1")).thenReturn(List.of());
        when(repository.listEvents("session-1")).thenReturn(List.of());
        when(repository.listPlaybookRuns("session-1")).thenReturn(List.of());
        when(repository.findSessionChangeStamp("session-1")).thenReturn(Optional.of(initial), Optional.of(updated));
        when(replayStore.append(any())).thenReturn(true);

        service.connect("session-1", null, "tester");
        service.pollSubscribedSessions();

        verify(replayStore).append(argThat(event ->
            "SESSION_SNAPSHOT".equals(event.type()) && "session-snapshot:session-1:1:1".equals(event.id())
        ));
        verify(replayStore).append(argThat(event -> "SESSION_UPDATED".equals(event.type())));
        verify(pubSubBus).publish(eq(keyspace.sseChannelSessionUpdated()), any());
    }

    @Test
    void shouldMaterializeRuntimeChangeNoticeWhenSessionHasLocalSubscribers() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        RedisKeyspace keyspace = new RedisKeyspace();
        SessionRuntimeStreamService service = new SessionRuntimeStreamService(
            repository,
            replayStore,
            pubSubBus,
            keyspace,
            new RedisJsonCodec(new ObjectMapper()),
            new RedisSharedStateProperties("instance-a", Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofHours(24), Duration.ofMinutes(15), 128, Duration.ofSeconds(1))
        );
        SessionRuntimeSessionDto session = session("session-1", Instant.parse("2026-04-21T00:00:00Z"), 1L);
        String initialFingerprint = "session-1:0:0:0:0";
        String updatedFingerprint = "session-1:1713657605000:2:2:0";

        when(replayStore.replayAfter("session-1", null)).thenReturn(new SessionRuntimeStreamDtos.SessionRuntimeStreamReplayResult(false, List.of()));
        when(repository.findSession("session-1")).thenReturn(Optional.of(session));
        when(repository.listMessages("session-1")).thenReturn(List.of());
        when(repository.listEvents("session-1")).thenReturn(List.of());
        when(repository.listPlaybookRuns("session-1")).thenReturn(List.of());
        when(repository.findSessionChangeStamp("session-1")).thenReturn(Optional.of(
            new SessionRuntimeRepository.SessionRuntimeChangeStamp("session-1", Instant.ofEpochMilli(0), 0L, 0L, null)
        ));
        when(replayStore.append(any())).thenReturn(true);

        service.connect("session-1", null, "tester");
        service.handleRuntimeChangeNotice(new SessionRuntimeChangeNotice("session-1", updatedFingerprint, Instant.now(), "worker-1"));

        verify(pubSubBus).publish(eq(keyspace.sseChannelSessionUpdated()), argThat(payload -> payload.contains(updatedFingerprint)));
    }

    @Test
    void shouldMaterializeRuntimeChangeNoticeIntoReplayWithoutLocalSubscribers() {
        SessionRuntimeRepository repository = mock(SessionRuntimeRepository.class);
        SessionRuntimeReplayStore replayStore = mock(SessionRuntimeReplayStore.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        RedisKeyspace keyspace = new RedisKeyspace();
        SessionRuntimeStreamService service = new SessionRuntimeStreamService(
            repository,
            replayStore,
            pubSubBus,
            keyspace,
            new RedisJsonCodec(new ObjectMapper()),
            new RedisSharedStateProperties("instance-a", Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofHours(24), Duration.ofMinutes(15), 128, Duration.ofSeconds(1))
        );
        SessionRuntimeSessionDto session = session("session-1", Instant.parse("2026-04-21T00:00:00Z"), 2L);
        String updatedFingerprint = "session-1:1713657605000:2:2:0";

        when(repository.findSession("session-1")).thenReturn(Optional.of(session));
        when(repository.listMessages("session-1")).thenReturn(List.of());
        when(repository.listEvents("session-1")).thenReturn(List.of());
        when(repository.listPlaybookRuns("session-1")).thenReturn(List.of());
        when(replayStore.append(any())).thenReturn(true);

        service.handleRuntimeChangeNotice(new SessionRuntimeChangeNotice("session-1", updatedFingerprint, Instant.now(), "worker-1"));

        verify(replayStore).append(any());
        verify(pubSubBus).publish(eq(keyspace.sseChannelSessionUpdated()), argThat(payload -> payload.contains(updatedFingerprint)));
    }

    private static SessionRuntimeSessionDto session(String sessionId, Instant updatedAt, long latestSequence) {
        return new SessionRuntimeSessionDto(
            sessionId,
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
            updatedAt.minusSeconds(5),
            updatedAt,
            null,
            latestSequence,
            latestSequence
        );
    }
}
