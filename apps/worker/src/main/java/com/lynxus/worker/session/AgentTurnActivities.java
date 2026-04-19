package com.lynxus.worker.session;

import com.lynxus.contracts.session.SessionContracts.AgentTurnRequest;
import com.lynxus.contracts.session.SessionContracts.AgentTurnResult;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface AgentTurnActivities {
    AgentTurnResult executeTurn(AgentTurnRequest request);
}
