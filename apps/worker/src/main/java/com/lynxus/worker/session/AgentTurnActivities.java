package com.lynxus.worker.session;

import com.lynxus.contracts.session.SessionContracts.AgentTurnRequest;
import com.lynxus.contracts.session.SessionContracts.AgentTurnExecutionOutcome;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface AgentTurnActivities {
    AgentTurnExecutionOutcome executeTurn(AgentTurnRequest request);
}
