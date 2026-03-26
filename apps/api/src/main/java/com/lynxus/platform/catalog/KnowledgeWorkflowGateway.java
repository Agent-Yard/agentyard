package com.lynxus.platform.catalog;

import com.lynxus.contracts.runtime.KnowledgeImportWorkflow;
import com.lynxus.contracts.runtime.KnowledgeIndexBuildWorkflow;
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeImportRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeIndexBuildRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

public interface KnowledgeWorkflowGateway {
    void startImport(String resourceId, String importJobId);

    void startIndexBuild(String resourceId, String indexSnapshotId);

    @Component
    class TemporalKnowledgeWorkflowGateway implements KnowledgeWorkflowGateway {
        private final WorkflowClient workflowClient;
        private final String taskQueue;

        public TemporalKnowledgeWorkflowGateway(
            WorkflowClient workflowClient,
            @Value("${lynxus.temporal.task-queue}") String taskQueue
        ) {
            this.workflowClient = workflowClient;
            this.taskQueue = taskQueue;
        }

        @Override
        public void startImport(String resourceId, String importJobId) {
            KnowledgeImportWorkflow workflow = workflowClient.newWorkflowStub(
                KnowledgeImportWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(taskQueue)
                    .setWorkflowId("knowledge-import-" + importJobId)
                    .build()
            );
            WorkflowClient.start(workflow::run, new KnowledgeImportRequest("knowledge-import-" + importJobId, resourceId, importJobId));
        }

        @Override
        public void startIndexBuild(String resourceId, String indexSnapshotId) {
            KnowledgeIndexBuildWorkflow workflow = workflowClient.newWorkflowStub(
                KnowledgeIndexBuildWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(taskQueue)
                    .setWorkflowId("knowledge-index-" + indexSnapshotId)
                    .build()
            );
            WorkflowClient.start(workflow::run, new KnowledgeIndexBuildRequest("knowledge-index-" + indexSnapshotId, resourceId, indexSnapshotId));
        }
    }
}
