package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionContracts.AgentTurnTransientFrame;

interface SessionChannelActivityRelay {
    void relay(AgentTurnTransientFrame frame);

    static SessionChannelActivityRelay noop() {
        return ignored -> {
        };
    }
}
