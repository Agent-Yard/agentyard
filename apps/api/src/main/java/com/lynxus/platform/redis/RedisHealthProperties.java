package com.lynxus.platform.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lynxus.redis")
public record RedisHealthProperties(boolean healthCheckEnabled) {
}
