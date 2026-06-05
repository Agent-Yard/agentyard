package com.agentyard.channel.gateway.connector.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentyard.shared.redis.RedisKeyspace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

class RedisFeishuStreamingReplyCardStoreTest {
    @Test
    void savesAndReadsStreamingReplyCardStateWithTtl() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RedisFeishuStreamingReplyCardStore store = new RedisFeishuStreamingReplyCardStore(
            redisTemplate,
            new RedisKeyspace(),
            new ObjectMapper(),
            new FeishuStreamingReplyCardProperties()
        );
        FeishuStreamingReplyCardState state = state();
        String key = store.stateKey(state.key());
        ArgumentCaptor<String> serialized = ArgumentCaptor.forClass(String.class);

        store.save(state);

        verify(valueOperations).set(eq(key), serialized.capture(), eq(new FeishuStreamingReplyCardProperties().getTtl()));
        when(valueOperations.get(key)).thenReturn(serialized.getValue());
        assertEquals(Optional.of(state), store.find(state.key()));
    }

    @Test
    void invalidRedisStateIsIgnored() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RedisFeishuStreamingReplyCardStore store = new RedisFeishuStreamingReplyCardStore(
            redisTemplate,
            new RedisKeyspace(),
            new ObjectMapper(),
            new FeishuStreamingReplyCardProperties()
        );
        when(valueOperations.get(store.stateKey(state().key()))).thenReturn("{not-json");

        assertFalse(store.find(state().key()).isPresent());
    }

    private static FeishuStreamingReplyCardState state() {
        return new FeishuStreamingReplyCardState(
            new FeishuStreamingReplyCardKey("profile-1", "session-1", "message-1"),
            "chat-1",
            "card-1",
            "external-message-1",
            "markdown_1",
            List.of(new FeishuStreamingReplyCardBlock("reply-block-1", "TEXT", "hello", false)),
            2,
            5L,
            false,
            Instant.parse("2026-05-05T00:00:00Z")
        );
    }
}
