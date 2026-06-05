package com.agentyard.worker.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentyard.contracts.session.PlaybookWorkflow;
import com.agentyard.contracts.session.SessionContracts.AgentConfig;
import com.agentyard.contracts.session.SessionContracts.AgentDecisionAction;
import com.agentyard.contracts.session.SessionContracts.PlaybookConfig;
import com.agentyard.contracts.session.SessionContracts.PlaybookExecutionPolicy;
import com.agentyard.contracts.session.SessionContracts.PlaybookNode;
import com.agentyard.contracts.session.SessionContracts.PlaybookNodeLayout;
import com.agentyard.contracts.session.SessionContracts.PlaybookNodeType;
import com.agentyard.contracts.session.SessionContracts.PlaybookRun;
import com.agentyard.contracts.session.SessionContracts.PlaybookRunStatus;
import com.agentyard.contracts.session.SessionContracts.PlaybookStartRequest;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaybookWorkflowImplTest {
    @Test
    void run_shouldFailWhenEndResultViolatesResultSchema() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("playbook-tests");
            worker.registerWorkflowImplementationTypes(PlaybookWorkflowImpl.class);
            worker.registerActivitiesImplementations(new NoopPlaybookNodeActivities());
            environment.start();

            PlaybookWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                PlaybookWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue("playbook-tests").build()
            );

            PlaybookRun result = workflow.run(
                new PlaybookStartRequest(
                    "session-1",
                    "run-1",
                    "event-1",
                    "agent-1",
                    ownerAgent(),
                    invalidResultPlaybook(),
                    Map.of("orderId", "ord-1")
                )
            );

            assertEquals(PlaybookRunStatus.FAILED, result.status());
            assertEquals(Map.of(), result.result());
            assertTrue(result.failureReason().contains("playbook result failed resultSchema validation"));
        }
    }

    @Test
    void run_shouldFailWhenEndNodeTriesToEmitCancelled() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("playbook-tests-cancelled-end");
            worker.registerWorkflowImplementationTypes(PlaybookWorkflowImpl.class);
            worker.registerActivitiesImplementations(new NoopPlaybookNodeActivities());
            environment.start();

            PlaybookWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                PlaybookWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue("playbook-tests-cancelled-end").build()
            );

            PlaybookRun result = workflow.run(
                new PlaybookStartRequest(
                    "session-1",
                    "run-1",
                    "event-1",
                    "agent-1",
                    ownerAgent(),
                    cancelledEndPlaybook(),
                    Map.of("orderId", "ord-1")
                )
            );

            assertEquals(PlaybookRunStatus.FAILED, result.status());
            assertTrue(result.failureReason().contains("playbook END node cannot emit CANCELLED directly"));
        }
    }

    private static PlaybookConfig invalidResultPlaybook() {
        return new PlaybookConfig(
            "playbook-1",
            "Refund",
            "Refund playbook",
            "{\"type\":\"object\"}",
            "{\"type\":\"object\",\"required\":[\"approved\"],\"properties\":{\"approved\":{\"type\":\"boolean\"}},\"additionalProperties\":false}",
            new PlaybookExecutionPolicy(null, null),
            false,
            false,
            "finish",
            List.of(
                new PlaybookNode(
                    "finish",
                    "Finish",
                    PlaybookNodeType.END,
                    "",
                    null,
                    null,
                    null,
                    null,
                    Map.of("result", Map.of("orderId", "ord-1")),
                    new PlaybookNodeLayout(120, 120)
                )
            ),
            List.of()
        );
    }

    private static PlaybookConfig cancelledEndPlaybook() {
        return new PlaybookConfig(
            "playbook-2",
            "Cancel",
            "Invalid cancel playbook",
            "{\"type\":\"object\"}",
            "{\"type\":\"object\"}",
            new PlaybookExecutionPolicy(null, null),
            false,
            false,
            "finish",
            List.of(
                new PlaybookNode(
                    "finish",
                    "Finish",
                    PlaybookNodeType.END,
                    "",
                    null,
                    null,
                    null,
                    null,
                    Map.of("status", "CANCELLED"),
                    new PlaybookNodeLayout(120, 120)
                )
            ),
            List.of()
        );
    }

    private static AgentConfig ownerAgent() {
        return new AgentConfig(
            "agent-1",
            "Agent 1",
            "support",
            "Handle the session",
            null,
            null,
            false,
            "",
            false,
            null,
            null,
            8,
            true,
            List.of(AgentDecisionAction.REPLY),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static final class NoopPlaybookNodeActivities implements PlaybookNodeActivities {
        @Override
        public PlaybookNodeExecutionResult executeStep(PlaybookNodeExecutionRequest request) {
            return new PlaybookNodeExecutionResult(Map.of(), null, null, null);
        }

        @Override
        public PlaybookNodeExecutionResult executeTool(PlaybookNodeExecutionRequest request) {
            return new PlaybookNodeExecutionResult(Map.of(), null, null, null);
        }
    }
}
