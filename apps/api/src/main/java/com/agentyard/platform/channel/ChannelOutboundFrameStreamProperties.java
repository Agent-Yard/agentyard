package com.agentyard.platform.channel;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentyard.channel-outbound-frame-stream")
public record ChannelOutboundFrameStreamProperties(Duration heartbeatInterval) {
    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(20);

    public ChannelOutboundFrameStreamProperties {
        heartbeatInterval = heartbeatInterval == null || heartbeatInterval.isZero() || heartbeatInterval.isNegative()
            ? DEFAULT_HEARTBEAT_INTERVAL
            : heartbeatInterval;
    }
}
