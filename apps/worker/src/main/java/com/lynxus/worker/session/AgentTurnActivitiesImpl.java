package com.lynxus.worker.session;

import com.lynxus.contracts.session.SessionContracts.AgentTurnRequest;
import com.lynxus.contracts.session.SessionContracts.AgentTurnExecutionOutcome;
import com.lynxus.worker.runtime.SessionAgentRuntimeGateway;
import org.springframework.stereotype.Component;

@Component
public class AgentTurnActivitiesImpl implements AgentTurnActivities {
    private final SessionAgentRuntimeGateway sessionAgentRuntimeGateway;

    public AgentTurnActivitiesImpl(SessionAgentRuntimeGateway sessionAgentRuntimeGateway) {
        this.sessionAgentRuntimeGateway = sessionAgentRuntimeGateway;
    }

    @Override
    public AgentTurnExecutionOutcome executeTurn(AgentTurnRequest request) {
        return sessionAgentRuntimeGateway.executeTurn(request);
    }
}
