package com.agentyard.contracts.agent;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ActivationWorkflow {
    @WorkflowMethod void run(AgentContracts.ActivationStart start);
}
