package com.agentyard.platform.agent;

import static com.agentyard.contracts.agent.AgentContracts.*;
import static com.agentyard.contracts.task.TaskContracts.*;
import static com.agentyard.persistence.jooq.Tables.*;
import static com.agentyard.persistence.jooqsupport.JooqTimeSupport.*;

import com.agentyard.contracts.runtime.RuntimeContracts.PlaybookResumeSource;
import com.agentyard.persistence.agent.AgentStore;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Database outboxes survive API/worker restarts. Temporal starts and signals are safe to redeliver. */
@Component
public class AgentDispatcher {
    private static final Logger log=LoggerFactory.getLogger(AgentDispatcher.class);
    private final AgentService agents;
    private final AgentWorkflowGateway workflows;
    private String activationControlCursor="";
    private String toolControlCursor="";
    @Value("${agentyard.agent.max-concurrent-activations:32}")
    private int maxConcurrentActivations=32;
    @Autowired
    public AgentDispatcher(AgentService agents,WorkflowClient workflows,@Value("${agentyard.temporal.task-queue}") String queue) {
        this(agents,new AgentWorkflowGateway.Temporal(workflows,queue));
    }
    AgentDispatcher(AgentService agents,AgentWorkflowGateway workflows) {this.agents=agents;this.workflows=workflows;}
    @Scheduled(fixedDelayString="${agentyard.agent.dispatch-interval-ms:500}")
    public void dispatch() {
        phase("control",this::reconcileControl);
        phase("schedules",this::schedules);
        phase("resource events",this::resourceEvents);
        phase("subscriptions",this::events);
        phase("claim",()->agents.store().transaction(s->{
            s.lock("agent-activation-capacity");
            int running=s.database().fetchCount(AGENT_ACTIVATION,AGENT_ACTIVATION.STATUS.eq("RUNNING"));
            int available=Math.max(1,maxConcurrentActivations)-running;
            if(available>0)s.claimActivations(Math.min(50,available));return null;
        }));
        phase("activations",this::activations);
        phase("tool runs",this::toolRuns);
        phase("signals",this::signals);
    }
    private void phase(String name,Runnable action) {
        try {action.run();} catch(RuntimeException failure) {
            // No request body, prompt, credential or downstream response is logged.
            log.warn("Agent dispatch phase {} will retry ({})",name,failure.getClass().getSimpleName());
        }
    }
    public static String activationWorkflowId(String id) {return "activation:"+id;}
    public static String toolRunWorkflowId(String id) {return "tool-run:"+id;}
    private void activations() {
        for(String id:agents.store().pendingActivations()) phase("activation "+id,()->agents.store().transaction(s->{
            // Startup and cancellation share this row lock, including their Temporal acknowledgement.
            var activation=s.activation(id,true).orElseThrow();
            var row=s.database().selectFrom(AGENT_ACTIVATION).where(AGENT_ACTIVATION.ID.eq(id)).fetchOne();
            var message=s.message(activation.inputMessageId()).orElseThrow();
            if(activation.status()!=ActivationStatus.RUNNING || row.getDispatchedAt()!=null
                || !control(s,activation.sessionId(),message.taskId()).equals("ACTIVE")) return null;
            try {
                workflows.startActivation(id);
            } catch(WorkflowExecutionAlreadyStarted alreadyStarted) { /* Database acknowledgement was lost. */ }
            s.activationDispatched(id);return null;
        }));
    }
    private void toolRuns() {
        var t=AGENT_TOOL_RUN;
        var ids=agents.store().database().select(t.ID).from(t).where(t.STATUS.eq("QUEUED").and(t.DISPATCHED_AT.isNull()).and(notPaused(t.SESSION_ID,t.TASK_ID)))
            .orderBy(t.CREATED_AT,t.ID).limit(50).fetch(t.ID);
        for(String id:ids) phase("tool run "+id,()->agents.store().transaction(s->{
            var run=s.toolRun(id,true).orElseThrow();
            if(run.status()!=ToolRunStatus.QUEUED
                || s.database().select(t.DISPATCHED_AT).from(t).where(t.ID.eq(id)).fetchOne(t.DISPATCHED_AT)!=null
                || toolControl(s,run)!=ExecutionControl.ACTIVE) return null;
            try {
                workflows.startToolRun(id);
            } catch(WorkflowExecutionAlreadyStarted alreadyStarted) { /* Durable deduplication. */ }
            s.database().update(t).set(t.DISPATCHED_AT,toOffsetDateTime(Instant.now())).where(t.ID.eq(id)).execute();return null;
        }));
    }
    private void signals() {
        var t=AGENT_TOOL_RUN_SIGNAL;var r=AGENT_TOOL_RUN;
        var pending=agents.store().database().select(t.fields()).from(t).join(r).on(r.ID.eq(t.TOOL_RUN_ID))
            .where(t.DELIVERED_AT.isNull().and(r.DISPATCHED_AT.isNotNull()).and(r.STATUS.notIn("SUCCEEDED","FAILED","CANCELLED")).and(notPaused(r.SESSION_ID,r.TASK_ID)))
            .orderBy(t.CREATED_AT,t.ID).limit(50).fetchInto(t);
        for(var signal:pending) phase("signal "+signal.getId(),()->{
            var run=agents.store().toolRun(signal.getToolRunId(),false).orElseThrow();
            if(toolControl(agents.store(),run)!=ExecutionControl.ACTIVE) return;
            var payload=new ToolRunResume(signal.getId(),signal.getWaitingId(),signal.getParticipantId(),signal.getSourceMessageId(),
                PlaybookResumeSource.valueOf(signal.getSource()),agents.store().json().readObjectMap(signal.getPayload()));
            workflows.resumeToolRun(run.id(),payload);
            // The worker acknowledges after its workflow accepts the signal. Lost ACKs are redelivered by ID.
        });
    }
    private String control(AgentStore s,String sessionId,String taskId) {
        return ExecutionControl.resolve(s,agents.authorization(),sessionId,taskId,Permission.VIEW).name();
    }
    private ExecutionControl toolControl(AgentStore s,ToolRun run) {
        return ExecutionControl.resolve(s,agents.authorization(),run.sessionId(),run.taskId(),Permission.EXECUTE);
    }
    /** Exclude parked work before LIMIT, so paused work cannot consume the entire dispatch batch. */
    private Condition notPaused(Field<String> sessionId,Field<String> taskId) {
        var db=agents.store().database();
        return DSL.notExists(db.selectOne().from(AGENT_SESSION).where(AGENT_SESSION.ID.eq(sessionId).and(AGENT_SESSION.STATUS.eq("PAUSED"))))
            .and(DSL.notExists(db.selectOne().from(COLLABORATION_TASK).where(COLLABORATION_TASK.ID.eq(taskId).and(COLLABORATION_TASK.STATUS.eq("PAUSED")))));
    }
    /** Stable keyset rotation checks every live row, including long-lived unchanged waits. */
    private List<String> controlBatch(Table<?> table,Field<String> id,Condition eligible,String cursor) {
        var db=agents.store().database();
        var rows=db.select(id).from(table).where(eligible.and(id.gt(cursor))).orderBy(id).limit(200).fetch(id);
        return rows.isEmpty()&&!cursor.isEmpty() ? db.select(id).from(table).where(eligible).orderBy(id).limit(200).fetch(id) : rows;
    }
    private void reconcileControl() {
        var a=AGENT_ACTIVATION;var t=AGENT_TOOL_RUN;
        var ids=controlBatch(a,a.ID,a.STATUS.in("QUEUED","RUNNING")
            .or(a.STATUS.eq("CANCELLED").and(a.CANCELLATION_DELIVERED_AT.isNull())),activationControlCursor);
        if(!ids.isEmpty()) activationControlCursor=ids.getLast();
        for(String id:ids) phase("activation control "+id,()->{
            agents.store().transaction(s->{
                var activation=s.activation(id,true).orElseThrow();
                var message=s.message(activation.inputMessageId()).orElseThrow();
                String desired=control(s,activation.sessionId(),message.taskId());
                var session=s.session(activation.sessionId(),false).orElseThrow();
                boolean taskJustCompleted=message.taskId()!=null&&session.status()==SessionStatus.ACTIVE
                    &&s.tasks().actor(session.participantId()).filter(Actor::active).isPresent()
                    &&s.tasks().task(message.taskId(),false).map(task->task.status()==TaskStatus.COMPLETED).orElse(false)
                    &&agents.authorization().taskPermissions(s,session.participantId(),message.taskId()).contains(Permission.VIEW)
                    &&s.database().select(a.DISPATCHED_AT).from(a).where(a.ID.eq(id)).fetchOne(a.DISPATCHED_AT)!=null;
                if(desired.equals("CLOSED")||desired.equals("PAUSED")&&activation.status()==ActivationStatus.RUNNING) {
                    // A bounded turn that just confirmed a result may finish its public acknowledgement.
                    // Live tool authorization already rejects further calls against completed work.
                    if(Set.of(ActivationStatus.QUEUED,ActivationStatus.RUNNING).contains(activation.status())
                        &&!(taskJustCompleted&&activation.status()==ActivationStatus.RUNNING))
                        s.complete(id,ActivationStatus.CANCELLED,new ActivationOutcome(false,null,Map.of(),"execution stopped by work or instance control",List.of(),null,null, null, false));
                }
                return null;
            });
            // Stop intent commits before transport; a lost cancel ACK only retries delivery.
            agents.store().transaction(s->{
                s.activation(id,true).orElseThrow();
                var row=s.database().selectFrom(a).where(a.ID.eq(id)).fetchOne();
                if(row!=null&&row.getStatus().equals("CANCELLED")&&row.getCancellationDeliveredAt()==null) {
                    // A start may have committed in Temporal even if its acknowledgement never reached SQL.
                    try {workflows.cancelActivation(id);}
                        catch(WorkflowNotFoundException gone) { /* The workflow is already gone. */ }
                    s.database().update(a).set(a.CANCELLATION_DELIVERED_AT,toOffsetDateTime(Instant.now())).where(a.ID.eq(id)).execute();
                }
                return null;
            });
        });
        var runs=controlBatch(t,t.ID,t.STATUS.notIn("SUCCEEDED","FAILED")
            .and(t.STATUS.ne("CANCELLED").or(t.CANCELLATION_DELIVERED_AT.isNull())),toolControlCursor);
        if(!runs.isEmpty()) toolControlCursor=runs.getLast();
        for(String id:runs) phase("tool control "+id,()->{
            agents.store().transaction(s->{
                var run=s.toolRun(id,true).orElseThrow();
                if(!ToolRunService.terminal(run.status())) {
                    String desired=toolControl(s,run).name();
                    if(desired.equals("CLOSED")) s.progress(id,new ToolRunProgress(ToolRunStatus.CANCELLED,run.result(),run.waiting(),"work or instance closed"));
                    else {
                        var row=s.database().selectFrom(t).where(t.ID.eq(id)).fetchOne();
                        if(row.getDispatchedAt()!=null&&!desired.equals(row.getDispatchedControl()))
                            // Commit intent before RPC. An accepted signal with a lost ACK must never look synchronized.
                            s.database().update(t).set(t.DISPATCHED_CONTROL,"PENDING").where(t.ID.eq(id)).execute();
                    }
                }
                return null;
            });
            agents.store().transaction(s->{
                var run=s.toolRun(id,true).orElseThrow();
                var row=s.database().selectFrom(t).where(t.ID.eq(id)).fetchOne();
                if(run.status()==ToolRunStatus.CANCELLED) {
                    if(row.getCancellationDeliveredAt()==null) {
                        try {workflows.cancelToolRun(id);}
                            catch(WorkflowNotFoundException gone) { /* No execution remains. */ }
                        s.database().update(t).set(t.CANCELLATION_DELIVERED_AT,toOffsetDateTime(Instant.now())).where(t.ID.eq(id)).execute();
                    }
                } else if(!ToolRunService.terminal(run.status())) {
                    String desired=toolControl(s,run).name();
                    if(!desired.equals("CLOSED")&&row.getDispatchedAt()!=null&&"PENDING".equals(row.getDispatchedControl())) {
                        workflows.pauseToolRun(id,desired.equals("PAUSED"));
                        s.database().update(t).set(t.DISPATCHED_CONTROL,desired).where(t.ID.eq(id)).execute();
                    }
                }
                return null;
            });
        });
    }
    private void schedules() {
        agents.store().transaction(s->{
            var t=AGENT_SCHEDULE;var now=Instant.now();
            var due=s.database().selectFrom(t).where(t.ACTIVE.isTrue().and(t.DUE_AT.le(toOffsetDateTime(now))).and(notPaused(t.SESSION_ID,t.TASK_ID)))
                .orderBy(t.DUE_AT,t.ID).limit(50).forUpdate().skipLocked().fetch();
            for(var row:due) {
                var schedule=s.schedule(row.getId()).orElseThrow();var session=s.session(schedule.sessionId(),false).orElseThrow();
                String desired=control(s,session.id(),schedule.taskId());
                if(desired.equals("PAUSED")) continue;
                boolean allowed=desired.equals("ACTIVE")&&agents.authorization().allowed(s,session.participantId(),"schedule.create",ScopeKind.SESSION,session.id());
                if(allowed&&schedule.taskId()!=null) allowed=agents.authorization().taskPermissions(s,session.participantId(),schedule.taskId()).contains(Permission.EXECUTE);
                if(allowed) agents.send(s,session.participantId(),new SendMessage("schedule:"+schedule.id()+":"+schedule.dueAt(),session.participantId(),
                    schedule.taskId(),null,MessageKind.EVENT,Map.of("text",schedule.instruction(),"scheduleId",schedule.id(),"scheduledAt",schedule.dueAt().toString())));
                Instant next=schedule.dueAt();boolean active=allowed&&schedule.intervalSeconds()!=null;
                // Coalesce missed recurring ticks into one wake-up instead of flooding an offline instance.
                if(active) {long intervals=Math.max(1,java.time.Duration.between(next,now).getSeconds()/schedule.intervalSeconds()+1);next=next.plusSeconds(Math.multiplyExact(intervals,schedule.intervalSeconds()));}
                s.schedule(new Schedule(schedule.id(),session.id(),schedule.taskId(),next,schedule.instruction(),schedule.intervalSeconds(),active,schedule.createdBy(),schedule.createdAt()));
            }
            return null;
        });
    }
    private void events() {
        agents.store().transaction(s->{
            var e=COLLABORATION_EVENT_OUTBOX;var sub=AGENT_SUBSCRIPTION;
            var pending=s.database().selectFrom(e).where(e.DELIVERED_AT.isNull()).orderBy(e.CREATED_AT,e.ID).limit(50).forUpdate().skipLocked().fetch();
            for(var event:pending) {
                var payload=s.json().readObjectMap(event.getPayload());
                var notified=new HashSet<String>();
                if(event.getScopeKind().equals("TASK")&&event.getKind().equals("REQUEST_CHANGES")
                    &&payload.get("notifyParticipantId") instanceof String responsible&&!responsible.equals(event.getActorId())) {
                    var recipient=s.participantSession(responsible);
                    if(recipient.isPresent()&&!control(s,recipient.get().id(),event.getScopeId()).equals("CLOSED")) {
                        agents.send(s,responsible,new SendMessage("decision:"+event.getId(),responsible,event.getScopeId(),null,MessageKind.EVENT,
                            Map.of("text","Changes were requested on submitted work. Read the decision and current work context before continuing.",
                                "scopeKind","TASK","scopeId",event.getScopeId(),"eventId",event.getId(),"kind",event.getKind(),
                                "decisionId",Objects.toString(payload.get("decisionId"),""))));
                        notified.add(responsible);
                    }
                }
                if(event.getScopeKind().equals("TASK")&&"PAUSED".equals(payload.get("previousStatus"))&&"ACTIVE".equals(payload.get("status"))) {
                    for(var link:s.tasks().sessions(event.getScopeId())) {
                        var session=s.session(link.sessionId(),false).orElseThrow();
                        if(!Objects.equals(session.participantId(),event.getActorId())&&control(s,session.id(),event.getScopeId()).equals("ACTIVE")) {
                            agents.send(s,session.participantId(),new SendMessage("resume:"+event.getId()+":"+session.id(),session.participantId(),event.getScopeId(),null,MessageKind.CONTROL,
                                Map.of("text","The work was explicitly resumed. Read current context and check interrupted tool receipts before continuing.","eventId",event.getId())));
                            notified.add(session.participantId());
                        }
                    }
                }
                var subscriptions=s.database().select(sub.ID).from(sub).where(sub.ACTIVE.isTrue().and(sub.SCOPE_KIND.eq(event.getScopeKind())).and(sub.SCOPE_ID.eq(event.getScopeId()))
                    .and(sub.CREATED_AT.le(event.getCreatedAt())))
                    .fetch(sub.ID);
                for(String id:subscriptions) {
                    var subscription=s.subscription(id).orElseThrow();
                    if(!subscription.eventKinds().contains("*")&&!subscription.eventKinds().contains(event.getKind())) continue;
                    var session=s.session(subscription.sessionId(),false).orElseThrow();
                    if(session.status()==SessionStatus.CLOSED||Objects.equals(session.participantId(),event.getActorId())||notified.contains(session.participantId())) continue;
                    if(!agents.authorization().allowed(s,session.participantId(),"subscription.create",ScopeKind.SESSION,session.id())) continue;
                    if(!eventVisible(s,session.participantId(),event.getScopeKind(),event.getScopeId(),s.json().readObjectMap(event.getPayload()))) continue;
                    agents.send(s,session.participantId(),new SendMessage("event:"+event.getId()+":"+id,session.participantId(),null,null,MessageKind.EVENT,
                        Map.of("text","A subscribed work item changed. Read its current authorized context before acting.","scopeKind",event.getScopeKind(),
                            "scopeId",event.getScopeId(),"eventId",event.getId(),"kind",event.getKind(),"change",s.json().readObjectMap(event.getPayload()))));
                    notified.add(session.participantId());
                }
                s.database().update(e).set(e.DELIVERED_AT,toOffsetDateTime(Instant.now())).where(e.ID.eq(event.getId())).execute();
            }
            return null;
        });
    }
    private void resourceEvents() {
        agents.store().database().execute("""
            insert into collaboration_event_outbox(id,scope_kind,scope_id,kind,payload,created_at)
            select p.id,case when p.aggregate_type='ROLE_TEMPLATE' then 'TEMPLATE' else 'RESOURCE' end,
                p.aggregate_id,p.event_type,jsonb_build_object('aggregateType',p.aggregate_type),p.occurred_at
            from platform_event p
            where p.aggregate_type in ('RESOURCE','KNOWLEDGE_BASE','PLAYBOOK','ROLE_TEMPLATE')
                and not exists(select 1 from collaboration_event_outbox e where e.id=p.id)
            order by p.occurred_at,p.id limit 100 on conflict do nothing
            """);
    }
    private boolean eventVisible(AgentStore s,String actor,String kind,String scopeId,Map<String,Object> payload) {
        if(!kind.equals("TASK")) return agents.authorization().allowed(s,actor,"resource.read",ScopeKind.valueOf(kind),scopeId);
        try {
            if(!agents.authorization().taskPermissions(s,actor,scopeId).contains(Permission.VIEW)) return false;
            long revision=((Number)payload.getOrDefault("revision",0)).longValue();
            var roles=s.tasks().participants(scopeId).stream().filter(p->p.actorId().equals(actor)).findFirst().map(Participant::roles).orElse(List.of());
            return s.tasks().events(scopeId,actor,Set.copyOf(roles),revision-1,1).stream().anyMatch(event->event.revision()==revision);
        } catch(ResponseStatusException inaccessible) {return false;}
    }
}
