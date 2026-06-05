package com.agentyard.channel.gateway.channel;

import org.springframework.stereotype.Component;

@Component
public class ChannelOutboundReplayCreditCalculator {
    public int replayCredit(int maxPendingFinals, int currentPendingFinals) {
        if (maxPendingFinals <= 0) {
            throw new IllegalArgumentException("maxPendingFinals must be positive");
        }
        if (currentPendingFinals < 0) {
            throw new IllegalArgumentException("currentPendingFinals must not be negative");
        }
        return Math.max(0, maxPendingFinals - currentPendingFinals);
    }

    public boolean shouldResume(int currentPendingFinals, int resumePendingFinals) {
        if (currentPendingFinals < 0) {
            throw new IllegalArgumentException("currentPendingFinals must not be negative");
        }
        if (resumePendingFinals < 0) {
            throw new IllegalArgumentException("resumePendingFinals must not be negative");
        }
        return currentPendingFinals <= resumePendingFinals;
    }
}
