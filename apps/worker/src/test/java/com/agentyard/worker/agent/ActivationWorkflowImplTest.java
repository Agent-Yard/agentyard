package com.agentyard.worker.agent;

import static org.junit.jupiter.api.Assertions.*;
import com.agentyard.contracts.agent.ActivationWorkflow;
import com.agentyard.contracts.agent.AgentContracts.*;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ActivationWorkflowImplTest {
    private void run(RecordingActivities activities) {
        try (var env = TestWorkflowEnvironment.newInstance()) {
            var worker = env.newWorker("activation-test");
            worker.registerWorkflowImplementationTypes(ActivationWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
            env.start();
            var workflow = env.getWorkflowClient().newWorkflowStub(ActivationWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue("activation-test").build());
            workflow.run(new ActivationStart("activation-1"));
        }
    }

    @Test void completionTransportRetryDoesNotReexecuteModel() {
        var activities = new RecordingActivities(0, 0, true, "COMPLETED");
        run(activities);
        assertEquals(1, activities.executions.get());
        assertEquals(2, activities.completions.get());
        assertTrue(activities.result.success());
    }

    @Test void transportInterruptionAndComputeYieldResumeTheSameActivation() {
        var activities = new RecordingActivities(1, 2, false, "COMPLETED");
        run(activities);
        assertEquals(3, activities.executions.get());
        assertEquals(List.of("activation-1", "activation-1", "activation-1"), activities.ids);
        assertEquals(1, activities.completions.get());
        assertTrue(activities.result.success());
    }

    @Test void repeatedOutageStopsAtBoundedBudgetAndReportsOnce() {
        var activities = new RecordingActivities(100, 0, false, "COMPLETED");
        run(activities);
        assertEquals(6, activities.executions.get());
        assertEquals(1, activities.completions.get());
        assertFalse(activities.result.success());
        assertFalse(activities.result.retryable());
        assertTrue(activities.result.failureReason().contains("budget exhausted"));
    }

    @Test void realExternalWaitAndBlockNeverScheduleAutomaticContinuation() {
        for (String disposition : List.of("WAITING", "BLOCKED")) {
            var activities = new RecordingActivities(0, 0, false, disposition);
            run(activities);
            assertEquals(1, activities.executions.get());
            assertEquals(disposition, activities.result.completion());
        }
    }

    private static final class RecordingActivities implements ActivationActivities {
        final AtomicInteger executions = new AtomicInteger();
        final AtomicInteger completions = new AtomicInteger();
        final List<String> ids = new ArrayList<>();
        final int transportFailures;
        final int yieldingAttempts;
        final boolean failCompletionOnce;
        final String disposition;
        volatile ActivationOutcome result;
        RecordingActivities(int transportFailures, int yieldingAttempts, boolean failCompletionOnce, String disposition) {
            this.transportFailures = transportFailures;
            this.yieldingAttempts = yieldingAttempts;
            this.failCompletionOnce = failCompletionOnce;
            this.disposition = disposition;
        }
        public ActivationOutcome executeActivation(String id) {
            ids.add(id);
            int attempt = executions.incrementAndGet();
            if (attempt <= transportFailures) throw new IllegalStateException("connection lost after checkpoint");
            if (attempt <= yieldingAttempts) return new ActivationOutcome(false, null, Map.of(), "slice budget", List.of(), null, null, null, true);
            return new ActivationOutcome(true, null, Map.of("currentTask", "task-1"), null, List.of(), null, null, disposition, false);
        }
        public void completeActivation(String id, ActivationOutcome outcome) {
            if (completions.incrementAndGet() == 1 && failCompletionOnce) throw new IllegalStateException("control plane temporarily unavailable");
            result = outcome;
        }
    }
}
