package com.agentyard.worker.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentyard.redis")
public record RedisHealthProperties(boolean healthCheckEnabled) {
}
