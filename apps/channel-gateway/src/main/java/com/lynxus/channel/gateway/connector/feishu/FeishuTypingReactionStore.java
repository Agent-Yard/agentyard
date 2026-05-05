package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.shared.redis.RedisKeyspace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class FeishuTypingReactionStore {
    private static final Logger log = LoggerFactory.getLogger(FeishuTypingReactionStore.class);

    private static final DefaultRedisScript<Long> SAVE_IF_ABSENT = new DefaultRedisScript<>(
        """
            if redis.call('exists', KEYS[1]) == 1 then
                return 0
            end
            redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('rpush', KEYS[2], KEYS[1])
            redis.call('pexpire', KEYS[2], ARGV[2])
            return 1
            """,
        Long.class
    );
    private static final DefaultRedisScript<Long> ATTACH_SESSION = new DefaultRedisScript<>(
        """
            local value = redis.call('get', KEYS[1])
            if not value then
                return 0
            end
            redis.call('rpush', KEYS[2], KEYS[1])
            redis.call('pexpire', KEYS[2], ARGV[1])
            return 1
            """,
        Long.class
    );
    private static final DefaultRedisScript<String> CLAIM_STATE_KEY = new DefaultRedisScript<>(
        """
            local value = redis.call('get', KEYS[1])
            if value then
                redis.call('del', KEYS[1])
            end
            return value
            """,
        String.class
    );
    private static final DefaultRedisScript<String> CLAIM_INDEX_HEAD = new DefaultRedisScript<>(
        """
            while true do
                local stateKey = redis.call('lpop', KEYS[1])
                if not stateKey then
                    return nil
                end
                local value = redis.call('get', stateKey)
                if value then
                    redis.call('del', stateKey)
                    return value
                end
            end
            """,
        String.class
    );

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyspace keyspace;
    private final ObjectMapper objectMapper;
    private final FeishuTypingReactionProperties properties;

    FeishuTypingReactionStore(
        StringRedisTemplate redisTemplate,
        RedisKeyspace keyspace,
        ObjectMapper objectMapper,
        FeishuTypingReactionProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.keyspace = keyspace;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    boolean saveIfAbsent(FeishuTypingReactionState state) {
        Duration ttl = properties.getTtl();
        String stateKey = stateKey(state.channelProfileId(), state.dedupKey());
        Long saved = redisTemplate.execute(
            SAVE_IF_ABSENT,
            List.of(stateKey, conversationIndexKey(state.channelProfileId(), state.externalConversationId())),
            write(state),
            String.valueOf(ttl.toMillis())
        );
        boolean created = Long.valueOf(1L).equals(saved);
        if (created && hasText(state.sessionId())) {
            attachSessionByDedupKey(state.channelProfileId(), state.dedupKey(), state.sessionId());
        }
        return created;
    }

    boolean attachSessionByDedupKey(String channelProfileId, String dedupKey, String sessionId) {
        if (!hasText(sessionId)) {
            return false;
        }
        Duration ttl = properties.getTtl();
        Long attached = redisTemplate.execute(
            ATTACH_SESSION,
            List.of(stateKey(channelProfileId, dedupKey), sessionIndexKey(channelProfileId, sessionId)),
            String.valueOf(ttl.toMillis())
        );
        return Long.valueOf(1L).equals(attached);
    }

    Optional<FeishuTypingReactionState> claimByDedupKey(String channelProfileId, String dedupKey) {
        return read(redisTemplate.execute(CLAIM_STATE_KEY, List.of(stateKey(channelProfileId, dedupKey))));
    }

    Optional<FeishuTypingReactionState> claimBySession(String channelProfileId, String sessionId) {
        if (!hasText(sessionId)) {
            return Optional.empty();
        }
        return read(redisTemplate.execute(CLAIM_INDEX_HEAD, List.of(sessionIndexKey(channelProfileId, sessionId))));
    }

    Optional<FeishuTypingReactionState> claimByConversation(String channelProfileId, String externalConversationId) {
        if (!hasText(externalConversationId)) {
            return Optional.empty();
        }
        return read(redisTemplate.execute(
            CLAIM_INDEX_HEAD,
            List.of(conversationIndexKey(channelProfileId, externalConversationId))
        ));
    }

    void restore(FeishuTypingReactionState state) {
        saveIfAbsent(state);
    }

    String stateKey(String channelProfileId, String dedupKey) {
        return keyspace.channelFeishuTypingReactionState(channelProfileId, hash(dedupKey));
    }

    String sessionIndexKey(String channelProfileId, String sessionId) {
        return keyspace.channelFeishuTypingReactionSessionIndex(channelProfileId, hash(sessionId));
    }

    String conversationIndexKey(String channelProfileId, String externalConversationId) {
        return keyspace.channelFeishuTypingReactionConversationIndex(channelProfileId, hash(externalConversationId));
    }

    private String write(FeishuTypingReactionState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception error) {
            throw new IllegalStateException("failed to serialize Feishu typing reaction state", error);
        }
    }

    private Optional<FeishuTypingReactionState> read(String value) {
        if (!hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, FeishuTypingReactionState.class));
        } catch (Exception error) {
            log.warn("discarding invalid Feishu typing reaction state from Redis", error);
            return Optional.empty();
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
