package com.lynxus.contracts.runtime;

import com.lynxus.contracts.runtime.WorkflowContracts.ResumeAction;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface AssistantRunWorkflow {
    @WorkflowMethod
    WorkflowResult run(WorkflowStartRequest request);

    @SignalMethod
    void submitResumeAction(ResumeAction action);

    @QueryMethod
    WorkflowResult currentResult();
}
