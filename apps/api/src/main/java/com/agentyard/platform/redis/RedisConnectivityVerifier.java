package com.agentyard.platform.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisConnectivityVerifier implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisConnectivityVerifier.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisHealthProperties redisHealthProperties;

    public RedisConnectivityVerifier(StringRedisTemplate redisTemplate, RedisHealthProperties redisHealthProperties) {
        this.redisTemplate = redisTemplate;
        this.redisHealthProperties = redisHealthProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!redisHealthProperties.healthCheckEnabled()) {
            LOGGER.info("redis connectivity verification skipped");
            return;
        }
        RedisConnectionFactory connectionFactory = redisTemplate.getConnectionFactory();
        if (connectionFactory == null) {
            throw new IllegalStateException("redis health check failed: missing connection factory");
        }
        String response;
        try (RedisConnection connection = connectionFactory.getConnection()) {
            response = connection.ping();
        }
        if (!"PONG".equalsIgnoreCase(response)) {
            throw new IllegalStateException("redis health check failed: unexpected response " + response);
        }
        LOGGER.info("redis connectivity verified");
    }
}
