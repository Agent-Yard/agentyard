package com.lynxus.worker.workflow;

import com.lynxus.contracts.runtime.KnowledgeQaEscalationWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
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
        @Value("${lynxus.temporal.task-queue}") String taskQueue
    ) {
        WorkflowClient workflowClient = WorkflowClient.newInstance(serviceStubs);
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(taskQueue);
        worker.registerWorkflowImplementationTypes(KnowledgeQaEscalationWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        factory.start();
        return factory;
    }
}
