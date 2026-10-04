package com.agentyard.worker.task;

import static com.agentyard.contracts.task.TaskContracts.*;
import static com.agentyard.contracts.session.SessionContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import com.agentyard.contracts.task.TaskExecutionWorkflow;
import io.temporal.client.*;
import io.temporal.testing.TestWorkflowEnvironment;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class TaskExecutionWorkflowTest {
    @Test void agentExecutionCompletesWithoutSessionWorkflow() {
        try(var environment=TestWorkflowEnvironment.newInstance()) {
            var activities=new Activities(false);var workflow=start(environment,activities);workflow.run(new ExecutionStart("execution"));
            assertEquals(List.of(ExecutionStatus.RUNNING,ExecutionStatus.SUCCEEDED),activities.statuses);
            assertEquals(1,activities.agentCalls);assertTrue(activities.last.result().containsKey("decision"));
        }
    }
    @Test void humanWaitCorrelatesResponseAndContinuesIndependently() throws Exception {
        try(var environment=TestWorkflowEnvironment.newInstance()) {
            var activities=new Activities(true);var workflow=start(environment,activities);WorkflowClient.start(workflow::run,new ExecutionStart("execution"));
            var wait=activities.waits.poll(10,TimeUnit.SECONDS);assertNotNull(wait);
            workflow.resume(new ResumeSignal("wrong","wrong","human",Map.of("answer","wrong")));
            workflow.resume(new ResumeSignal("signal",wait.waiting().get("id").toString(),"human",Map.of("answer","verified")));
            WorkflowStub.fromTyped(workflow).getResult(10,TimeUnit.SECONDS,Void.class);
            assertEquals(2,activities.agentCalls);assertEquals("verified",activities.resume.get("answer"));
            assertEquals(ExecutionStatus.SUCCEEDED,activities.last.status());
        }
    }
    @Test void cancellationReleasesHumanWaitWithoutAnotherAgentCall() throws Exception {
        try(var environment=TestWorkflowEnvironment.newInstance()) {
            var activities=new Activities(true);var workflow=start(environment,activities);WorkflowClient.start(workflow::run,new ExecutionStart("execution"));
            assertNotNull(activities.waits.poll(10,TimeUnit.SECONDS));workflow.cancel();
            WorkflowStub.fromTyped(workflow).getResult(10,TimeUnit.SECONDS,Void.class);
            assertEquals(1,activities.agentCalls);assertEquals(ExecutionStatus.CANCELLED,activities.last.status());
        }
    }
    @Test void playbookResultUsesItsDeclaredSchemaWithoutInjectingTaskMetadata() {
        try(var environment=TestWorkflowEnvironment.newInstance()) {
            var activities=new Activities(false);activities.playbook=playbook(Map.of());
            start(environment,activities).run(new ExecutionStart("execution"));
            assertEquals(ExecutionStatus.SUCCEEDED,activities.last.status());assertEquals(Map.of(),activities.last.result());assertEquals(0,activities.agentCalls);
        }
    }
    @Test void invalidPlaybookEndStatusFailsInsteadOfReportingSuccess() {
        try(var environment=TestWorkflowEnvironment.newInstance()) {
            var activities=new Activities(false);activities.playbook=playbook(Map.of("status","CANCELLED"));
            start(environment,activities).run(new ExecutionStart("execution"));
            assertEquals(ExecutionStatus.FAILED,activities.last.status());assertTrue(activities.last.failureReason().contains("invalid END status"));
        }
    }
    private PlaybookConfig playbook(Map<String,Object> config) {
        return new PlaybookConfig("playbook","Validate","", "{\"type\":\"object\"}","{\"type\":\"object\",\"additionalProperties\":false}",new PlaybookExecutionPolicy(null,null),false,false,"finish",List.of(new PlaybookNode("finish","Finish",PlaybookNodeType.END,"",null,null,null,null,config,new PlaybookNodeLayout(0,0))),List.of());
    }
    private TaskExecutionWorkflow start(TestWorkflowEnvironment environment,Activities activities) {
        var worker=environment.newWorker("tasks");worker.registerWorkflowImplementationTypes(TaskExecutionWorkflowImpl.class);worker.registerActivitiesImplementations(activities);environment.start();
        return environment.getWorkflowClient().newWorkflowStub(TaskExecutionWorkflow.class,WorkflowOptions.newBuilder().setTaskQueue("tasks").build());
    }
    static class Activities implements TaskExecutionActivities {
        final boolean wait;volatile int agentCalls;volatile ExecutionProgress last;volatile Map<String,Object> resume;PlaybookConfig playbook;
        final List<ExecutionStatus> statuses=new CopyOnWriteArrayList<>();final BlockingQueue<ExecutionProgress> waits=new LinkedBlockingQueue<>();
        Activities(boolean wait){this.wait=wait;}
        public ExecutionInput taskExecutionInput(String id) {
            var now=Instant.now();var task=new Task("task",null,"Research","Goal",TaskStatus.ACTIVE,"",1,1,Map.of(),Policy.defaults(),"human",null,now,now);
            return new ExecutionInput(new Execution(id,"task","agent","human",playbook==null?ExecutionKind.AGENT:ExecutionKind.PLAYBOOK,ExecutionStatus.RUNNING,"Investigate",Map.of(),Map.of(),Map.of(),null,now,now),new RuntimeSnapshot("assistant","1",null,playbook),new TaskDetail(task,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),Set.of(Permission.VIEW)));
        }
        public Execution taskExecutionProgress(String id,ExecutionProgress progress){last=progress;statuses.add(progress.status());if(progress.status()==ExecutionStatus.WAITING)waits.add(progress);return taskExecutionInput(id).execution();}
        public AgentTurnExecutionOutcome taskAgentExecute(String id,Map<String,Object> response){agentCalls++;resume=response;var action=wait&&agentCalls==1?AgentDecisionAction.SESSION_HUMAN_HANDOFF:AgentDecisionAction.REPLY;return new AgentTurnExecutionOutcome(true,new AgentTurnResult(new AgentDecision(action,null,null,null,Map.of(),"Verify evidence"),Map.of(),null),null);}
    }
}
