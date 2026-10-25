package com.agentyard.worker.agent;

import com.agentyard.contracts.agent.AgentContracts.*;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface ToolRunActivities {
    ToolRunRuntime loadToolRun(String toolRunId);
    void recordToolRunProgress(String toolRunId, ToolRunProgress progress);
    void acknowledgeToolRunSignal(String toolRunId, String signalId);
}
