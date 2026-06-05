package com.agentyard.worker.workflow;

import com.agentyard.contracts.runtime.WorkflowContracts.KnowledgeImportRequest;
import com.agentyard.contracts.runtime.WorkflowContracts.KnowledgeIndexBuildRequest;
import com.agentyard.contracts.runtime.WorkflowContracts.KnowledgeJobResult;
import com.agentyard.worker.logging.WorkerLogContext;
import com.agentyard.worker.runtime.KnowledgeServiceGateway;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeActivitiesImpl implements KnowledgeActivities {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeActivitiesImpl.class);
    private final KnowledgeServiceGateway knowledgeServiceGateway;

    public KnowledgeActivitiesImpl(KnowledgeServiceGateway knowledgeServiceGateway) {
        this.knowledgeServiceGateway = knowledgeServiceGateway;
    }

    @Override
    public KnowledgeJobResult runImport(KnowledgeImportRequest request) {
        try (WorkerLogContext.Scope _ = WorkerLogContext.open(request.logContext())) {
            log.info("knowledge import activity workflowId={} importJobId={}", request.workflowId(), request.importJobId());
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
    }

    @Override
    public KnowledgeJobResult buildIndex(KnowledgeIndexBuildRequest request) {
        try (WorkerLogContext.Scope _ = WorkerLogContext.open(request.logContext())) {
            log.info("knowledge index activity workflowId={} snapshotId={}", request.workflowId(), request.indexSnapshotId());
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
}
