package com.agentyard.worker.workflow;

import com.agentyard.contracts.session.PlaybookWorkflow;
import com.agentyard.contracts.session.SessionWorkflow;
import com.agentyard.contracts.runtime.KnowledgeImportWorkflow;
import com.agentyard.contracts.runtime.KnowledgeIndexBuildWorkflow;
import com.agentyard.worker.session.AgentTurnActivitiesImpl;
import com.agentyard.worker.session.SessionPersistenceActivitiesImpl;
import com.agentyard.worker.session.PlaybookNodeActivitiesImpl;
import com.agentyard.worker.session.PlaybookWorkflowImpl;
import com.agentyard.worker.session.SessionWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TemporalWorkerConfiguration {
    @Bean
    WorkflowServiceStubs workflowServiceStubs(@Value("${agentyard.temporal.target}") String target) {
        return WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(target).build()
        );
    }

    @Bean
    WorkerFactory workerFactory(
        WorkflowServiceStubs serviceStubs,
        AgentTurnActivitiesImpl agentTurnActivities,
        SessionPersistenceActivitiesImpl sessionPersistenceActivities,
        PlaybookNodeActivitiesImpl playbookNodeActivities,
        KnowledgeActivitiesImpl knowledgeActivities,
        @Value("${agentyard.temporal.task-queue}") String taskQueue,
        @Value("${agentyard.temporal.namespace}") String namespace,
        @Value("${agentyard.temporal.activity-start-to-close-timeout:PT2M}") Duration activityStartToCloseTimeout,
        @Value("${agentyard.temporal.workflow-deadlock-detection-timeout:PT5S}") Duration workflowDeadlockDetectionTimeout
    ) {
        WorkflowClient workflowClient = WorkflowClient.newInstance(
            serviceStubs,
            WorkflowClientOptions.newBuilder().setNamespace(namespace).build()
        );
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(taskQueue, workerOptions(workflowDeadlockDetectionTimeout));
        worker.registerWorkflowImplementationFactory(
            SessionWorkflow.class,
            () -> new SessionWorkflowImpl(activityStartToCloseTimeout)
        );
        worker.registerWorkflowImplementationFactory(
            PlaybookWorkflow.class,
            () -> new PlaybookWorkflowImpl(activityStartToCloseTimeout)
        );
        worker.registerWorkflowImplementationFactory(
            KnowledgeImportWorkflow.class,
            () -> new KnowledgeImportWorkflowImpl(activityStartToCloseTimeout)
        );
        worker.registerWorkflowImplementationFactory(
            KnowledgeIndexBuildWorkflow.class,
            () -> new KnowledgeIndexBuildWorkflowImpl(activityStartToCloseTimeout)
        );
        worker.registerActivitiesImplementations(
            knowledgeActivities,
            agentTurnActivities,
            sessionPersistenceActivities,
            playbookNodeActivities
        );
        factory.start();
        return factory;
    }

    WorkerOptions workerOptions(Duration workflowDeadlockDetectionTimeout) {
        return WorkerOptions.newBuilder()
            .setDefaultDeadlockDetectionTimeout(workflowDeadlockDetectionTimeout.toMillis())
            .validateAndBuildWithDefaults();
    }
}
