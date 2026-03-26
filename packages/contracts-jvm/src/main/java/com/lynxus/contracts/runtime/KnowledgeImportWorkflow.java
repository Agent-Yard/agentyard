package com.lynxus.contracts.runtime;

import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeImportRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeJobResult;
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
