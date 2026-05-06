package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.shared.redis.RedisKeyspace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class RedisFeishuStreamingReplyCardStore implements FeishuStreamingReplyCardStore {
    private static final Logger log = LoggerFactory.getLogger(RedisFeishuStreamingReplyCardStore.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyspace keyspace;
    private final ObjectMapper objectMapper;
    private final FeishuStreamingReplyCardProperties properties;

    RedisFeishuStreamingReplyCardStore(
        StringRedisTemplate redisTemplate,
        RedisKeyspace keyspace,
        ObjectMapper objectMapper,
        FeishuStreamingReplyCardProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.keyspace = keyspace;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<FeishuStreamingReplyCardState> find(FeishuStreamingReplyCardKey key) {
        String value = redisTemplate.opsForValue().get(stateKey(key));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, FeishuStreamingReplyCardState.class));
        } catch (Exception error) {
            log.warn("discarding invalid Feishu streaming reply card state from Redis", error);
            return Optional.empty();
        }
    }

    @Override
    public void save(FeishuStreamingReplyCardState state) {
        try {
            redisTemplate.opsForValue().set(
                stateKey(state.key()),
                objectMapper.writeValueAsString(state),
                properties.getTtl()
            );
        } catch (Exception error) {
            throw new IllegalStateException("failed to save Feishu streaming reply card state", error);
        }
    }

    @Override
    public void delete(FeishuStreamingReplyCardKey key) {
        redisTemplate.delete(stateKey(key));
    }

    String stateKey(FeishuStreamingReplyCardKey key) {
        return keyspace.channelFeishuStreamingReplyCardState(
            key.channelProfileId(),
            hash(key.sessionId()),
            hash(key.messageId())
        );
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
