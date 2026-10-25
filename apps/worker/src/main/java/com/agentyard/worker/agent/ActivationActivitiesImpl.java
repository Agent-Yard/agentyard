package com.agentyard.worker.agent;

import com.agentyard.contracts.agent.AgentContracts.ActivationOutcome;
import com.agentyard.worker.runtime.AgentRuntimeGateway;
import com.agentyard.worker.runtime.ExecutionControlPlane;
import org.springframework.stereotype.Component;

@Component
public class ActivationActivitiesImpl implements ActivationActivities {
    private final AgentRuntimeGateway runtime;
    private final ExecutionControlPlane controlPlane;

    public ActivationActivitiesImpl(AgentRuntimeGateway runtime, ExecutionControlPlane controlPlane) {
        this.runtime = runtime;
        this.controlPlane = controlPlane;
    }

    public ActivationOutcome executeActivation(String id) {
        return runtime.executeActivation(id);
    }

    public void completeActivation(String id, ActivationOutcome outcome) {
        controlPlane.completeActivation(id, outcome);
    }
}
