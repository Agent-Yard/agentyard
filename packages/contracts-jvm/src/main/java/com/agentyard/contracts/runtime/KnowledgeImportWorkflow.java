package com.agentyard.contracts.runtime;

import com.agentyard.contracts.runtime.WorkflowContracts.KnowledgeImportRequest;
import com.agentyard.contracts.runtime.WorkflowContracts.KnowledgeJobResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface KnowledgeImportWorkflow {
    @WorkflowMethod
    KnowledgeJobResult run(KnowledgeImportRequest request);

    @QueryMethod
    KnowledgeJobResult currentResult();
}
