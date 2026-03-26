package com.lynxus.worker.workflow;

import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeImportRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeIndexBuildRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeJobResult;
import com.lynxus.worker.runtime.KnowledgeServiceGateway;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeActivitiesImpl implements KnowledgeActivities {
    private final KnowledgeServiceGateway knowledgeServiceGateway;

    public KnowledgeActivitiesImpl(KnowledgeServiceGateway knowledgeServiceGateway) {
        this.knowledgeServiceGateway = knowledgeServiceGateway;
    }

    @Override
    public KnowledgeJobResult runImport(KnowledgeImportRequest request) {
        String status = knowledgeServiceGateway.runImportJob(request.importJobId());
        return new KnowledgeJobResult(
            request.workflowId(),
            request.knowledgeBaseId(),
            request.importJobId(),
            status,
            "knowledge import " + status.toLowerCase(),
            Instant.now()
        );
    }

    @Override
    public KnowledgeJobResult buildIndex(KnowledgeIndexBuildRequest request) {
        String status = knowledgeServiceGateway.buildIndexSnapshot(request.indexSnapshotId());
        return new KnowledgeJobResult(
            request.workflowId(),
            request.knowledgeBaseId(),
            request.indexSnapshotId(),
            status,
            "knowledge index build " + status.toLowerCase(),
            Instant.now()
        );
    }
}
