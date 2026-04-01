package com.lynxus.platform.knowledge;

import com.lynxus.contracts.runtime.KnowledgeImportWorkflow;
import com.lynxus.contracts.runtime.KnowledgeIndexBuildWorkflow;
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeImportRequest;
import com.lynxus.contracts.runtime.WorkflowContracts.KnowledgeIndexBuildRequest;
import com.lynxus.platform.shared.logging.PlatformLogContext;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

public interface KnowledgeWorkflowGateway {
    void startImport(String knowledgeBaseId, String importJobId);

    void startIndexBuild(String knowledgeBaseId, String indexSnapshotId);

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
        public void startImport(String knowledgeBaseId, String importJobId) {
            String workflowId = "knowledge-import-" + importJobId;
            KnowledgeImportWorkflow workflow = workflowClient.newWorkflowStub(
                KnowledgeImportWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(taskQueue)
                    .setWorkflowId(workflowId)
                    .build()
            );
            WorkflowClient.start(
                workflow::run,
                new KnowledgeImportRequest(workflowId, knowledgeBaseId, importJobId, PlatformLogContext.capture(null, workflowId, null))
            );
        }

        @Override
        public void startIndexBuild(String knowledgeBaseId, String indexSnapshotId) {
            String workflowId = "knowledge-index-" + indexSnapshotId;
            KnowledgeIndexBuildWorkflow workflow = workflowClient.newWorkflowStub(
                KnowledgeIndexBuildWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(taskQueue)
                    .setWorkflowId(workflowId)
                    .build()
            );
            WorkflowClient.start(
                workflow::run,
                new KnowledgeIndexBuildRequest(workflowId, knowledgeBaseId, indexSnapshotId, PlatformLogContext.capture(null, workflowId, null))
            );
        }
    }
}
