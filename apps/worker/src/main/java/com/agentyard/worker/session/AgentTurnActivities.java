package com.agentyard.worker.session;

import com.agentyard.contracts.session.SessionContracts.AgentTurnRequest;
import com.agentyard.contracts.session.SessionContracts.AgentTurnExecutionOutcome;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface AgentTurnActivities {
    AgentTurnExecutionOutcome executeTurn(AgentTurnRequest request);
}
