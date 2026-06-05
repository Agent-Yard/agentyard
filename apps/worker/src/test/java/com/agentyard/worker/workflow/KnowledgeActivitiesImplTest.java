package com.agentyard.worker.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agentyard.contracts.runtime.WorkflowContracts;
import com.agentyard.worker.runtime.KnowledgeServiceGateway;
import org.junit.jupiter.api.Test;

class KnowledgeActivitiesImplTest {
    @Test
    void shouldPreserveKnowledgeBaseIdAcrossImportWorkflow() {
        KnowledgeActivitiesImpl activities = new KnowledgeActivitiesImpl(new StubKnowledgeServiceGateway("COMPLETED", "READY"));

        WorkflowContracts.KnowledgeJobResult result = activities.runImport(
            new WorkflowContracts.KnowledgeImportRequest("wf-import", "knowledge-base-support", "import-job-1", sampleLogContext("wf-import"))
        );

        assertEquals("knowledge-base-support", result.knowledgeBaseId());
        assertEquals("COMPLETED", result.status());
    }

    @Test
    void shouldPreserveKnowledgeBaseIdAcrossIndexWorkflow() {
        KnowledgeActivitiesImpl activities = new KnowledgeActivitiesImpl(new StubKnowledgeServiceGateway("COMPLETED", "READY"));

        WorkflowContracts.KnowledgeJobResult result = activities.buildIndex(
            new WorkflowContracts.KnowledgeIndexBuildRequest("wf-index", "knowledge-base-support", "snapshot-1", sampleLogContext("wf-index"))
        );

        assertEquals("knowledge-base-support", result.knowledgeBaseId());
        assertEquals("READY", result.status());
    }

    private record StubKnowledgeServiceGateway(String importStatus, String snapshotStatus) implements KnowledgeServiceGateway {
        @Override
        public String runImportJob(String importJobId) {
            return importStatus;
        }

        @Override
        public String buildIndexSnapshot(String indexSnapshotId) {
            return snapshotStatus;
        }
    }

    private static WorkflowContracts.LogContext sampleLogContext(String workflowId) {
        return new WorkflowContracts.LogContext(
            "0123456789abcdef0123456789abcdef",
            null,
            workflowId,
            null,
            "user-1"
        );
    }
}
