package com.lynxus.worker.workflow;

import com.lynxus.contracts.session.PlaybookWorkflow;
import com.lynxus.contracts.session.SessionWorkflow;
import com.lynxus.contracts.runtime.KnowledgeImportWorkflow;
import com.lynxus.contracts.runtime.KnowledgeIndexBuildWorkflow;
import com.lynxus.worker.session.AgentTurnActivitiesImpl;
import com.lynxus.worker.session.SessionPersistenceActivitiesImpl;
import com.lynxus.worker.session.PlaybookNodeActivitiesImpl;
import com.lynxus.worker.session.PlaybookWorkflowImpl;
import com.lynxus.worker.session.SessionWorkflowImpl;
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
    WorkflowServiceStubs workflowServiceStubs(@Value("${lynxus.temporal.target}") String target) {
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
        @Value("${lynxus.temporal.task-queue}") String taskQueue,
        @Value("${lynxus.temporal.namespace}") String namespace,
        @Value("${lynxus.temporal.activity-start-to-close-timeout:PT2M}") Duration activityStartToCloseTimeout,
        @Value("${lynxus.temporal.workflow-deadlock-detection-timeout:PT5S}") Duration workflowDeadlockDetectionTimeout
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
