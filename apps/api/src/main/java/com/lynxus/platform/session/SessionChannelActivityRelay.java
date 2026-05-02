package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionContracts.AgentTurnStreamFrame;

interface SessionChannelActivityRelay {
    void relay(AgentTurnStreamFrame frame);

    static SessionChannelActivityRelay noop() {
        return ignored -> {
        };
    }
}
