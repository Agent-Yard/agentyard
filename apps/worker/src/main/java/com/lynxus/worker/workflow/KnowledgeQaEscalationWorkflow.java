package com.lynxus.worker.workflow;

import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowResult;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStartRequest;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface KnowledgeQaEscalationWorkflow {
    @WorkflowMethod
    WorkflowResult run(WorkflowStartRequest request);
}
