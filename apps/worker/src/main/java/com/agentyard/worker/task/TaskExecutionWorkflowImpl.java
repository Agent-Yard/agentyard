package com.agentyard.worker.task;

import static com.agentyard.contracts.task.TaskContracts.*;
import com.agentyard.contracts.task.TaskExecutionWorkflow;
import com.agentyard.contracts.session.SessionContracts.*;
import com.agentyard.worker.session.PlaybookDefinition;
import com.agentyard.worker.session.PlaybookNodeActivities;
import com.agentyard.worker.session.PlaybookNodeActivities.PlaybookNodeExecutionRequest;
import com.agentyard.worker.session.JsonSchemaValidator;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** One durable execution, independent of all session workflows. Task state remains in the control plane. */
public class TaskExecutionWorkflowImpl implements TaskExecutionWorkflow {
    private final TaskExecutionActivities control=Workflow.newActivityStub(TaskExecutionActivities.class,ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(30)).setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build()).build());
    // External actions must not be repeated implicitly after an ambiguous timeout.
    private final TaskExecutionActivities runtime=Workflow.newActivityStub(TaskExecutionActivities.class,ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(16)).setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build()).build());
    private final PlaybookNodeActivities nodes=Workflow.newActivityStub(PlaybookNodeActivities.class,ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(10)).setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build()).build());
    private final JsonSchemaValidator validator=new JsonSchemaValidator(new ObjectMapper());
    private boolean cancelled; private ResumeSignal pending; private String waitingId; private String executionId;
    private Map<String,Object> result=Map.of();
    @Override public void run(ExecutionStart start) {
        executionId=start.executionId();
        try {
            var input=control.taskExecutionInput(executionId);
            if(cancelled) { progress(ExecutionStatus.CANCELLED,Map.of(),null); return; }
            progress(ExecutionStatus.RUNNING,Map.of(),null);
            if(input.execution().kind()==ExecutionKind.AGENT) runAgent(); else runPlaybook(input);
            progress(cancelled?ExecutionStatus.CANCELLED:ExecutionStatus.SUCCEEDED,Map.of(),null);
        } catch(Exception error) {
            progress(cancelled?ExecutionStatus.CANCELLED:ExecutionStatus.FAILED,Map.of(),error.getMessage()==null?"execution failed":error.getMessage());
        }
    }
    private void runAgent() {
        Map<String,Object> response=Map.of();
        for(int step=0;step<100 && !cancelled;step++) {
            control.taskExecutionInput(executionId); // Re-check current actor permissions before each external action.
            var outcome=runtime.taskAgentExecute(executionId,response);
            if(!outcome.success()) throw new IllegalStateException(outcome.failureReason());
            var decision=outcome.result().decision();
            result=new LinkedHashMap<>(); result.put("decision",decision); result.put("sharedState",outcome.result().sharedState()); result.put("llmUsage",outcome.llmUsage());
            if(decision.action()==AgentDecisionAction.SESSION_HUMAN_HANDOFF) {
                response=waitForInput(Map.of("type","HUMAN_TASK","prompt",Objects.toString(decision.operatorReason(),"Agent requests human input"),"output",result));
            } else if(decision.action()==AgentDecisionAction.REPLY) return;
            else throw new IllegalStateException("unsupported task execution outcome: "+decision.action());
        }
        if(!cancelled) throw new IllegalStateException("execution reached its 100 human-interaction limit; start a new execution to continue");
    }
    private void runPlaybook(ExecutionInput input) {
        var playbook=input.runtime().playbook(); var definition=new PlaybookDefinition(playbook);
        Map<String,Object> state=new LinkedHashMap<>(input.execution().input());
        state.remove("contextEntryIds");state.remove("contextEntries");state.remove("contextRevision");
        validator.validate(playbook.inputSchema(),state,"input");
        String key=playbook.entryNodeKey(); int visits=0;
        while(key!=null && !cancelled) {
            if(++visits>1000) throw new IllegalStateException("playbook exceeded its 1000 node execution limit");
            control.taskExecutionInput(executionId);
            PlaybookNode node=definition.requireNode(key); String route=null;
            switch(node.nodeType()) {
                case END -> {
                    Object configured=node.config().get("result"); result=configured instanceof Map<?,?> ? objectMap(configured):new LinkedHashMap<>(state);
                    String status=Objects.toString(node.config().get("status"),"SUCCEEDED");
                    if(!"SUCCEEDED".equals(status)) throw new IllegalStateException("FAILED".equals(status)?Objects.toString(node.config().get("failureReason"),"playbook failed"):"invalid END status: "+status);
                    validator.validate(playbook.resultSchema(),result,"result"); return;
                }
                case HUMAN_TASK, EXTERNAL_INTERACTION -> {
                    if(node.nodeType()==PlaybookNodeType.HUMAN_TASK&&!playbook.allowHumanTask() || node.nodeType()==PlaybookNodeType.EXTERNAL_INTERACTION&&!playbook.allowExternalInteraction()) throw new IllegalStateException("waiting node is disabled by playbook policy");
                    var wait=new LinkedHashMap<String,Object>(); wait.put("type",node.nodeType().name());wait.put("nodeKey",key);wait.put("prompt",node.nodeName());wait.put("config",node.config());
                    var response=waitForInput(wait); if(cancelled) return;
                    state.put("playbook.lastResume",Map.of("nodeKey",key,"resumeSource",node.nodeType()==PlaybookNodeType.HUMAN_TASK?"HUMAN":"EXTERNAL","payload",response)); route=Objects.toString(response.get("routeKey"),null);
                }
                case STEP, TOOL_TASK -> {
                    var config=new LinkedHashMap<>(node.config());
                    config.put("taskExecution",Map.of("id",executionId,"instruction",input.execution().instruction(),"context",input.context()));
                    var request=new PlaybookNodeExecutionRequest("task:"+input.execution().taskId(),executionId,playbook.playbookId(),key,node.nodeName(),input.runtime().agent(),node.scriptRef(),node.scriptVersion(),node.toolId(),node.toolOperation(),state,config);
                    var output=node.nodeType()==PlaybookNodeType.STEP?nodes.executeStep(request):nodes.executeTool(request);
                    state.putAll(output.statePatch());route=output.routeKey();
                    if(output.terminalStatus()==PlaybookRunStatus.FAILED || output.terminalStatus()==PlaybookRunStatus.CANCELLED) throw new IllegalStateException(Objects.toString(output.failureReason(),"node failed"));
                }
            }
            result=new LinkedHashMap<>(state); key=definition.nextNodeKey(key,route);
        }
        if(!cancelled) throw new IllegalStateException("playbook ended without END node");
    }
    private Map<String,Object> waitForInput(Map<String,Object> descriptor) {
        waitingId=Workflow.randomUUID().toString(); pending=null; var wait=new LinkedHashMap<>(descriptor);wait.put("id",waitingId);
        progress(ExecutionStatus.WAITING,wait,null);
        Workflow.await(()->cancelled || pending!=null);
        if(cancelled) return Map.of();
        var answer=pending;pending=null;waitingId=null;
        progress(ExecutionStatus.RUNNING,Map.of(),null);
        var response=new LinkedHashMap<>(answer.payload());response.put("actorId",answer.actorId());response.put("signalId",answer.signalId());return response;
    }
    private void progress(ExecutionStatus status,Map<String,Object> waiting,String failure) { control.taskExecutionProgress(executionId,new ExecutionProgress(status,result,waiting,failure)); }
    @SuppressWarnings("unchecked") private static Map<String,Object> objectMap(Object value) { return (Map<String,Object>)value; }
    @Override public void resume(ResumeSignal signal) { if(waitingId!=null && waitingId.equals(signal.waitingId()) && pending==null) pending=signal; }
    @Override public void cancel() { cancelled=true; }
}
