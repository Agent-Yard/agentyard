package com.lynxus.contracts.runtime;

import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeIndexBuildRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeJobResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface KnowledgeIndexBuildWorkflow {
    @WorkflowMethod
    KnowledgeJobResult run(KnowledgeIndexBuildRequest request);

    @QueryMethod
    KnowledgeJobResult currentResult();
}
