package com.agentyard.worker.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.temporal.worker.WorkerOptions;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TemporalWorkerConfigurationTest {
    @Test
    void shouldApplyConfiguredWorkflowDeadlockDetectionTimeout() {
        TemporalWorkerConfiguration configuration = new TemporalWorkerConfiguration();

        WorkerOptions options = configuration.workerOptions(Duration.ofSeconds(5));

        assertEquals(5_000, options.getDefaultDeadlockDetectionTimeout());
    }

    @Test
    void shouldFallbackToTemporalDefaultWhenTimeoutIsZero() {
        TemporalWorkerConfiguration configuration = new TemporalWorkerConfiguration();

        WorkerOptions options = configuration.workerOptions(Duration.ZERO);

        assertEquals(1_000, options.getDefaultDeadlockDetectionTimeout());
    }
}
