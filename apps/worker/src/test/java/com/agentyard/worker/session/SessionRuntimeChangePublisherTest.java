package com.agentyard.worker.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentyard.contracts.session.SessionRuntimeChangeNotice;
import com.agentyard.worker.shared.redis.WorkerSharedStateProperties;
import com.agentyard.shared.redis.RedisJsonCodec;
import com.agentyard.shared.redis.RedisKeyspace;
import com.agentyard.shared.redis.RedisPubSubBus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SessionRuntimeChangePublisherTest {
    @Test
    void shouldPublishSessionRuntimeNoticeViaRedisPubSubBus() {
        JooqSessionProjectionRepository repository = mock(JooqSessionProjectionRepository.class);
        RedisPubSubBus pubSubBus = mock(RedisPubSubBus.class);
        RedisJsonCodec codec = new RedisJsonCodec(new ObjectMapper());
        SessionRuntimeChangePublisher publisher = new SessionRuntimeChangePublisher(
            repository,
            pubSubBus,
            new RedisKeyspace(),
            codec,
            new WorkerSharedStateProperties("worker-a")
        );
        JooqSessionProjectionRepository.SessionRuntimeChangeStamp stamp =
            new JooqSessionProjectionRepository.SessionRuntimeChangeStamp(
                "session-1",
                Instant.parse("2026-04-21T00:00:00Z"),
                2L,
                3L,
                Instant.parse("2026-04-21T00:00:01Z")
            );
        when(repository.findSessionChangeStamp("session-1")).thenReturn(Optional.of(stamp));

        publisher.publishSessionChanged("session-1");

        verify(pubSubBus).publish(eq("agentyard:sse:channel:session-changed"), org.mockito.ArgumentMatchers.argThat(message -> {
            SessionRuntimeChangeNotice notice = codec.read(message, SessionRuntimeChangeNotice.class);
            assertEquals("session-1", notice.sessionId());
            assertEquals(stamp.fingerprint(), notice.fingerprint());
            assertEquals("worker-a", notice.sourceInstanceId());
            return true;
        }));
    }
}
