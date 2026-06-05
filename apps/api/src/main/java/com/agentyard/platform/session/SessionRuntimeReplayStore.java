package com.agentyard.platform.session;

import com.agentyard.platform.session.SessionRuntimeStreamDtos.SessionRuntimeStreamEvent;
import com.agentyard.platform.session.SessionRuntimeStreamDtos.SessionRuntimeStreamReplayResult;
import com.agentyard.platform.shared.redis.RedisSharedStateProperties;
import com.agentyard.shared.redis.RedisJsonCodec;
import com.agentyard.shared.redis.RedisKeyspace;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SessionRuntimeReplayStore {
    private final StringRedisTemplate redisTemplate;
    private final RedisJsonCodec codec;
    private final RedisKeyspace keyspace;
    private final RedisSharedStateProperties properties;
    private final Counter replayAppendCounter;
    private final Counter replayDedupCounter;
    private final Counter replayMatchedCounter;
    private final Counter replayMissCounter;

    @Autowired
    public SessionRuntimeReplayStore(
        StringRedisTemplate redisTemplate,
        RedisJsonCodec codec,
        RedisKeyspace keyspace,
        RedisSharedStateProperties properties,
        MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.codec = codec;
        this.keyspace = keyspace;
        this.properties = properties;
        this.replayAppendCounter = Counter.builder("agentyard.shared_state.sse.replay.appended")
            .description("Number of session runtime replay events appended")
            .register(meterRegistry);
        this.replayDedupCounter = Counter.builder("agentyard.shared_state.sse.replay.deduped")
            .description("Number of duplicate session runtime replay appends skipped")
            .register(meterRegistry);
        this.replayMatchedCounter = Counter.builder("agentyard.shared_state.sse.replay.matched")
            .description("Number of successful Last-Event-ID replay matches")
            .register(meterRegistry);
        this.replayMissCounter = Counter.builder("agentyard.shared_state.sse.replay.missed")
            .description("Number of replay misses that require snapshot fallback")
            .register(meterRegistry);
    }

    SessionRuntimeReplayStore(
        StringRedisTemplate redisTemplate,
        RedisJsonCodec codec,
        RedisKeyspace keyspace,
        RedisSharedStateProperties properties
    ) {
        this(redisTemplate, codec, keyspace, properties, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    public boolean append(SessionRuntimeStreamEvent event) {
        String dedupKey = keyspace.sseEventDedup(event.sessionId(), event.id());
        Duration ttl = properties.sseReplayTtl();
        Boolean fresh = redisTemplate.opsForValue().setIfAbsent(dedupKey, event.id(), ttl);
        if (!Boolean.TRUE.equals(fresh)) {
            replayDedupCounter.increment();
            return false;
        }
        String eventKey = keyspace.sseEvents(event.sessionId());
        redisTemplate.opsForList().rightPush(eventKey, codec.write(event));
        redisTemplate.opsForList().trim(eventKey, -properties.sseReplayLimit(), -1);
        redisTemplate.expire(eventKey, ttl);
        replayAppendCounter.increment();
        return true;
    }

    public SessionRuntimeStreamReplayResult replayAfter(String sessionId, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            replayMissCounter.increment();
            return new SessionRuntimeStreamReplayResult(false, List.of());
        }
        List<String> payloads = redisTemplate.opsForList().range(keyspace.sseEvents(sessionId), 0, -1);
        if (payloads == null || payloads.isEmpty()) {
            replayMissCounter.increment();
            return new SessionRuntimeStreamReplayResult(false, List.of());
        }
        boolean found = false;
        List<SessionRuntimeStreamEvent> replay = new ArrayList<>();
        for (String payload : payloads) {
            SessionRuntimeStreamEvent event = codec.read(payload, SessionRuntimeStreamEvent.class);
            if (found) {
                replay.add(event);
                continue;
            }
            if (event.id().equals(lastEventId)) {
                found = true;
            }
        }
        if (found) {
            replayMatchedCounter.increment();
        } else {
            replayMissCounter.increment();
        }
        return new SessionRuntimeStreamReplayResult(found, List.copyOf(replay));
    }
}
