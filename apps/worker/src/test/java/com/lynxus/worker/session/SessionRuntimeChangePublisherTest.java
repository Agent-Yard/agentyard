package com.lynxus.worker.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.contracts.session.SessionRuntimeChangeNotice;
import com.lynxus.worker.shared.redis.WorkerSharedStateProperties;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SessionRuntimeChangePublisherTest {
    @Test
    void shouldPublishSessionRuntimeNoticeViaRedisPubSubBus() {
        JdbcSessionProjectionRepository repository = mock(JdbcSessionProjectionRepository.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        RedisJsonCodec codec = new RedisJsonCodec(new ObjectMapper());
        SessionRuntimeChangePublisher publisher = new SessionRuntimeChangePublisher(
            repository,
            pubSubBus,
            new RedisKeyspace(),
            codec,
            new WorkerSharedStateProperties("worker-a")
        );
        JdbcSessionProjectionRepository.SessionRuntimeChangeStamp stamp =
            new JdbcSessionProjectionRepository.SessionRuntimeChangeStamp(
                "session-1",
                Instant.parse("2026-04-21T00:00:00Z"),
                3L,
                Instant.parse("2026-04-21T00:00:01Z")
            );
        when(repository.findSessionChangeStamp("session-1")).thenReturn(Optional.of(stamp));

        publisher.publishSessionChanged("session-1");

        verify(pubSubBus).publish(eq("lynxus:sse:channel:session-changed"), org.mockito.ArgumentMatchers.argThat(message -> {
            SessionRuntimeChangeNotice notice = codec.read(message, SessionRuntimeChangeNotice.class);
            assertEquals("session-1", notice.sessionId());
            assertEquals(stamp.fingerprint(), notice.fingerprint());
            assertEquals("worker-a", notice.sourceInstanceId());
            return true;
        }));
    }
}
