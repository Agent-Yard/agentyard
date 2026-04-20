package com.lynxus.worker.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.session.PlaybookWorkflow;
import com.lynxus.contracts.session.SessionContracts.AgentConfig;
import com.lynxus.contracts.session.SessionContracts.AgentDecisionAction;
import com.lynxus.contracts.session.SessionContracts.PlaybookConfig;
import com.lynxus.contracts.session.SessionContracts.PlaybookEdge;
import com.lynxus.contracts.session.SessionContracts.PlaybookExecutionPolicy;
import com.lynxus.contracts.session.SessionContracts.PlaybookNode;
import com.lynxus.contracts.session.SessionContracts.PlaybookNodeType;
import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.PlaybookRunStatus;
import com.lynxus.contracts.session.SessionContracts.PlaybookStartRequest;
import io.temporal.client.WorkflowClient;
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
                    Map.of("result", Map.of("orderId", "ord-1"))
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
