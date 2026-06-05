package com.agentyard.channel.gateway.connector.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentyard.shared.redis.RedisKeyspace;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.ObjectMapper;

class FeishuTypingReactionStoreTest {
    @Test
    void saveIfAbsentStoresStateWithConversationIndexAndBoundedTtl() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(anyRedisScript(), anyStringList(), anyString(), anyString())).thenReturn(1L);
        FeishuTypingReactionProperties properties = new FeishuTypingReactionProperties();
        properties.setTtl(Duration.ofHours(2));
        FeishuTypingReactionStore store = new FeishuTypingReactionStore(
            redisTemplate,
            new RedisKeyspace("test"),
            new ObjectMapper(),
            properties
        );

        FeishuTypingReactionState state = state(null);
        assertTrue(store.saveIfAbsent(state));

        verify(redisTemplate).execute(
            anyRedisScript(),
            eq(List.of(
                store.stateKey("profile-1", "dedup-1"),
                store.conversationIndexKey("profile-1", "oc_1")
            )),
            anyString(),
            eq(String.valueOf(Duration.ofHours(1).toMillis()))
        );
    }

    @Test
    void claimBySessionReturnsStoredState() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        FeishuTypingReactionState state = state("session-1");
        when(redisTemplate.execute(anyRedisScript(), anyStringList()))
            .thenReturn(objectMapper.writeValueAsString(state));
        FeishuTypingReactionStore store = new FeishuTypingReactionStore(
            redisTemplate,
            new RedisKeyspace("test"),
            objectMapper,
            new FeishuTypingReactionProperties()
        );

        assertEquals(state, store.claimBySession("profile-1", "session-1").orElseThrow());

        verify(redisTemplate).execute(
            anyRedisScript(),
            eq(List.of(store.sessionIndexKey("profile-1", "session-1")))
        );
    }

    private static FeishuTypingReactionState state(String sessionId) {
        return new FeishuTypingReactionState(
            "profile-1",
            "oc_1",
            "om_1",
            "dedup-1",
            sessionId,
            "reaction-1"
        );
    }

    private static List<String> anyStringList() {
        return any();
    }

    @SuppressWarnings("unchecked")
    private static <T> DefaultRedisScript<T> anyRedisScript() {
        return (DefaultRedisScript<T>) any(DefaultRedisScript.class);
    }
}
