package com.agentyard.platform.session;

import com.agentyard.contracts.session.SessionContracts.AgentTurnTransientFrame;

interface SessionChannelActivityRelay {
    void relay(AgentTurnTransientFrame frame);

    static SessionChannelActivityRelay noop() {
        return ignored -> {
        };
    }
}
