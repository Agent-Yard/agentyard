package com.agentyard.channel.gateway.connector.feishu;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentyard.channel-gateway.feishu.typing-reaction")
public class FeishuTypingReactionProperties {
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    private static final Duration MAX_TTL = Duration.ofHours(1);

    private Duration ttl = DEFAULT_TTL;

    public Duration getTtl() {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return DEFAULT_TTL;
        }
        return ttl.compareTo(MAX_TTL) > 0 ? MAX_TTL : ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
}
