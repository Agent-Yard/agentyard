package com.agentyard.platform.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentyard.redis")
public record RedisHealthProperties(boolean healthCheckEnabled) {
}
