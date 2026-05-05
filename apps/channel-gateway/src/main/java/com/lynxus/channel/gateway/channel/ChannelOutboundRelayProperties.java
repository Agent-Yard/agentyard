package com.lynxus.channel.gateway.channel;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "lynxus.channel-gateway.outbound-relay")
public class ChannelOutboundRelayProperties {
    private boolean enabled = true;
    private Duration scanFixedDelay = Duration.ofSeconds(5);
    private Duration ownerLockTtl = Duration.ofSeconds(30);
    private Duration connectTimeout = Duration.ofSeconds(10);
    @Min(1)
    private int remoteMaxPendingFinals = 100;
    @Min(0)
    private int remoteResumePendingFinals = 20;
    @Min(1)
    private int nativeMaxPendingFinals = 1;
    @Min(0)
    private int nativeResumePendingFinals = 0;
    @Min(0)
    private int transientQueueCapacity = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getScanFixedDelay() {
        return scanFixedDelay;
    }

    public void setScanFixedDelay(Duration scanFixedDelay) {
        this.scanFixedDelay = scanFixedDelay;
    }

    public Duration getOwnerLockTtl() {
        return ownerLockTtl;
    }

    public void setOwnerLockTtl(Duration ownerLockTtl) {
        this.ownerLockTtl = ownerLockTtl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public int getRemoteMaxPendingFinals() {
        return remoteMaxPendingFinals;
    }

    public void setRemoteMaxPendingFinals(int remoteMaxPendingFinals) {
        this.remoteMaxPendingFinals = remoteMaxPendingFinals;
    }

    public int getRemoteResumePendingFinals() {
        return remoteResumePendingFinals;
    }

    public void setRemoteResumePendingFinals(int remoteResumePendingFinals) {
        this.remoteResumePendingFinals = remoteResumePendingFinals;
    }

    public int getNativeMaxPendingFinals() {
        return nativeMaxPendingFinals;
    }

    public void setNativeMaxPendingFinals(int nativeMaxPendingFinals) {
        this.nativeMaxPendingFinals = nativeMaxPendingFinals;
    }

    public int getNativeResumePendingFinals() {
        return nativeResumePendingFinals;
    }

    public void setNativeResumePendingFinals(int nativeResumePendingFinals) {
        this.nativeResumePendingFinals = nativeResumePendingFinals;
    }

    public int getTransientQueueCapacity() {
        return transientQueueCapacity;
    }

    public void setTransientQueueCapacity(int transientQueueCapacity) {
        this.transientQueueCapacity = transientQueueCapacity;
    }
}
