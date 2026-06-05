package com.agentyard.channel.gateway.connector.feishu;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentyard.channel-gateway.feishu.streaming-reply-card")
public class FeishuStreamingReplyCardProperties {
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);
    private static final Duration MAX_TTL = Duration.ofDays(14);
    private static final Duration DEFAULT_EMPTY_CARD_DELETE_DELAY = Duration.ofSeconds(5);
    private static final Duration MAX_EMPTY_CARD_DELETE_DELAY = Duration.ofMinutes(1);

    private Duration ttl = DEFAULT_TTL;
    private Duration emptyCardDeleteDelay = DEFAULT_EMPTY_CARD_DELETE_DELAY;

    public Duration getTtl() {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return DEFAULT_TTL;
        }
        return ttl.compareTo(MAX_TTL) > 0 ? MAX_TTL : ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration getEmptyCardDeleteDelay() {
        if (emptyCardDeleteDelay == null || emptyCardDeleteDelay.isNegative()) {
            return DEFAULT_EMPTY_CARD_DELETE_DELAY;
        }
        return emptyCardDeleteDelay.compareTo(MAX_EMPTY_CARD_DELETE_DELAY) > 0 ? MAX_EMPTY_CARD_DELETE_DELAY : emptyCardDeleteDelay;
    }

    public void setEmptyCardDeleteDelay(Duration emptyCardDeleteDelay) {
        this.emptyCardDeleteDelay = emptyCardDeleteDelay;
    }
}
