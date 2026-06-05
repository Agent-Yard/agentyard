package com.agentyard.worker.session;

import com.agentyard.contracts.session.SessionContracts.AgentTurnRequest;
import com.agentyard.contracts.session.SessionContracts.AgentTurnExecutionOutcome;
import com.agentyard.worker.runtime.SessionAgentRuntimeGateway;
import org.springframework.stereotype.Component;

@Component
public class AgentTurnActivitiesImpl implements AgentTurnActivities {
    private final SessionAgentRuntimeGateway sessionAgentRuntimeGateway;

    public AgentTurnActivitiesImpl(SessionAgentRuntimeGateway sessionAgentRuntimeGateway) {
        this.sessionAgentRuntimeGateway = sessionAgentRuntimeGateway;
    }

    @Override
    public AgentTurnExecutionOutcome executeTurn(AgentTurnRequest request) {
        return sessionAgentRuntimeGateway.executeTurnStream(request);
    }
}
