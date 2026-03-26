package com.lynxus.worker.workflow;

import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeImportRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeIndexBuildRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeJobResult;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface KnowledgeActivities {
    KnowledgeJobResult runImport(KnowledgeImportRequest request);

    KnowledgeJobResult buildIndex(KnowledgeIndexBuildRequest request);
}
