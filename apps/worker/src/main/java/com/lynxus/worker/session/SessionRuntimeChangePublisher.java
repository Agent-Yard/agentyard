package com.lynxus.worker.session;

import com.lynxus.contracts.session.SessionRuntimeChangeNotice;
import com.lynxus.worker.shared.redis.WorkerSharedStateProperties;
import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import com.lynxus.shared.redis.RedisPubSubBus;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class SessionRuntimeChangePublisher {
    private final JdbcSessionProjectionRepository repository;
    private final RedisPubSubBus pubSubBus;
    private final RedisKeyspace keyspace;
    private final RedisJsonCodec codec;
    private final WorkerSharedStateProperties properties;

    public SessionRuntimeChangePublisher(
        JdbcSessionProjectionRepository repository,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec,
        WorkerSharedStateProperties properties
    ) {
        this.repository = repository;
        this.pubSubBus = pubSubBus;
        this.keyspace = keyspace;
        this.codec = codec;
        this.properties = properties;
    }

    public void publishSessionChanged(String sessionId) {
        repository.findSessionChangeStamp(sessionId).ifPresent(this::publish);
    }

    private void publish(JdbcSessionProjectionRepository.SessionRuntimeChangeStamp stamp) {
        SessionRuntimeChangeNotice notice = new SessionRuntimeChangeNotice(
            stamp.sessionId(),
            stamp.fingerprint(),
            Instant.now(),
            properties.instanceId()
        );
        pubSubBus.publish(keyspace.sseChannelSessionChanged(), codec.write(notice));
    }
}
