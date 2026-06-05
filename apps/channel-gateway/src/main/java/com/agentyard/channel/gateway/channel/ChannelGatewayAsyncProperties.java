package com.agentyard.channel.gateway.channel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "agentyard.channel-gateway.async")
public class ChannelGatewayAsyncProperties {
    @Valid
    private ExecutorPool inboundSessionDispatch = new ExecutorPool(2, 8, 1000, 60, 30, true);

    public ExecutorPool getInboundSessionDispatch() {
        return inboundSessionDispatch;
    }

    public void setInboundSessionDispatch(ExecutorPool inboundSessionDispatch) {
        this.inboundSessionDispatch = inboundSessionDispatch;
    }

    public static class ExecutorPool {
        @Min(0)
        private int coreSize;

        @Min(1)
        private int maxSize;

        @Min(0)
        private int queueCapacity;

        @Min(1)
        private int keepAliveSeconds;

        @Min(0)
        private int shutdownAwaitSeconds;

        private boolean waitForTasksToCompleteOnShutdown;

        public ExecutorPool() {
        }

        public ExecutorPool(
            int coreSize,
            int maxSize,
            int queueCapacity,
            int keepAliveSeconds,
            int shutdownAwaitSeconds,
            boolean waitForTasksToCompleteOnShutdown
        ) {
            this.coreSize = coreSize;
            this.maxSize = maxSize;
            this.queueCapacity = queueCapacity;
            this.keepAliveSeconds = keepAliveSeconds;
            this.shutdownAwaitSeconds = shutdownAwaitSeconds;
            this.waitForTasksToCompleteOnShutdown = waitForTasksToCompleteOnShutdown;
        }

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = coreSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        public void setKeepAliveSeconds(int keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
        }

        public int getShutdownAwaitSeconds() {
            return shutdownAwaitSeconds;
        }

        public void setShutdownAwaitSeconds(int shutdownAwaitSeconds) {
            this.shutdownAwaitSeconds = shutdownAwaitSeconds;
        }

        public boolean isWaitForTasksToCompleteOnShutdown() {
            return waitForTasksToCompleteOnShutdown;
        }

        public void setWaitForTasksToCompleteOnShutdown(boolean waitForTasksToCompleteOnShutdown) {
            this.waitForTasksToCompleteOnShutdown = waitForTasksToCompleteOnShutdown;
        }

        @AssertTrue(message = "max-size must be greater than or equal to core-size")
        public boolean isMaxSizeValid() {
            return maxSize >= coreSize;
        }
    }
}
