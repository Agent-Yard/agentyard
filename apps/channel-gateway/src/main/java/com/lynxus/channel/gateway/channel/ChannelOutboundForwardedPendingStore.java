package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameAck;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import com.lynxus.shared.redis.RedisKeyspace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ChannelOutboundForwardedPendingStore {
    private static final DefaultRedisScript<Long> ACK_HEAD = new DefaultRedisScript<>(
        """
            if redis.call('exists', KEYS[2]) == 0 then
                return 2
            end
            local head = redis.call('lindex', KEYS[1], 0)
            if not head then
                return 3
            end
            if head ~= ARGV[1] then
                return 4
            end
            redis.call('lpop', KEYS[1])
            redis.call('del', KEYS[2])
            if redis.call('llen', KEYS[1]) == 0 then
                redis.call('del', KEYS[1])
            end
            return 1
            """,
        Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyspace keyspace;
    private final ObjectMapper objectMapper;
    private final ChannelOutboundRelayProperties properties;

    public ChannelOutboundForwardedPendingStore(
        StringRedisTemplate redisTemplate,
        RedisKeyspace keyspace,
        ObjectMapper objectMapper,
        ChannelOutboundRelayProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.keyspace = keyspace;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void markForwarded(ChannelOutboundProfileConsumer consumer, ChannelOutboundFrame frame) {
        if (frame.kind() != ChannelOutboundFrameKind.FINAL_DELIVERY || frame.finalSequence() == null) {
            throw new IllegalArgumentException("only FINAL_DELIVERY frames can be marked pending");
        }
        String markerKey = markerKey(consumer, frame.finalSequence());
        String pendingKey = pendingKey(consumer);
        String entry = pendingEntry(frame.finalSequence(), frame.frameId());
        String marker = write(new ForwardedFinalFrame(
            frame.frameId(),
            frame.finalSequence(),
            frame.sessionId(),
            Objects.toString(frame.payload().get("sessionMessageId"), null),
            entry
        ));
        Duration ttl = properties.getExtensionForwardedPendingTtl();
        Boolean markerCreated = redisTemplate.opsForValue().setIfAbsent(markerKey, marker, ttl);
        if (Boolean.TRUE.equals(markerCreated)) {
            redisTemplate.opsForList().rightPush(pendingKey, entry);
        }
        redisTemplate.expire(pendingKey, ttl);
        redisTemplate.expire(markerKey, ttl);
    }

    public AckPendingResult ackForwarded(ChannelOutboundProfileConsumer consumer, ChannelOutboundFrameAck ack) {
        String markerKey = markerKey(consumer, ack.finalSequence());
        String markerValue = redisTemplate.opsForValue().get(markerKey);
        if (markerValue == null || markerValue.isBlank()) {
            return AckPendingResult.MARKER_MISSING;
        }
        ForwardedFinalFrame marker = read(markerValue);
        if (!marker.matches(ack)) {
            return AckPendingResult.MARKER_MISMATCH;
        }
        Long result = redisTemplate.execute(
            ACK_HEAD,
            List.of(pendingKey(consumer), markerKey),
            marker.pendingEntry()
        );
        if (Long.valueOf(1L).equals(result)) {
            return AckPendingResult.ACKED;
        }
        if (Long.valueOf(4L).equals(result)) {
            return AckPendingResult.NON_HEAD;
        }
        return AckPendingResult.MARKER_MISSING;
    }

    public long pendingFinalCount(ChannelOutboundProfileConsumer consumer) {
        Long size = redisTemplate.opsForList().size(pendingKey(consumer));
        return size == null ? 0 : size;
    }

    public void clear(ChannelOutboundProfileConsumer consumer) {
        String pendingKey = pendingKey(consumer);
        List<String> entries = redisTemplate.opsForList().range(pendingKey, 0, -1);
        if (entries != null) {
            for (String entry : entries) {
                Long finalSequence = finalSequenceFromPendingEntry(entry);
                if (finalSequence != null) {
                    redisTemplate.delete(markerKey(consumer, finalSequence));
                }
            }
        }
        redisTemplate.delete(pendingKey);
    }

    String pendingKey(ChannelOutboundProfileConsumer consumer) {
        return keyspace.channelOutboundExtensionPendingFinals(ProfileConsumerKeys.key(consumer));
    }

    String markerKey(ChannelOutboundProfileConsumer consumer, long finalSequence) {
        return keyspace.channelOutboundExtensionForwardedFinal(ProfileConsumerKeys.key(consumer), finalSequence);
    }

    static String pendingEntry(long finalSequence, String frameId) {
        return finalSequence + ":" + frameIdHash(frameId);
    }

    private static Long finalSequenceFromPendingEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        int separator = entry.indexOf(':');
        String raw = separator < 0 ? entry : entry.substring(0, separator);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String frameIdHash(String frameId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(frameId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private String write(ForwardedFinalFrame marker) {
        try {
            return objectMapper.writeValueAsString(marker);
        } catch (Exception error) {
            throw new IllegalStateException("failed to serialize channel outbound forwarded marker", error);
        }
    }

    private ForwardedFinalFrame read(String markerValue) {
        try {
            return objectMapper.readValue(markerValue, ForwardedFinalFrame.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("channel outbound forwarded marker is invalid", error);
        }
    }

    public enum AckPendingResult {
        ACKED,
        MARKER_MISSING,
        MARKER_MISMATCH,
        NON_HEAD
    }

    record ForwardedFinalFrame(
        String frameId,
        long finalSequence,
        String sessionId,
        String sessionMessageId,
        String pendingEntry
    ) {
        private boolean matches(ChannelOutboundFrameAck ack) {
            return finalSequence == ack.finalSequence()
                && Objects.equals(frameId, ack.frameId())
                && Objects.equals(sessionId, ack.sessionId())
                && Objects.equals(sessionMessageId, ack.sessionMessageId());
        }
    }
}
