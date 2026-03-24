package com.lynxus.worker.workflow;

import com.lynxus.contracts.runtime.KnowledgeQaEscalationWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
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
        KnowledgeQaActivitiesImpl activities,
        @Value("${lynxus.temporal.task-queue}") String taskQueue,
        @Value("${lynxus.temporal.namespace}") String namespace,
        @Value("${lynxus.temporal.activity-start-to-close-timeout:PT2M}") Duration activityStartToCloseTimeout
    ) {
        WorkflowClient workflowClient = WorkflowClient.newInstance(
            serviceStubs,
            WorkflowClientOptions.newBuilder().setNamespace(namespace).build()
        );
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(taskQueue);
        worker.registerWorkflowImplementationFactory(
            KnowledgeQaEscalationWorkflow.class,
            () -> new KnowledgeQaEscalationWorkflowImpl(activityStartToCloseTimeout)
        );
        worker.registerActivitiesImplementations(activities);
        factory.start();
        return factory;
    }
}
