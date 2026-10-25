package com.agentyard.platform.agent;

import static com.agentyard.contracts.agent.AgentContracts.*;
import static com.agentyard.contracts.task.TaskContracts.*;
import static com.agentyard.platform.agent.AgentAuthorization.*;
import static com.agentyard.platform.agent.AgentService.*;
import static com.agentyard.persistence.jooqsupport.JooqTimeSupport.*;

import com.agentyard.contracts.runtime.RuntimeContracts.PlaybookNodeType;
import com.agentyard.contracts.runtime.RuntimeContracts.PlaybookResumeSource;
import com.agentyard.persistence.agent.AgentStore;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ToolRunService {
    private final AgentService agents;
    private final CapabilityAccess capabilities;
    public ToolRunService(AgentService agents,CapabilityAccess capabilities) { this.agents=agents;this.capabilities=capabilities; }

    @Transactional
    public ToolRun start(String activationId,String requestId,String versionId,String taskId,Map<String,Object> input) {
        return agents.store().transaction(s->{
            var activation=agents.running(s,activationId);var session=s.session(activation.sessionId(),false).orElseThrow();
            return agents.mapper().convertValue(agents.idempotent(s,session.participantId(),requestId,"playbook.run",
                new ToolStart(versionId,taskId,input),ignored->{
                    var profile=agents.catalog().resolveRoleTemplateVersion(session.templateVersionId());
                    capabilities.authorize(s,session,profile,"PLAYBOOK",versionId,"run");
                    if(taskId!=null) agents.authorization().requireTask(s,session.participantId(),taskId,Permission.EXECUTE);
                    Instant now=Instant.now();
                    var run=new ToolRun(id(),session.id(),session.participantId(),activationId,taskId,versionId,ToolRunStatus.QUEUED,input,Map.of(),Map.of(),null,now,now);
                    s.toolRun(run);return run;
                }),ToolRun.class);
        });
    }
    private record ToolStart(String versionId,String taskId,Map<String,Object> input) {}
    public ToolRunRuntime runtime(String runId) {
        return agents.store().transaction(s->runtime(s,runId));
    }
    ToolRunRuntime runtime(AgentStore s,String runId) {
        var run=s.toolRun(runId,false).orElseThrow(()->notFound("ToolRun"));
        if(terminal(run.status())) throw denied("tool run is closed");
        var session=s.session(run.sessionId(),false).orElseThrow();
        agents.authorization().active(s,session.participantId());
        if(session.status()==SessionStatus.CLOSED) throw denied("Session is closed");
        if(session.status()==SessionStatus.PAUSED) throw paused();
        if(run.taskId()!=null) {
            agents.authorization().requireTask(s,run.participantId(),run.taskId(),Permission.EXECUTE);
            var task=s.tasks().task(run.taskId(),false).orElseThrow();
            if(Set.of(TaskStatus.CANCELLED,TaskStatus.COMPLETED).contains(task.status())) throw denied("Task is not active");
            if(task.status()==TaskStatus.PAUSED) throw paused();
        }
        var profile=agents.catalog().resolveRoleTemplateVersion(session.templateVersionId());
        var playbook=(com.agentyard.contracts.runtime.RuntimeContracts.PlaybookConfig)
            capabilities.authorize(s,session,profile,"PLAYBOOK",run.playbookVersionId(),"run");
        return new ToolRunRuntime(run,playbook,profile);
    }
    public ToolRunRuntime authorizeNode(String runId,ToolRunNodeAuthorization request) {
        required(request.executionId(),"executionId");
        var runtime=runtime(runId);var run=runtime.run();
        var node=runtime.playbook().nodes().stream().filter(n->n.nodeKey().equals(request.nodeKey())).findFirst().orElseThrow(()->notFound("node"));
        if(node.nodeType()==PlaybookNodeType.STEP) agents.authorization().require(agents.store(),run.participantId(),"sandbox.execute",ScopeKind.RESOURCE,run.playbookVersionId(),false);
        if(node.nodeType()==PlaybookNodeType.TOOL_TASK) {
            var session=agents.store().session(run.sessionId(),false).orElseThrow();
            capabilities.authorize(agents.store(),session,runtime.agent(),"TOOL",node.toolId(),node.toolOperation());
        }
        return runtime;
    }
    @Transactional
    public void progress(String runId,ToolRunProgress progress) {
        agents.store().transaction(s->{
            var previous=s.toolRun(runId,true).orElseThrow(()->notFound("ToolRun"));
            var table=com.agentyard.persistence.jooq.Tables.AGENT_TOOL_RUN;
            boolean realCompletion=progress.status()==ToolRunStatus.SUCCEEDED||progress.status()==ToolRunStatus.FAILED;
            if(previous.status()==ToolRunStatus.SUCCEEDED||previous.status()==ToolRunStatus.FAILED) return null;
            if(previous.status()==ToolRunStatus.CANCELLED) {
                // Stop intent wins, but do not erase a real outcome that arrives after cancellation.
                if(realCompletion && s.database().select(table.COMPLETION_RECEIVED_AT).from(table).where(table.ID.eq(runId)).fetchOne(table.COMPLETION_RECEIVED_AT)==null) {
                    s.progress(runId,stoppedProgress(progress,previous.failureReason()));
                    s.database().update(table).set(table.COMPLETION_RECEIVED_AT,toOffsetDateTime(Instant.now())).where(table.ID.eq(runId)).execute();
                }
                return null;
            }
            if(progress.status()==ToolRunStatus.QUEUED) throw new IllegalArgumentException("progress cannot return to queued");
            if(progress.status()==ToolRunStatus.WAITING) required(Objects.toString(progress.waiting().get("waitingId"),null),"waitingId");
            // Serialize completion with instance/work controls and Task permission changes.
            var session=s.session(previous.sessionId(),true).orElseThrow();
            if(previous.taskId()!=null) s.tasks().task(previous.taskId(),true).orElseThrow();
            s.database().select(com.agentyard.persistence.jooq.Tables.COLLABORATION_ACTOR.ID)
                .from(com.agentyard.persistence.jooq.Tables.COLLABORATION_ACTOR)
                .where(com.agentyard.persistence.jooq.Tables.COLLABORATION_ACTOR.ID.eq(session.participantId())).forShare().fetch();
            boolean stopped=ExecutionControl.resolve(s,agents.authorization(),session.id(),previous.taskId(),Permission.EXECUTE)==ExecutionControl.CLOSED;
            boolean waitingChanged=progress.status()==ToolRunStatus.WAITING && !progress.waiting().equals(previous.waiting());
            var accepted=stopped ? stoppedProgress(progress,"work, instance or execution authority closed") : progress;
            s.progress(runId,accepted);
            if(realCompletion) s.database().update(table).set(table.COMPLETION_RECEIVED_AT,toOffsetDateTime(Instant.now())).where(table.ID.eq(runId)).execute();
            if(!stopped&&(terminal(progress.status())||waitingChanged)) {
                // The result lives on ToolRun; a bounded pointer event survives pause and cannot fail on large outputs.
                Map<String,Object> body=Map.of("text",waitingChanged?"The workflow requires a response. Read its current waiting point.":"The workflow returned a result. Read its execution receipt.",
                    "toolRunId",runId,"status",progress.status(),"waitingId",Objects.toString(progress.waiting().get("waitingId"),""));
                agents.send(s,session.participantId(),new SendMessage(runId+":"+progress.status()+":"+previous.updatedAt(),session.participantId(),
                    previous.taskId(),null,MessageKind.EVENT,body));
            }
            return null;
        });
    }
    @Transactional
    public ToolRunResume resume(String executor,String decisionMaker,String sourceMessageId,String runId,String waitingId,Map<String,Object> payload) {
        return agents.store().transaction(s->{
            var run=s.toolRun(runId,true).orElseThrow(()->notFound("ToolRun"));
            if(run.status()!=ToolRunStatus.WAITING||!waitingId.equals(run.waiting().get("waitingId"))) throw new IllegalArgumentException("this waiting point is no longer active");
            if(ExecutionControl.resolve(s,agents.authorization(),run.sessionId(),run.taskId(),Permission.EXECUTE)==ExecutionControl.CLOSED)
                throw denied("the waiting work is closed or no longer authorized");
            var session=s.session(run.sessionId(),false).orElseThrow();
            if(!run.participantId().equals(executor)&&!session.createdBy().equals(executor)) {
                if(run.taskId()==null) throw denied("tool run is private");
                agents.authorization().requireTask(s,executor,run.taskId(),Permission.DECIDE);
            }
            PlaybookResumeSource source=PlaybookResumeSource.valueOf(Objects.toString(run.waiting().get("source"),"HUMAN"));
            if(source==PlaybookResumeSource.EXTERNAL_SYSTEM) throw denied("external results must arrive through an authenticated external callback");
            var decider=agents.authorization().active(s,decisionMaker);
            if(decider.kind()!=ActorKind.HUMAN) throw denied("a human waiting point requires an actual human response");
            if(sourceMessageId==null) throw new IllegalArgumentException("human response source is required");
            var message=s.message(sourceMessageId).orElseThrow(()->notFound("source message"));
            if(!message.senderId().equals(decisionMaker)||!message.recipientId().equals(executor)) throw denied("response source does not match the participants");
            if(run.taskId()!=null) agents.authorization().requireTask(s,decisionMaker,run.taskId(),Permission.DECIDE);
            return persistSignal(s,runId,waitingId,decisionMaker,sourceMessageId,source,payload);
        });
    }
    /** Caller holds the run row lock and has verified the actual decision/callback source. */
    ToolRunResume persistSignal(AgentStore s,String runId,String waitingId,String participantId,String sourceMessageId,
        PlaybookResumeSource source,Map<String,Object> payload) {
        var t=com.agentyard.persistence.jooq.Tables.AGENT_TOOL_RUN_SIGNAL;
        var old=s.database().selectFrom(t).where(t.TOOL_RUN_ID.eq(runId).and(t.WAITING_ID.eq(waitingId))).fetchOne();
        if(old!=null) {
            if(!Objects.equals(old.getParticipantId(),participantId)||!Objects.equals(old.getSourceMessageId(),sourceMessageId)
                ||!old.getSource().equals(source.name())||!s.json().readObjectMap(old.getPayload()).equals(payload))
                throw new IllegalArgumentException("waiting point already received another response");
            return new ToolRunResume(old.getId(),waitingId,old.getParticipantId(),old.getSourceMessageId(),source,payload);
        }
        var signal=new ToolRunResume(id(),waitingId,participantId,sourceMessageId,source,payload);
        s.database().insertInto(t).set(t.ID,signal.signalId()).set(t.TOOL_RUN_ID,runId).set(t.WAITING_ID,waitingId)
            .set(t.PARTICIPANT_ID,participantId).set(t.SOURCE_MESSAGE_ID,sourceMessageId).set(t.SOURCE,source.name())
            .set(t.PAYLOAD,s.json().toJsonb(payload)).set(t.CREATED_AT,toOffsetDateTime(Instant.now())).execute();
        return signal;
    }
    public void acknowledge(String runId,String signalId) {
        var t=com.agentyard.persistence.jooq.Tables.AGENT_TOOL_RUN_SIGNAL;
        agents.store().database().update(t).set(t.DELIVERED_AT,toOffsetDateTime(Instant.now())).where(t.ID.eq(signalId).and(t.TOOL_RUN_ID.eq(runId))).execute();
    }
    private static ToolRunProgress stoppedProgress(ToolRunProgress receipt,String reason) {
        String detail=Objects.toString(reason,"tool invocation cancelled")+"; execution reported "+receipt.status();
        if(receipt.failureReason()!=null) detail+=" ("+receipt.failureReason()+")";
        return new ToolRunProgress(ToolRunStatus.CANCELLED,receipt.result(),receipt.waiting(),detail);
    }
    private static ResponseStatusException paused() {return new ResponseStatusException(HttpStatus.LOCKED,"EXECUTION_PAUSED");}
    static boolean terminal(ToolRunStatus status) { return Set.of(ToolRunStatus.SUCCEEDED,ToolRunStatus.FAILED,ToolRunStatus.CANCELLED).contains(status); }
}
