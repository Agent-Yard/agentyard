package com.agentyard.contracts.agent;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ToolRunWorkflow {
    @WorkflowMethod void run(AgentContracts.ToolRunStart start);
    @SignalMethod void resume(AgentContracts.ToolRunResume signal);
    @SignalMethod void cancel();
    @SignalMethod void setPaused(boolean paused);
}
