package com.agentyard.worker.session;

import static org.junit.jupiter.api.Assertions.*;
import com.agentyard.contracts.agent.AgentContracts.*;
import com.agentyard.contracts.agent.ToolRunWorkflow;
import com.agentyard.contracts.runtime.RuntimeContracts.*;
import com.agentyard.worker.agent.ToolRunActivities;
import com.agentyard.worker.runtime.ExecutionPaused;
import io.temporal.failure.ApplicationFailure;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ToolRunWorkflowImplTest {
    @Test
    void keepsHumanReplyPendingDuringPauseThenResumesWithoutRepeatingNodes() throws Exception {
        var definition=ToolRunFixtures.flow(ToolRunFixtures.node("tool",PlaybookNodeType.TOOL_TASK),
            ToolRunFixtures.node("human",PlaybookNodeType.HUMAN_TASK),ToolRunFixtures.node("end",PlaybookNodeType.END));
        try(var harness=new Harness(definition)) {
            var workflow=harness.start("paused-run");
            var waiting=harness.activities.await("paused-run",ToolRunStatus.WAITING);
            workflow.setPaused(true);
            workflow.resume(new ToolRunResume("answer",(String)waiting.waiting().get("waitingId"),"human-1","reply",
                PlaybookResumeSource.HUMAN,Map.of("approved",true)));
            assertNull(harness.activities.changes.poll(150,TimeUnit.MILLISECONDS));
            assertTrue(harness.activities.acknowledged.isEmpty());
            assertEquals(ToolRunStatus.WAITING,harness.activities.last.get("paused-run").status());
            workflow.setPaused(false);
            harness.complete(workflow);
            assertEquals(ToolRunStatus.SUCCEEDED,harness.activities.last.get("paused-run").status());
            assertEquals(List.of("paused-run:tool:1"),harness.activities.executions);
            assertEquals(List.of("answer"),harness.activities.acknowledged);
        }
    }

    @Test
    void earlyPausePreventsRuntimeLoadAndCancellationDoesNotRequireResuming() throws Exception {
        var definition=ToolRunFixtures.flow(ToolRunFixtures.node("end",PlaybookNodeType.END));
        try(var harness=new Harness(definition)) {
            var workflow=harness.env.getWorkflowClient().newWorkflowStub(ToolRunWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue("tool-run-test").setWorkflowId("tool-run:early-pause").build());
            WorkflowStub.fromTyped(workflow).signalWithStart("setPaused",new Object[]{true},new Object[]{new ToolRunStart("early-pause")});
            assertNull(harness.activities.loads.poll(150,TimeUnit.MILLISECONDS));
            workflow.cancel();
            harness.complete(workflow);
            assertTrue(harness.activities.loads.isEmpty());
            assertEquals(ToolRunStatus.CANCELLED,harness.activities.last.get("early-pause").status());
        }
    }
    @Test
    void authorizationPauseWaitsForResumeAndRetriesOnlyTheUnstartedNode() throws Exception {
        var definition=ToolRunFixtures.flow(ToolRunFixtures.node("tool",PlaybookNodeType.TOOL_TASK),ToolRunFixtures.node("end",PlaybookNodeType.END));
        try(var harness=new Harness(definition)) {
            harness.activities.pauseFirstAuthorization=true;
            var workflow=harness.start("pause-race");
            assertNotNull(harness.activities.authorizations.poll(10,TimeUnit.SECONDS));
            workflow.setPaused(true);
            assertNull(harness.activities.authorizations.poll(200,TimeUnit.MILLISECONDS));
            assertTrue(harness.activities.executions.isEmpty());
            workflow.setPaused(false);harness.complete(workflow);
            assertEquals(2,harness.activities.authorizationCount.get());
            assertEquals(List.of("pause-race:tool:1"),harness.activities.executions);
            assertEquals(ToolRunStatus.SUCCEEDED,harness.activities.last.get("pause-race").status());
        }
    }

    @Test
    void resumeBeforeThePausedHttpResponseDoesNotLoseTheResumeSignal() throws Exception {
        var definition=ToolRunFixtures.flow(ToolRunFixtures.node("tool",PlaybookNodeType.TOOL_TASK),ToolRunFixtures.node("end",PlaybookNodeType.END));
        try(var harness=new Harness(definition)) {
            harness.activities.pauseFirstAuthorization=true;
            harness.activities.releaseAuthorization=new CountDownLatch(1);
            var workflow=harness.start("resume-race");
            assertNotNull(harness.activities.authorizations.poll(10,TimeUnit.SECONDS));
            workflow.setPaused(true);workflow.setPaused(false);
            harness.activities.releaseAuthorization.countDown();
            harness.complete(workflow);
            assertEquals(2,harness.activities.authorizationCount.get());
            assertEquals(List.of("resume-race:tool:1"),harness.activities.executions);
            assertEquals(ToolRunStatus.SUCCEEDED,harness.activities.last.get("resume-race").status());
        }
    }

    @Test
    void cancelStopsARuntimeLoadThatIsRetryingDuringAnOutage() throws Exception {
        var definition=ToolRunFixtures.flow(ToolRunFixtures.node("end",PlaybookNodeType.END));
        try(var harness=new Harness(definition)) {
            harness.activities.failLoad=true;
            var workflow=harness.start("load-outage");
            assertNotNull(harness.activities.loads.poll(10,TimeUnit.SECONDS));
            workflow.cancel();harness.complete(workflow);
            assertTrue(harness.activities.executions.isEmpty());
            assertEquals(ToolRunStatus.CANCELLED,harness.activities.last.get("load-outage").status());
        }
    }

    @Test
    void persistsHumanAndExternalWaitsWithCorrelatedExactlyOnceResume() throws Exception {
        var definition = ToolRunFixtures.flow(ToolRunFixtures.node("tool", PlaybookNodeType.TOOL_TASK),
            ToolRunFixtures.node("human", PlaybookNodeType.HUMAN_TASK),
            ToolRunFixtures.node("external", PlaybookNodeType.EXTERNAL_INTERACTION),
            ToolRunFixtures.node("end", PlaybookNodeType.END));
        try (var harness = new Harness(definition)) {
            var workflow = harness.start("run-1");
            var human = harness.activities.await("run-1", ToolRunStatus.WAITING);
            String humanId = (String) human.waiting().get("waitingId");
            workflow.resume(new ToolRunResume("wrong-source", humanId, "human-1", "message-0", PlaybookResumeSource.EXTERNAL_SYSTEM, Map.of()));
            workflow.resume(new ToolRunResume("wrong-wait", "previous-wait", "human-1", "message-0", PlaybookResumeSource.HUMAN, Map.of()));
            var answer = new ToolRunResume("answer-1", humanId, "human-1", "message-1", PlaybookResumeSource.HUMAN, Map.of("approved", true));
            workflow.resume(answer);
            var external = harness.activities.await("run-1", ToolRunStatus.WAITING);
            assertEquals("external", external.waiting().get("nodeKey"));
            assertNotEquals(humanId, external.waiting().get("waitingId"));
            workflow.resume(answer);
            workflow.resume(new ToolRunResume("callback-1", (String) external.waiting().get("waitingId"), "external-1", "message-2",
                PlaybookResumeSource.EXTERNAL_SYSTEM, Map.of("receipt", "receipt-1")));
            harness.complete(workflow);
            var result = harness.activities.last.get("run-1");
            assertEquals(ToolRunStatus.SUCCEEDED, result.status());
            assertEquals(List.of("answer-1", "callback-1"), harness.activities.acknowledged);
            assertEquals(List.of("run-1:tool:1"), harness.activities.executions);
            var lastResume = (Map<?, ?>) result.result().get("playbook.lastResume");
            assertEquals("message-2", lastResume.get("sourceMessageId"));
            assertEquals("external-1", lastResume.get("participantId"));
        }
    }

    @Test
    void allowsTwoWaitingToolRunsFromTheSameSessionAndCancelsOnlyOne() throws Exception {
        var definition = ToolRunFixtures.flow(ToolRunFixtures.node("human", PlaybookNodeType.HUMAN_TASK), ToolRunFixtures.node("end", PlaybookNodeType.END));
        try (var harness = new Harness(definition)) {
            var first = harness.start("run-1");
            var firstWait = harness.activities.await("run-1", ToolRunStatus.WAITING);
            var second = harness.start("run-2");
            var secondWait = harness.activities.await("run-2", ToolRunStatus.WAITING);
            first.cancel();
            first.resume(new ToolRunResume("late", (String) firstWait.waiting().get("waitingId"), "human-1", "m1", PlaybookResumeSource.HUMAN, Map.of()));
            second.resume(new ToolRunResume("answer", (String) secondWait.waiting().get("waitingId"), "human-1", "m2", PlaybookResumeSource.HUMAN, Map.of("ok", true)));
            harness.complete(first);
            harness.complete(second);
            assertEquals(ToolRunStatus.CANCELLED, harness.activities.last.get("run-1").status());
            assertEquals(ToolRunStatus.SUCCEEDED, harness.activities.last.get("run-2").status());
            assertEquals(List.of("answer"), harness.activities.acknowledged);
        }
    }

    @Test
    void preservesNodeFailureInsteadOfReplacingItWithMissingEndError() throws Exception {
        var definition = ToolRunFixtures.flow(ToolRunFixtures.node("failed", PlaybookNodeType.TOOL_TASK), ToolRunFixtures.node("end", PlaybookNodeType.END));
        try (var harness = new Harness(definition)) {
            harness.activities.failNode = true;
            var workflow = harness.start("run-1");
            harness.complete(workflow);
            assertEquals(ToolRunStatus.FAILED, harness.activities.last.get("run-1").status());
            assertEquals("current grant revoked", harness.activities.last.get("run-1").failureReason());
        }
    }

    @Test
    void validatesFinalResultBeforeReportingSuccess() throws Exception {
        var node = ToolRunFixtures.node("end", PlaybookNodeType.END);
        var definition = new PlaybookConfig("flow", "Flow", "", "{}",
            "{\"type\":\"object\",\"required\":[\"approved\"]}", null, true, true, "end", List.of(node), List.of());
        try (var harness = new Harness(definition)) {
            var workflow = harness.start("run-1");
            harness.complete(workflow);
            assertEquals(ToolRunStatus.FAILED, harness.activities.last.get("run-1").status());
            assertTrue(harness.activities.last.get("run-1").failureReason().contains("approved"));
        }
    }

    private static final class Harness implements AutoCloseable {
        final TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
        final RecordingActivities activities;
        Harness(PlaybookConfig definition) {
            activities = new RecordingActivities(definition);
            var worker = env.newWorker("tool-run-test");
            worker.registerWorkflowImplementationTypes(ToolRunWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
            env.start();
        }
        ToolRunWorkflow start(String id) {
            var workflow = env.getWorkflowClient().newWorkflowStub(ToolRunWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue("tool-run-test").setWorkflowId("tool-run:" + id).build());
            WorkflowClient.start(workflow::run, new ToolRunStart(id));
            return workflow;
        }
        void complete(ToolRunWorkflow workflow) throws Exception {
            WorkflowStub.fromTyped(workflow).getResult(10, TimeUnit.SECONDS, Void.class);
        }
        public void close() { env.close(); }
    }

    private static final class RecordingActivities implements ToolRunActivities, PlaybookNodeActivities {
        final PlaybookConfig definition;
        final ConcurrentHashMap<String, ToolRunProgress> last = new ConcurrentHashMap<>();
        final BlockingQueue<Progress> changes = new LinkedBlockingQueue<>();
        final List<String> acknowledged = new CopyOnWriteArrayList<>();
        final List<String> executions = new CopyOnWriteArrayList<>();
        final BlockingQueue<String> loads=new LinkedBlockingQueue<>();
        volatile boolean failNode;
        volatile boolean failLoad;
        volatile boolean pauseFirstAuthorization;
        volatile CountDownLatch releaseAuthorization;
        final AtomicInteger authorizationCount=new AtomicInteger();
        final BlockingQueue<String> authorizations=new LinkedBlockingQueue<>();
        RecordingActivities(PlaybookConfig definition) { this.definition = definition; }
        public ToolRunRuntime loadToolRun(String id) { loads.add(id); if(failLoad)throw new IllegalStateException("temporary control plane outage");return ToolRunFixtures.runtime(id, definition); }
        public void recordToolRunProgress(String id, ToolRunProgress progress) {
            last.put(id, progress);
            changes.add(new Progress(id, progress));
        }
        public void acknowledgeToolRunSignal(String id, String signalId) { acknowledged.add(signalId); }
        public PlaybookNodeExecutionResult executeStep(ToolRunNodeRequest request) { return executeTool(request); }
        public PlaybookNodeExecutionResult executeTool(ToolRunNodeRequest request) {
            int attempt=authorizationCount.incrementAndGet();authorizations.add(request.executionId());
            if(pauseFirstAuthorization&&attempt==1) {
                if(releaseAuthorization!=null) try { assertTrue(releaseAuthorization.await(10,TimeUnit.SECONDS)); }
                    catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
                throw ApplicationFailure.newNonRetryableFailure("paused before effects",ExecutionPaused.TYPE);
            }
            executions.add(request.executionId());
            return failNode ? new PlaybookNodeExecutionResult(Map.of(), null, PlaybookRunStatus.FAILED, "current grant revoked")
                : new PlaybookNodeExecutionResult(Map.of("toolResult", "record-1"), null, null, null);
        }
        ToolRunProgress await(String id, ToolRunStatus status) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            List<Progress> skipped = new ArrayList<>();
            try {
                while (System.nanoTime() < deadline) {
                    Progress event = changes.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (event == null) break;
                    if (event.id.equals(id) && event.progress.status() == status) return event.progress;
                    if (!event.id.equals(id)) skipped.add(event);
                }
                throw new AssertionError("missing progress " + id + " " + status + "; last=" + last);
            } finally { changes.addAll(skipped); }
        }
        record Progress(String id, ToolRunProgress progress) {}
    }
}
