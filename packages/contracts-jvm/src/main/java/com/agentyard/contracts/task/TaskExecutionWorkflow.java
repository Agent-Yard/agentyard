package com.agentyard.contracts.task;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import static com.agentyard.contracts.task.TaskContracts.*;

@WorkflowInterface
public interface TaskExecutionWorkflow {
    @WorkflowMethod void run(ExecutionStart request);
    @SignalMethod void resume(ResumeSignal signal);
    @SignalMethod void cancel();
}
