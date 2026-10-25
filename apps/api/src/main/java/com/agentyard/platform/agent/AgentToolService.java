package com.agentyard.platform.agent;

import static com.agentyard.contracts.agent.AgentContracts.*;
import static com.agentyard.contracts.task.TaskContracts.*;
import static com.agentyard.platform.agent.AgentAuthorization.*;
import static com.agentyard.platform.agent.AgentService.*;

import com.agentyard.persistence.agent.AgentStore;
import com.agentyard.platform.task.TaskCommandService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Model arguments never select the caller; a live persisted Activation supplies it. */
@Service
public class AgentToolService {
    private final AgentService agents;
    private final TaskCommandService tasks;
    private final ToolRunService toolRuns;
    private final CapabilityAccess capabilities;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    public AgentToolService(AgentService agents,TaskCommandService tasks,ToolRunService toolRuns,CapabilityAccess capabilities,
        org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.agents=agents;this.tasks=tasks;this.toolRuns=toolRuns;this.capabilities=capabilities;
        this.transactions=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }
    public ToolResult invoke(String activationId,ToolInvocation invocation) {
        try {
            return transactions.execute(transaction -> agents.store().transaction(s->{
                var activation=agents.running(s,activationId);var session=s.session(activation.sessionId(),false).orElseThrow();
                required(invocation.callId(),"callId");required(invocation.name(),"name");
                Object result;
                // Authorization is deliberately re-evaluated even for a previously seen resource call ID.
                if(invocation.name().equals("resource_authorize")) result=resourceAuthorization(s,activation,session,invocation);
                else result=agents.idempotent(s,session.participantId(),activationId+":"+invocation.callId(),invocation.name(),invocation,
                    ignored->execute(s,activation,session,invocation));
                return new ToolResult(true,result,null,null);
            }));
        } catch(ResponseStatusException error) {
            return new ToolResult(false,null,"HTTP_"+error.getStatusCode().value(),error.getReason());
        } catch(IllegalArgumentException|java.util.NoSuchElementException error) {
            return new ToolResult(false,null,"INVALID_REQUEST",error.getMessage());
        }
    }
    private Object execute(AgentStore s,Activation activation,Session session,ToolInvocation call) {
        Map<String,Object> a=call.arguments();String actor=session.participantId();String requestId=activation.id()+":"+call.callId()+":effect";
        return switch(call.name()) {
            case "task_list" -> agents.collaboration().tasks(actor,string(a,"cursor"),null,string(a,"organizationId"),string(a,"query"),limit(a));
            case "task_read" -> {
                String taskId=text(a,"taskId");
                yield Map.of("detail",agents.collaboration().summary(taskId,actor),"context",agents.collaboration().context(taskId,actor,string(a,"cursor"),limit(a)),
                    "decisions",agents.collaboration().decisions(taskId,actor,string(a,"decisionCursor"),limit(a)));
            }
            case "task_create" -> {
                agents.authorization().require(s,actor,"task.create",ScopeKind.PLATFORM,"*",false);
                var authority=authority(s,activation,session,string(a,"sourceMessageId"));
                var task=agents.collaboration().createTask(authority,new CreateTask(string(a,"organizationId"),text(a,"title"),text(a,"goal"),map(a.get("attributes")),null));
                s.tasks().session(new SessionLink(task.id(),session.id(),actor,Instant.now()),false);
                yield task;
            }
            case "task_command" -> {
                String taskId=text(a,"taskId");var authority=authority(s,activation,session,string(a,"sourceMessageId"));
                if(authority.sourceMessageId()!=null) {
                    var source=s.message(authority.sourceMessageId()).orElseThrow();
                    if(source.taskId()!=null&&!source.taskId().equals(taskId)) throw denied("decision source belongs to different work");
                }
                yield tasks.command(taskId,authority,new Command(requestId,number(a,"expectedRevision"),CommandKind.valueOf(text(a,"kind")),map(a.get("data"))));
            }
            case "participant_list" -> participants(s,actor,a);
            case "template_list" -> agents.catalog().listRoleTemplates().stream().filter(t->agents.authorization().allowed(s,actor,"agent.create",ScopeKind.TEMPLATE,t.id()))
                .filter(t->matchesQuery(t.name(),a)).map(t->Map.of("id",t.id(),"name",t.name(),"description",t.description(),
                    "versions",t.versions().stream().filter(v->v.status()==com.agentyard.contracts.runtime.WorkflowContracts.VersionStatus.PUBLISHED).toList())).toList();
            case "agent_create" -> agents.createAs(actor,new CreateSession(requestId,text(a,"templateVersionId"),string(a,"name"),string(a,"taskId"),text(a,"instruction"),
                list(a.get("grants")).stream().map(g->agents.mapper().convertValue(g,GrantRequest.class)).toList(),
                list(a.get("taskRoles")).stream().map(Object::toString).toList()));
            case "message_send" -> agents.sendFromActivation(s,activation,session,new SendMessage(requestId,text(a,"recipientId"),string(a,"taskId"),string(a,"replyToMessageId"),
                MessageKind.valueOf(text(a,"kind")),map(a.get("body"))));
            case "grant_create" -> agents.delegate(actor,text(a,"participantId"),new GrantRequest(text(a,"operation"),ScopeKind.valueOf(text(a,"scopeKind")),
                text(a,"scopeId"),Boolean.TRUE.equals(a.get("delegable")),instant(a,"expiresAt",false)));
            case "grant_revoke" -> {agents.revoke(actor,text(a,"grantId"));yield Map.of("revoked",true);}
            case "session_read" -> agents.detail(actor,text(a,"sessionId"));
            case "session_status" -> agents.executionStatus(actor,text(a,"sessionId"),string(a,"taskId"));
            case "session_control" -> agents.controlAs(actor,text(a,"sessionId"),requestId,SessionStatus.valueOf(text(a,"status")));
            case "session_memory" -> agents.memory(session.id(),a.containsKey("patch")?map(a.get("patch")):null);
            case "schedule_create" -> {
                agents.authorization().require(s,actor,"schedule.create",ScopeKind.SESSION,session.id(),false);
                String taskId=string(a,"taskId");if(taskId!=null) agents.authorization().requireTask(s,actor,taskId,Permission.EXECUTE);
                var due=instant(a,"dueAt",true);Long interval=a.containsKey("intervalSeconds")?number(a,"intervalSeconds"):null;
                if(interval!=null&&interval<60) throw new IllegalArgumentException("recurring interval must be at least 60 seconds");
                var schedule=new Schedule(id(),session.id(),taskId,due,text(a,"instruction"),interval,true,actor,Instant.now());
                s.schedule(schedule);yield schedule;
            }
            case "schedule_cancel" -> {
                var schedule=s.schedule(text(a,"scheduleId")).orElseThrow(()->notFound("schedule"));
                if(!session.id().equals(schedule.sessionId())) agents.authorization().require(s,actor,"schedule.manage",ScopeKind.SESSION,schedule.sessionId(),false);
                s.schedule(new Schedule(schedule.id(),schedule.sessionId(),schedule.taskId(),schedule.dueAt(),schedule.instruction(),schedule.intervalSeconds(),false,schedule.createdBy(),schedule.createdAt()));
                yield Map.of("cancelled",true);
            }
            case "subscription_create" -> {
                ScopeKind kind=ScopeKind.valueOf(text(a,"scopeKind"));String scopeId=text(a,"scopeId");
                if(!Set.of(ScopeKind.TASK,ScopeKind.RESOURCE,ScopeKind.TEMPLATE).contains(kind))
                    throw new IllegalArgumentException("subscriptions support Task, resource and role-template events");
                requireScopeRead(s,actor,kind,scopeId);
                agents.authorization().require(s,actor,"subscription.create",ScopeKind.SESSION,session.id(),false);
                var kinds=list(a.get("eventKinds")).stream().map(Object::toString).toList();
                if(kinds.isEmpty()) throw new IllegalArgumentException("eventKinds is required");
                var subscription=new Subscription(id(),session.id(),kind,scopeId,kinds,true,actor,Instant.now());s.subscription(subscription);yield subscription;
            }
            case "subscription_cancel" -> {
                var subscription=s.subscription(text(a,"subscriptionId")).orElseThrow(()->notFound("subscription"));
                if(!subscription.sessionId().equals(session.id())) agents.authorization().require(s,actor,"subscription.manage",ScopeKind.SESSION,subscription.sessionId(),false);
                var t=com.agentyard.persistence.jooq.Tables.AGENT_SUBSCRIPTION;
                s.database().update(t).set(t.ACTIVE,false).where(t.ID.eq(subscription.id())).execute();yield Map.of("cancelled",true);
            }
            case "tool_run_start" -> toolRuns.start(activation.id(),requestId,text(a,"playbookVersionId"),string(a,"taskId"),map(a.get("input")));
            case "tool_run_resume" -> {
                var authority=authority(s,activation,session,string(a,"sourceMessageId"));
                yield toolRuns.resume(actor,authority.decidedBy(),authority.sourceMessageId(),text(a,"toolRunId"),text(a,"waitingId"),map(a.get("payload")));
            }
            case "resource_read" -> readResource(s,session,map(a.get("reference")));
            default -> throw new IllegalArgumentException("unknown collaboration tool: "+call.name());
        };
    }
    private Object resourceAuthorization(AgentStore s,Activation activation,Session session,ToolInvocation call) {
        var a=call.arguments();String type=text(a,"resourceType"),resource=text(a,"resourceId");
        Object descriptor=capabilities.authorize(s,session,agents.catalog().resolveRoleTemplateVersion(session.templateVersionId()),type,resource,string(a,"operation"));
        return Map.of("descriptor",descriptor,"idempotencyKey",activation.id()+":"+call.callId());
    }
    private List<Actor> participants(AgentStore s,String actor,Map<String,Object> a) {
        String taskId=string(a,"taskId"),organization=string(a,"organizationId");
        if(taskId!=null) {
            agents.authorization().requireTask(s,actor,taskId,Permission.VIEW);
            return s.tasks().participants(taskId).stream().map(p->s.tasks().actor(p.actorId()).orElseThrow()).filter(p->matchesQuery(p.displayName(),a)).toList();
        }
        if(organization!=null) {
            if(!agents.authorization().owns(s,actor,"ORGANIZATION",organization)) throw denied("organization directory is private");
            return s.tasks().members(organization).stream().map(p->s.tasks().actor(p.actorId()).orElseThrow()).filter(p->matchesQuery(p.displayName(),a)).toList();
        }
        return s.tasks().actors(string(a,"query")).stream().filter(p->{
            try {agents.authorization().requireMessage(s,actor,p.id(),null);return true;}catch(ResponseStatusException denied){return false;}
        }).toList();
    }
    private Object readResource(AgentStore s,Session session,Map<String,Object> reference) {
        String type=text(reference,"kind");
        if(type.equals("TASK_CONTENT")) {
            String taskId=text(reference,"taskId"),entryId=text(reference,"entryId");
            var detail=agents.collaboration().summary(taskId,session.participantId());
            var roles=detail.participants().stream().filter(p->p.actorId().equals(session.participantId())).findFirst().orElseThrow().roles();
            var entry=s.tasks().entry(entryId).filter(e->e.taskId().equals(taskId)&&
                (e.authorId().equals(session.participantId())||e.visibleToRoles().isEmpty()||e.visibleToRoles().stream().anyMatch(roles::contains)))
                .orElseThrow(()->notFound("content"));
            if(reference.containsKey("revision")&&number(reference,"revision")!=entry.revision()) {
                long revision=number(reference,"revision");
                entry=s.tasks().version(entryId,revision).filter(e->
                    (e.authorId().equals(session.participantId())||e.visibleToRoles().isEmpty()||e.visibleToRoles().stream().anyMatch(roles::contains)))
                    .orElseThrow(()->notFound("content version"));
            }
            return entry;
        }
        var profile=agents.catalog().resolveRoleTemplateVersion(session.templateVersionId());
        Object descriptor=capabilities.authorize(s,session,profile,type,text(reference,"resourceId"),string(reference,"operation"));
        if(descriptor instanceof com.agentyard.contracts.runtime.RuntimeContracts.ToolDescriptor tool)
            descriptor=Map.of("resourceId",tool.resourceId(),"resourceVersionId",tool.resourceVersionId(),"name",tool.resourceName(),"operations",tool.operations());
        if(descriptor instanceof com.agentyard.contracts.runtime.RuntimeContracts.PlaybookConfig playbook)
            descriptor=Map.of("playbookVersionId",playbook.playbookId(),"name",playbook.name(),"description",playbook.description(),
                "inputSchema",playbook.inputSchema(),"resultSchema",playbook.resultSchema());
        return Map.of("reference",reference,"descriptor",descriptor);
    }
    private void requireScopeRead(AgentStore s,String actor,ScopeKind kind,String id) {
        if(kind==ScopeKind.TASK) agents.authorization().requireTask(s,actor,id,Permission.VIEW);
        else if(kind==ScopeKind.SESSION) agents.authorization().requireSessionRead(s,actor,s.session(id,false).orElseThrow(()->notFound("Session")));
        else agents.authorization().require(s,actor,"resource.read",kind,id,false);
    }
    private CommandAuthority authority(AgentStore s,Activation activation,Session session,String sourceId) {
        if(sourceId==null) return CommandAuthority.own(session.participantId());
        if(!activation.inputMessageId().equals(sourceId)) throw denied("a proxy instruction must come from this activation's current input");
        var source=s.message(sourceId).orElseThrow();
        if(!source.recipientId().equals(session.participantId())) throw denied("instruction was not addressed to this instance");
        var sender=agents.authorization().active(s,source.senderId());
        if(sender.kind()!=ActorKind.HUMAN) throw denied("an Agent cannot impersonate a human decision");
        return new CommandAuthority(session.participantId(),source.senderId(),source.id());
    }
    private static boolean matchesQuery(String value,Map<String,Object> a) {
        String query=string(a,"query");return query==null||value.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT));
    }
    static String string(Map<String,Object> a,String key) {
        Object v=a.get(key);if(v==null)return null;if(!(v instanceof String))throw new IllegalArgumentException(key+" must be a string");return required((String)v,key);
    }
    static String text(Map<String,Object> a,String key) { return required(string(a,key),key); }
    static long number(Map<String,Object> a,String key) {
        Object v=a.get(key);if(!(v instanceof Number n)||n.doubleValue()!=n.longValue())throw new IllegalArgumentException(key+" must be an integer");return ((Number)v).longValue();
    }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value) {
        if(value==null)return Map.of();if(!(value instanceof Map))throw new IllegalArgumentException("expected an object");return (Map<String,Object>)value;
    }
    static List<?> list(Object value) {if(value==null)return List.of();if(!(value instanceof List<?>))throw new IllegalArgumentException("expected an array");return (List<?>)value;}
    private static int limit(Map<String,Object> a) {return a.containsKey("limit")?(int)Math.max(1,Math.min(100,number(a,"limit"))):50;}
    private static Instant instant(Map<String,Object> a,String key,boolean mandatory) {String value=string(a,key);if(value==null){if(mandatory)throw new IllegalArgumentException(key+" is required");return null;}return Instant.parse(value);}
}
