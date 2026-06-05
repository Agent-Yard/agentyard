package com.agentyard.platform.shared.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentyard.platform.shared.ConflictException;
import com.agentyard.shared.redis.RedisJsonCodec;
import com.agentyard.shared.redis.RedisSharedStateMetrics;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

class RedisIdempotencyServiceTest {
    @Test
    void shouldReturnCompletedPayloadWithoutExecutingActionAgain() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency-key")).thenReturn("COMPLETED:{\"value\":\"cached\"}");
        RedisIdempotencyService service = service(redisTemplate);

        TestPayload result = service.execute(
            "idempotency-key",
            TestPayload.class,
            () -> {
                throw new AssertionError("action should not run");
            }
        );

        assertEquals("cached", result.value());
        verify(valueOperations, never()).setIfAbsent(any(), any(), any(Duration.class));
    }

    @Test
    void shouldPersistCompletedPayloadAfterFirstExecution() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency-key")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("idempotency-key"), any(), any(Duration.class))).thenReturn(true);
        RedisIdempotencyService service = service(redisTemplate);
        AtomicInteger executions = new AtomicInteger();

        TestPayload result = service.execute(
            "idempotency-key",
            TestPayload.class,
            () -> new TestPayload("value-" + executions.incrementAndGet())
        );

        assertEquals("value-1", result.value());
        assertEquals(1, executions.get());
        verify(valueOperations).set(eq("idempotency-key"), eq("COMPLETED:{\"value\":\"value-1\"}"), any(Duration.class));
    }

    @Test
    void shouldRejectDuplicateRequestWhileFirstExecutionIsInProgress() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency-key")).thenReturn(null, "STARTED:instance-a:token");
        when(valueOperations.setIfAbsent(eq("idempotency-key"), any(), any(Duration.class))).thenReturn(false);
        RedisIdempotencyService service = service(redisTemplate);

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> service.execute("idempotency-key", TestPayload.class, () -> new TestPayload("unexpected"))
        );

        assertEquals("duplicate request is already in progress", error.getMessage());
    }

    private static RedisIdempotencyService service(StringRedisTemplate redisTemplate) {
        return new RedisIdempotencyService(
            redisTemplate,
            new RedisJsonCodec(new ObjectMapper()),
            new RedisSharedStateProperties("instance-a", Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofHours(24), Duration.ofMinutes(15), 128, Duration.ofSeconds(1)),
            new RedisSharedStateMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
        );
    }

    private record TestPayload(String value) {
    }
}
