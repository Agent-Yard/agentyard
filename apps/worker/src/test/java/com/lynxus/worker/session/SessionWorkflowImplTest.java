package com.lynxus.worker.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.session.SessionContracts.AgentConfig;
import com.lynxus.contracts.session.SessionContracts.AgentDecision;
import com.lynxus.contracts.session.SessionContracts.AgentDecisionAction;
import com.lynxus.contracts.session.SessionContracts.AgentTurnRequest;
import com.lynxus.contracts.session.SessionContracts.AgentTurnResult;
import com.lynxus.contracts.session.SessionContracts.AssistantSessionConfig;
import com.lynxus.contracts.session.SessionContracts.ExternalCallbackSignal;
import com.lynxus.contracts.session.SessionContracts.HumanResumeSignal;
import com.lynxus.contracts.session.SessionContracts.PlaybookConfig;
import com.lynxus.contracts.session.SessionContracts.PlaybookEdge;
import com.lynxus.contracts.session.SessionContracts.PlaybookExecutionPolicy;
import com.lynxus.contracts.session.SessionContracts.PlaybookNode;
import com.lynxus.contracts.session.SessionContracts.PlaybookNodeType;
import com.lynxus.contracts.session.SessionContracts.PlaybookRun;
import com.lynxus.contracts.session.SessionContracts.SessionEvent;
import com.lynxus.contracts.session.SessionContracts.SessionEventType;
import com.lynxus.contracts.session.SessionContracts.SessionMessageDeliveryStatus;
import com.lynxus.contracts.session.SessionContracts.SessionOwnerPolicy;
import com.lynxus.contracts.session.SessionContracts.SessionPolicy;
import com.lynxus.contracts.session.SessionContracts.SessionSnapshot;
import com.lynxus.contracts.session.SessionContracts.SessionStartRequest;
import com.lynxus.contracts.session.SessionContracts.SessionTriggerType;
import com.lynxus.contracts.session.SessionContracts.UserMessage;
import com.lynxus.contracts.session.SessionWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class SessionWorkflowImplTest {
    @Test
    void submitUserMessage_shouldRejectWhenWorkflowTurnsDrainingAtGuardrail() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("session-tests-guardrail");
            RecordingPersistenceActivities persistence = new RecordingPersistenceActivities();
            worker.registerWorkflowImplementationTypes(SessionWorkflowImpl.class, PlaybookWorkflowImpl.class);
            worker.registerActivitiesImplementations(
                new RunPlaybookAgentTurnActivities(),
                persistence,
                new NoopPlaybookNodeActivities()
            );
            environment.start();

            SessionWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                SessionWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue("session-tests-guardrail").setWorkflowId("session-1").build()
            );
            WorkflowClient.start(
                workflow::run,
                startRequestForAgentActionsAndPolicy(
                    List.of(AgentDecisionAction.RUN_PLAYBOOK, AgentDecisionAction.NO_REPLY),
                    Duration.ofHours(1),
                    Duration.ofSeconds(2),
                    20_000
                )
            );

            assertEquals(
                SessionMessageDeliveryStatus.ACCEPTED,
                workflow.submitUserMessage(new UserMessage("msg-1", "customer-1", "start", Map.of())).status()
            );

            waitForEvent(environment, persistence, SessionEventType.PLAYBOOK_WAITING);
            environment.sleep(Duration.ofSeconds(3));

            assertEquals(
                SessionMessageDeliveryStatus.REJECTED,
                workflow.submitUserMessage(new UserMessage("msg-2", "customer-1", "follow up", Map.of())).status()
            );

            environment.sleep(Duration.ofSeconds(1));
            assertEquals(1, countEvents(persistence.events(), SessionEventType.USER_MESSAGE));
            assertTrue(workflow.currentSnapshot().draining());
        }
    }

    @Test
    void resumeSignals_shouldAppendReceivedEventsOnlyAfterValidation() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("session-tests");
            RecordingPersistenceActivities persistence = new RecordingPersistenceActivities();
            worker.registerWorkflowImplementationTypes(SessionWorkflowImpl.class, PlaybookWorkflowImpl.class);
            worker.registerActivitiesImplementations(
                new RunPlaybookAgentTurnActivities(),
                persistence,
                new NoopPlaybookNodeActivities()
            );
            environment.start();

            SessionWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                SessionWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue("session-tests").setWorkflowId("session-1").build()
            );
            WorkflowClient.start(workflow::run, startRequest());

            assertEquals(
                SessionMessageDeliveryStatus.ACCEPTED,
                workflow.submitUserMessage(new UserMessage("msg-1", "customer-1", "start", Map.of())).status()
            );

            waitForEvent(environment, persistence, SessionEventType.PLAYBOOK_WAITING);
            String activeRunId = workflow.currentSnapshot().activePlaybookRunId();

            workflow.humanResume(new HumanResumeSignal("session-1", "run-wrong", Map.of("approved", true)));
            environment.sleep(Duration.ofSeconds(1));
            assertEquals(0, countEvents(persistence.events(), SessionEventType.HUMAN_RESUME_RECEIVED));

            workflow.humanResume(new HumanResumeSignal("session-wrong", activeRunId, Map.of("approved", true)));
            environment.sleep(Duration.ofSeconds(1));
            assertEquals(0, countEvents(persistence.events(), SessionEventType.HUMAN_RESUME_RECEIVED));

            workflow.humanResume(new HumanResumeSignal("session-1", activeRunId, Map.of("approved", true)));
            waitForEvent(environment, persistence, SessionEventType.HUMAN_RESUME_RECEIVED);
            waitForEvent(environment, persistence, SessionEventType.PLAYBOOK_RESUMED);

            workflow.humanResume(new HumanResumeSignal("session-1", activeRunId, Map.of("approved", true)));
            environment.sleep(Duration.ofSeconds(1));
            assertEquals(1, countEvents(persistence.events(), SessionEventType.HUMAN_RESUME_RECEIVED));

            workflow.externalCallback(new ExternalCallbackSignal("session-1", activeRunId, Map.of("approved", true)));
            environment.sleep(Duration.ofSeconds(1));
            assertEquals(0, countEvents(persistence.events(), SessionEventType.EXTERNAL_CALLBACK_RECEIVED));
        }
    }

    @Test
    void malformedRunPlaybookDecision_shouldBeRejectedByWorkerAuthority() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("session-tests-invalid-run-playbook");
            RecordingPersistenceActivities persistence = new RecordingPersistenceActivities();
            worker.registerWorkflowImplementationTypes(SessionWorkflowImpl.class);
            worker.registerActivitiesImplementations(new MalformedRunPlaybookActivities(), persistence);
            environment.start();

            SessionWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                SessionWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue("session-tests-invalid-run-playbook")
                    .setWorkflowId("session-invalid-run-playbook")
                    .build()
            );
            WorkflowClient.start(workflow::run, startRequestForAgentActions(List.of(AgentDecisionAction.RUN_PLAYBOOK)));

            assertEquals(
                SessionMessageDeliveryStatus.ACCEPTED,
                workflow.submitUserMessage(new UserMessage("msg-1", "customer-1", "start", Map.of())).status()
            );

            waitForEvent(environment, persistence, SessionEventType.AGENT_DECISION_REJECTED);
            assertEquals(
                "run_playbook_playbook_id_required",
                latestEventOfType(persistence.events(), SessionEventType.AGENT_DECISION_REJECTED).payload().get("rejectReason")
            );
            assertEquals(0, countEvents(persistence.events(), SessionEventType.PLAYBOOK_STARTED));
        }
    }

    @Test
    void replyDecision_shouldIgnoreExtraFieldsWithoutRejection() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("session-tests-reply-extra-fields");
            RecordingPersistenceActivities persistence = new RecordingPersistenceActivities();
            worker.registerWorkflowImplementationTypes(SessionWorkflowImpl.class);
            worker.registerActivitiesImplementations(new ReplyWithExtraFieldsActivities(), persistence);
            environment.start();

            SessionWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                SessionWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue("session-tests-reply-extra-fields")
                    .setWorkflowId("session-reply-extra-fields")
                    .build()
            );
            WorkflowClient.start(workflow::run, startRequestForAgentActions(List.of(AgentDecisionAction.REPLY)));

            assertEquals(
                SessionMessageDeliveryStatus.ACCEPTED,
                workflow.submitUserMessage(new UserMessage("msg-1", "customer-1", "start", Map.of())).status()
            );

            waitForEvent(environment, persistence, SessionEventType.OWNER_REPLY);
            assertEquals(0, countEvents(persistence.events(), SessionEventType.AGENT_DECISION_REJECTED));
            assertEquals(1, countEvents(persistence.events(), SessionEventType.OWNER_REPLY));
        }
    }

    @Test
    void rejectedDecision_shouldPersistSharedStateBeforeRejectionEvent() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("session-tests-shared-state-order");
            RecordingPersistenceActivities persistence = new RecordingPersistenceActivities();
            worker.registerWorkflowImplementationTypes(SessionWorkflowImpl.class);
            worker.registerActivitiesImplementations(new MalformedRunPlaybookActivities(), persistence);
            environment.start();

            SessionWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                SessionWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue("session-tests-shared-state-order")
                    .setWorkflowId("session-shared-state-order")
                    .build()
            );
            WorkflowClient.start(workflow::run, startRequestForAgentActions(List.of(AgentDecisionAction.RUN_PLAYBOOK)));

            assertEquals(
                SessionMessageDeliveryStatus.ACCEPTED,
                workflow.submitUserMessage(new UserMessage("msg-1", "customer-1", "start", Map.of())).status()
            );

            waitForEvent(environment, persistence, SessionEventType.AGENT_DECISION_REJECTED);
            assertEquals("malformed-run-playbook", workflow.currentSnapshot().sharedState().get("reviewMarker"));
            assertTrue(
                persistence.firstOperationIndex("saveSession:sharedState.reviewMarker=malformed-run-playbook")
                    < persistence.firstOperationIndex("appendEvent:AGENT_DECISION_REJECTED")
            );
        }
    }

    @Test
    void malformedSwitchOwnerDecision_shouldBeRejectedByWorkerAuthority() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("session-tests-invalid-switch-owner");
            RecordingPersistenceActivities persistence = new RecordingPersistenceActivities();
            worker.registerWorkflowImplementationTypes(SessionWorkflowImpl.class);
            worker.registerActivitiesImplementations(new MalformedSwitchOwnerActivities(), persistence);
            environment.start();

            SessionWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                SessionWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue("session-tests-invalid-switch-owner")
                    .setWorkflowId("session-invalid-switch-owner")
                    .build()
            );
            WorkflowClient.start(
                workflow::run,
                startRequestForAgentActions(List.of(AgentDecisionAction.SWITCH_OWNER))
            );

            assertEquals(
                SessionMessageDeliveryStatus.ACCEPTED,
                workflow.submitUserMessage(new UserMessage("msg-1", "customer-1", "start", Map.of())).status()
            );

            waitForEvent(environment, persistence, SessionEventType.AGENT_DECISION_REJECTED);
            assertEquals(
                "switch_owner_target_agent_id_required",
                latestEventOfType(persistence.events(), SessionEventType.AGENT_DECISION_REJECTED).payload().get("rejectReason")
            );
            assertEquals(0, countEvents(persistence.events(), SessionEventType.OWNER_SWITCH));
        }
    }

    private static SessionStartRequest startRequest() {
        return startRequestForAgentActions(List.of(AgentDecisionAction.RUN_PLAYBOOK, AgentDecisionAction.NO_REPLY));
    }

    private static SessionStartRequest startRequestForAgentActions(List<AgentDecisionAction> allowedActions) {
        return startRequestForAgentActionsAndPolicy(allowedActions, Duration.ofHours(1), Duration.ofDays(7), 20_000);
    }

    private static SessionStartRequest startRequestForAgentActionsAndPolicy(
        List<AgentDecisionAction> allowedActions,
        Duration idleTimeout,
        Duration maxWorkflowAge,
        int maxWorkflowHistoryEvents
    ) {
        return new SessionStartRequest(
            "session-1",
            "scenario-1",
            "Session",
            "customer-1",
            new AssistantSessionConfig(
                "assistant-1",
                "Assistant",
                "2026.04.20",
                "agent-1",
                new SessionOwnerPolicy(3),
                new SessionPolicy(idleTimeout, maxWorkflowAge, maxWorkflowHistoryEvents),
                new PlaybookExecutionPolicy(null, null)
            ),
            List.of(
                new AgentConfig(
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
                    allowedActions,
                    List.of("agent-2"),
                    List.of("playbook-1"),
                    List.of(),
                    List.of()
                ),
                new AgentConfig(
                    "agent-2",
                    "Agent 2",
                    "specialist",
                    "Take over escalated sessions",
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
                )
            ),
            List.of(waitingPlaybook()),
            Map.of()
        );
    }

    private static PlaybookConfig waitingPlaybook() {
        return new PlaybookConfig(
            "playbook-1",
            "Playbook",
            "Wait for human resume",
            "{\"type\":\"object\"}",
            "{\"type\":\"object\"}",
            new PlaybookExecutionPolicy(null, null),
            true,
            false,
            "wait-human",
            List.of(
                new PlaybookNode("wait-human", "Wait", PlaybookNodeType.HUMAN_TASK, "", null, null, null, null, Map.of()),
                new PlaybookNode("finish", "Finish", PlaybookNodeType.END, "", null, null, null, null, Map.of("result", Map.of("approved", true)))
            ),
            List.of(
                new PlaybookEdge("wait-to-finish", "wait-human", "finish", null, null, true)
            )
        );
    }

    private static void waitForEvent(
        TestWorkflowEnvironment environment,
        RecordingPersistenceActivities persistence,
        SessionEventType eventType
    ) {
        for (int attempt = 0; attempt < 20; attempt += 1) {
            if (countEvents(persistence.events(), eventType) > 0) {
                return;
            }
            environment.sleep(Duration.ofMillis(200));
        }
        throw new AssertionError(
            "expected event not recorded: " + eventType + ", actual events=" + persistence.events().stream().map(SessionEvent::eventType).toList()
        );
    }

    private static long countEvents(List<SessionEvent> events, SessionEventType eventType) {
        return events.stream().filter(event -> event.eventType() == eventType).count();
    }

    private static SessionEvent latestEventOfType(List<SessionEvent> events, SessionEventType eventType) {
        return events.stream()
            .filter(event -> event.eventType() == eventType)
            .reduce((first, second) -> second)
            .orElseThrow();
    }

    private static final class RunPlaybookAgentTurnActivities implements AgentTurnActivities {
        @Override
        public AgentTurnResult executeTurn(AgentTurnRequest request) {
            if (request.trigger().triggerType() == SessionTriggerType.USER_MESSAGE) {
                return new AgentTurnResult(
                    new AgentDecision(
                        AgentDecisionAction.RUN_PLAYBOOK,
                        null,
                        null,
                        "playbook-1",
                        Map.of("customerId", "customer-1"),
                        null
                    ),
                    Map.of()
                );
            }
            return new AgentTurnResult(
                new AgentDecision(AgentDecisionAction.NO_REPLY, null, null, null, Map.of(), null),
                Map.of()
            );
        }
    }

    private static final class MalformedRunPlaybookActivities implements AgentTurnActivities {
        @Override
        public AgentTurnResult executeTurn(AgentTurnRequest request) {
            return new AgentTurnResult(
                new AgentDecision(
                    AgentDecisionAction.RUN_PLAYBOOK,
                    null,
                    "agent-2",
                    null,
                    Map.of("customerId", "customer-1"),
                    null
                ),
                Map.of("reviewMarker", "malformed-run-playbook")
            );
        }
    }

    private static final class MalformedSwitchOwnerActivities implements AgentTurnActivities {
        @Override
        public AgentTurnResult executeTurn(AgentTurnRequest request) {
            return new AgentTurnResult(
                new AgentDecision(
                    AgentDecisionAction.SWITCH_OWNER,
                    null,
                    null,
                    "playbook-1",
                    Map.of("customerId", "customer-1"),
                    null
                ),
                Map.of("reviewMarker", "malformed-switch-owner")
            );
        }
    }

    private static final class ReplyWithExtraFieldsActivities implements AgentTurnActivities {
        @Override
        public AgentTurnResult executeTurn(AgentTurnRequest request) {
            return new AgentTurnResult(
                new AgentDecision(
                    AgentDecisionAction.REPLY,
                    "reply from owner",
                    "agent-2",
                    "playbook-1",
                    Map.of("customerId", "customer-1"),
                    "ignored accompanying reply"
                ),
                Map.of()
            );
        }
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

    private static final class RecordingPersistenceActivities implements SessionPersistenceActivities {
        private final List<SessionEvent> events = new CopyOnWriteArrayList<>();
        private final ConcurrentMap<String, PlaybookRun> playbookRuns = new ConcurrentHashMap<>();
        private final List<String> operations = new CopyOnWriteArrayList<>();

        @Override
        public void saveSession(SessionRecord session) {
            operations.add("saveSession:sharedState.reviewMarker=" + session.sharedState().get("reviewMarker"));
        }

        @Override
        public void appendEvent(SessionEvent event) {
            operations.add("appendEvent:" + event.eventType().name());
            events.add(event);
        }

        @Override
        public void savePlaybookRun(PlaybookRun playbookRun) {
            playbookRuns.put(playbookRun.runId(), playbookRun);
        }

        List<SessionEvent> events() {
            return new ArrayList<>(events);
        }

        int firstOperationIndex(String operation) {
            return operations.indexOf(operation);
        }
    }
}
