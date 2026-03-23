package com.lynxus.worker.workflow;

import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface KnowledgeQaActivities {
    WorkflowResult executeAgentRuntime(WorkflowStartRequest request);
}
