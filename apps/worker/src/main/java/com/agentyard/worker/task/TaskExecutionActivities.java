package com.agentyard.worker.task;

import com.agentyard.contracts.task.TaskContracts.*;
import com.agentyard.contracts.session.SessionContracts.AgentTurnExecutionOutcome;
import io.temporal.activity.ActivityInterface;
import java.util.Map;

@ActivityInterface
public interface TaskExecutionActivities {
    ExecutionInput taskExecutionInput(String executionId);
    Execution taskExecutionProgress(String executionId, ExecutionProgress progress);
    AgentTurnExecutionOutcome taskAgentExecute(String executionId, Map<String,Object> resume);
}
