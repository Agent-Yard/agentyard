package com.agentyard.platform.session;

import com.agentyard.contracts.session.SessionRuntimeChangeNotice;
import com.agentyard.platform.shared.redis.RedisSharedStateProperties;
import com.agentyard.shared.redis.RedisJsonCodec;
import com.agentyard.shared.redis.RedisKeyspace;
import com.agentyard.shared.redis.RedisPubSubBus;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class SessionRuntimeChangeNoticePublisher {
    private final SessionRuntimeRepository repository;
    private final RedisPubSubBus pubSubBus;
    private final RedisKeyspace keyspace;
    private final RedisJsonCodec codec;
    private final RedisSharedStateProperties properties;

    public SessionRuntimeChangeNoticePublisher(
        SessionRuntimeRepository repository,
        RedisPubSubBus pubSubBus,
        RedisKeyspace keyspace,
        RedisJsonCodec codec,
        RedisSharedStateProperties properties
    ) {
        this.repository = repository;
        this.pubSubBus = pubSubBus;
        this.keyspace = keyspace;
        this.codec = codec;
        this.properties = properties;
    }

    public void publishSessionChanged(String sessionId) {
        repository.findSessionChangeStamp(sessionId).ifPresent(stamp -> pubSubBus.publish(
            keyspace.sseChannelSessionChanged(),
            codec.write(new SessionRuntimeChangeNotice(
                stamp.sessionId(),
                stamp.fingerprint(),
                Instant.now(),
                properties.instanceId()
            ))
        ));
    }
}
