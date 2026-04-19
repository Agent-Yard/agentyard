package com.lynxus.platform.session;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TemporalSessionConfiguration {
    @Bean
    WorkflowServiceStubs workflowServiceStubs(@Value("${lynxus.temporal.target}") String target) {
        return WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(target).build()
        );
    }

    @Bean
    WorkflowClient workflowClient(
        WorkflowServiceStubs serviceStubs,
        @Value("${lynxus.temporal.namespace}") String namespace
    ) {
        return WorkflowClient.newInstance(
            serviceStubs,
            WorkflowClientOptions.newBuilder().setNamespace(namespace).build()
        );
    }
}
