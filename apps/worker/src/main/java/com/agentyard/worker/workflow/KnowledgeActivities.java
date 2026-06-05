package com.agentyard.worker.workflow;

import com.agentyard.contracts.runtime.WorkflowContracts.KnowledgeImportRequest;
import com.agentyard.contracts.runtime.WorkflowContracts.KnowledgeIndexBuildRequest;
import com.agentyard.contracts.runtime.WorkflowContracts.KnowledgeJobResult;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface KnowledgeActivities {
    KnowledgeJobResult runImport(KnowledgeImportRequest request);

    KnowledgeJobResult buildIndex(KnowledgeIndexBuildRequest request);
}
