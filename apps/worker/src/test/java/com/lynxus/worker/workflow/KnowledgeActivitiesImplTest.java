package com.lynxus.worker.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lynxus.contracts.runtime.WorkflowContracts;
import com.lynxus.worker.runtime.KnowledgeServiceGateway;
import org.junit.jupiter.api.Test;

class KnowledgeActivitiesImplTest {
    @Test
    void shouldPreserveKnowledgeBaseIdAcrossImportWorkflow() {
        KnowledgeActivitiesImpl activities = new KnowledgeActivitiesImpl(new StubKnowledgeServiceGateway("COMPLETED", "READY"));

        WorkflowContracts.KnowledgeJobResult result = activities.runImport(
            new WorkflowContracts.KnowledgeImportRequest("wf-import", "knowledge-base-support", "import-job-1")
        );

        assertEquals("knowledge-base-support", result.knowledgeBaseId());
        assertEquals("COMPLETED", result.status());
    }

    @Test
    void shouldPreserveKnowledgeBaseIdAcrossIndexWorkflow() {
        KnowledgeActivitiesImpl activities = new KnowledgeActivitiesImpl(new StubKnowledgeServiceGateway("COMPLETED", "READY"));

        WorkflowContracts.KnowledgeJobResult result = activities.buildIndex(
            new WorkflowContracts.KnowledgeIndexBuildRequest("wf-index", "knowledge-base-support", "snapshot-1")
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
}
