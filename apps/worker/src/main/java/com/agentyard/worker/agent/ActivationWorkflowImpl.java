package com.agentyard.worker.agent;

import com.agentyard.contracts.agent.ActivationWorkflow;
import com.agentyard.contracts.agent.AgentContracts.*;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.CancellationScope;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** One bounded wake-up. Durable identity and context outlive this workflow. */
public class ActivationWorkflowImpl implements ActivationWorkflow {
    public void run(ActivationStart start) {
        ActivationActivities execution = Workflow.newActivityStub(ActivationActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(15))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build()).build());
        ActivationActivities persistence = Workflow.newActivityStub(ActivationActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryOptions(RetryOptions.newBuilder().setInitialInterval(Duration.ofSeconds(1))
                    .setMaximumInterval(Duration.ofMinutes(1)).build()).build());
        try {
            ActivationOutcome outcome = null;
            // Resume the SAME activation and recorded tool IDs. Runtime leases fence
            // overlapping requests; uncertain external writes are never replayed.
            for (int attempt = 0; attempt < 6; attempt++) {
                try {
                    var next = execution.executeActivation(start.activationId());
                    outcome = next == null ? resumable("runtime returned no activation outcome", outcome) : next;
                } catch (CanceledFailure cancelled) {
                    throw cancelled;
                } catch (RuntimeException failure) {
                    if (CancellationScope.current().isCancelRequested()) {
                        throw new CanceledFailure("activation cancelled");
                    }
                    outcome = resumable("execution transport interrupted; resuming durable checkpoints", outcome);
                }
                if (!outcome.retryable()) break;
                if (attempt < 5) Workflow.sleep(Duration.ofSeconds(Math.min(60, 5L << attempt)));
            }
            if (outcome.retryable()) {
                outcome = new ActivationOutcome(false, null, outcome.workMemory(),
                    "automatic continuation budget exhausted: " + outcome.failureReason(), outcome.llmUsage(),
                    outcome.mappingTelemetry(), outcome.securityAssessment(), null, false);
            }
            persistence.completeActivation(start.activationId(), outcome);
        } catch (CanceledFailure cancelled) {
            Workflow.newDetachedCancellationScope(() ->
                persistence.completeActivation(start.activationId(), failure("activation cancelled"))).run();
            throw cancelled;
        }
    }

    private ActivationOutcome failure(String reason) {
        return new ActivationOutcome(false, null, Map.of(), reason, List.of(), null, null, null, false);
    }

    private ActivationOutcome resumable(String reason, ActivationOutcome previous) {
        return new ActivationOutcome(false, null, Map.of(), reason,
            previous == null ? List.of() : previous.llmUsage(), null, null, null, true);
    }
}
