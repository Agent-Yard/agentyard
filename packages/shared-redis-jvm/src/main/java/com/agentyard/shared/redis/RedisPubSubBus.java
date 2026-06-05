package com.agentyard.shared.redis;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

@Component
public class RedisPubSubBus {
    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final RedisSharedStateMetrics metrics;

    public RedisPubSubBus(
        StringRedisTemplate redisTemplate,
        RedisMessageListenerContainer listenerContainer,
        RedisSharedStateMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.metrics = metrics;
    }

    public void publish(String channel, String payload) {
        redisTemplate.convertAndSend(channel, payload);
        metrics.incrementPubSubPublished(channel);
    }

    public AutoCloseable subscribe(String channel, Consumer<String> consumer) {
        ChannelTopic topic = new ChannelTopic(channel);
        MessageListener listener = (message, pattern) -> {
            metrics.incrementPubSubReceived(channel);
            consumer.accept(new String(message.getBody(), StandardCharsets.UTF_8));
        };
        listenerContainer.addMessageListener(listener, topic);
        return () -> listenerContainer.removeMessageListener(listener, topic);
    }
}
