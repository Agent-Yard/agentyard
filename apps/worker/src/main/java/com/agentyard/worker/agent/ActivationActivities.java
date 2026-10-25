package com.agentyard.worker.agent;

import com.agentyard.contracts.agent.AgentContracts.ActivationOutcome;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface ActivationActivities {
    ActivationOutcome executeActivation(String activationId);
    void completeActivation(String activationId, ActivationOutcome outcome);
}
