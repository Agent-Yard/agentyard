package com.lynxus.worker.workflow;

import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResumeRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface AssistantRunActivities {
    WorkflowResult startExecution(WorkflowStartRequest request);

    WorkflowResult resumeExecution(WorkflowResumeRequest request);
}
