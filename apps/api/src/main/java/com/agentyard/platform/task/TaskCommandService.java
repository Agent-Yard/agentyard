package com.agentyard.platform.task;

import static com.agentyard.contracts.task.TaskContracts.*;
import static com.agentyard.platform.task.TaskAccess.*;
import static com.agentyard.platform.task.CollaborationService.*;
import com.agentyard.persistence.task.TaskStore;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TaskCommandService {
    private final CollaborationService collaboration;
    public TaskCommandService(CollaborationService collaboration) { this.collaboration = collaboration; }
    public CommandResult command(String taskId, Command command) { return command(taskId, collaboration.currentActor().id(), command); }
    CommandResult command(String taskId, String actor, Command command) {
        return command(taskId,actor,command,null);
    }
    CommandResult command(String taskId, String actor, Command command, String executionId) {
        text(command.requestId(), "requestId"); if (command.kind() == null) bad("command kind is required");
        var data = object(command.data());
        String digest = collaboration.store.serialize(List.of(command.kind(), command.expectedRevision(), canonical(data)));
        if (digest.length() > 500000) bad("command exceeds 500 KB");
        return collaboration.store.transaction(s -> {
            // All graph and completion commands share one lock order. Row locks serialize each task's command log.
            if (Set.of(CommandKind.LINK_TASK, CommandKind.UNLINK_TASK, CommandKind.CREATE_CHILD, CommandKind.TRANSITION).contains(command.kind())) s.graphLock();
            Task task = collaboration.task(s,taskId,actor,true);
            if(executionId!=null) {
                var execution=s.execution(executionId).filter(e->e.taskId().equals(taskId)&&e.actorId().equals(actor)).orElseThrow(TaskAccess::missing);
                if(executionTerminal(execution.status())) conflict("execution capability is closed");
                require(permissions(task.policy().roles(),roles(s.participants(taskId),actor)),Permission.EXECUTE);
            }
            var receipt = s.receipt(taskId,actor,command.requestId());
            if (receipt.isPresent()) {
                if (!receipt.get().digest().equals(digest)) conflict("requestId was already used for a different command");
                var result=receipt.get().result(); return new CommandResult(taskId,result.revision(),result.objectId(),true);
            }
            revision(task.revision(),command.expectedRevision());
            var permissions = permissions(task.policy().roles(),roles(s.participants(taskId),actor));
            if (terminal(task.status()) && !Set.of(CommandKind.TRANSITION,CommandKind.LINK_SESSION,CommandKind.UNLINK_SESSION).contains(command.kind())) conflict("reopen a closed task before changing its work or permissions");
            Draft draft = new Draft(task);
            switch (command.kind()) {
                case UPDATE -> {
                    require(permissions,Permission.MANAGE);
                    if(data.containsKey("title")) draft.title=text(str(data,"title"),"title");
                    if(data.containsKey("goal")) draft.goal=text(str(data,"goal"),"goal");
                    if(data.containsKey("stage")) draft.stage=Objects.toString(data.get("stage"),"");
                    if(data.containsKey("attributes")) draft.attributes=map(data.get("attributes"));
                    if(data.containsKey("policy")) {
                        draft.policy=validatePolicy(collaboration.mapper.convertValue(data.get("policy"),Policy.class));
                        // Role keys and permissions may evolve, but existing entries must keep a valid audience.
                        s.participants(taskId).forEach(p -> validateAssignments(draft.policy.roles(),p.roles()));
                        for(var entry : allContext(s,taskId)) validateAssignments(draft.policy.roles(),entry.visibleToRoles());
                        retainManager(draft.policy.roles(),s.participants(taskId));
                    }
                    draft.material=true;
                }
                case SET_PARTICIPANT, REMOVE_PARTICIPANT -> {
                    require(permissions,Permission.MANAGE); String target=text(str(data,"actorId"),"actorId"); activeActor(s,target);
                    List<String> assigned=command.kind()==CommandKind.REMOVE_PARTICIPANT ? List.of() : stringList(data.get("roles"));
                    validateAssignments(task.policy().roles(),assigned); s.participant(taskId,target,assigned); retainManager(task.policy().roles(),s.participants(taskId));
                    draft.material=true;
                }
                case PUT_CONTEXT -> putContext(s,task,actor,permissions,data,draft);
                case SET_CONTEXT_STATE -> {
                    require(permissions,Permission.DECIDE); var entry=entry(s,task,actor,text(str(data,"entryId"),"entryId"));
                    revision(entry.revision(),number(data,"entryRevision"));
                    var updated=new ContextEntry(entry.id(),taskId,entry.kind(),entry.title(),entry.body(),enumValue(ContextState.class,str(data,"state")),entry.visibleToRoles(),entry.source(),entry.authorId(),entry.revision()+1,entry.createdAt(),Instant.now());
                    s.saveEntry(updated,actor); draft.objectId=entry.id(); draft.audience=entry.visibleToRoles(); draft.material=true;
                }
                case CREATE_CHILD -> {
                    require(permissions,Permission.CONTRIBUTE);
                    // Explicit participants: creating a child does not copy the parent's private context or memberships.
                    Policy policy=validatePolicy(data.containsKey("policy") ? collaboration.mapper.convertValue(data.get("policy"),Policy.class) : null);
                    Instant now=Instant.now(); Task child=new Task(id(),task.organizationId(),text(str(data,"title"),"title"),text(str(data,"goal"),"goal"),TaskStatus.OPEN,"",1,1,map(data.get("attributes")),policy,actor,null,now,now);
                    s.saveTask(child); s.participant(child.id(),actor,List.of(managerRole(policy.roles())));
                    // Delegation is explicit. This also lets an Agent hand a child back to people
                    // without making every parent participant a member of every child.
                    for(Object value : data.get("participants") instanceof List<?> members ? members : List.of()) {
                        var participant=collaboration.mapper.convertValue(value,Participant.class);
                        activeActor(s,participant.actorId());
                        collaboration.task(s,taskId,participant.actorId(),false);
                        validateAssignments(policy.roles(),participant.roles());
                        if(executionId!=null) {
                            var execution=s.execution(executionId).orElseThrow(TaskAccess::missing);
                            for(String sourceId:stringList(execution.input().get("contextEntryIds"))) entry(s,task,participant.actorId(),sourceId);
                        }
                        s.participant(child.id(),participant.actorId(),participant.roles());
                    }
                    retainManager(policy.roles(),s.participants(child.id()));
                    s.event(new Event(child.id(),1,actor,"CREATED",Map.of("parentTaskId",taskId),Set.of(),now));
                    s.relation(new Relation(taskId,child.id(),RelationKind.CHILD),false); draft.objectId=child.id(); draft.material=true;
                }
                case LINK_TASK, UNLINK_TASK -> {
                    require(permissions,Permission.MANAGE); String related=text(str(data,"relatedTaskId"),"relatedTaskId");
                    collaboration.task(s,related,actor,false); RelationKind kind=enumValue(RelationKind.class,str(data,"kind"));
                    if(taskId.equals(related)) bad("task cannot relate to itself");
                    if(command.kind()==CommandKind.LINK_TASK && kind!=RelationKind.RELATED && s.wouldCycle(taskId,related)) conflict("child/dependency graph must remain acyclic");
                    s.relation(new Relation(taskId,related,kind),command.kind()==CommandKind.UNLINK_TASK); draft.material=true;
                }
                case LINK_SESSION, UNLINK_SESSION -> {
                    require(permissions,Permission.VIEW); String session=text(str(data,"sessionId"),"sessionId"); var who=activeActor(s,actor);
                    if(who.kind()!=ActorKind.HUMAN || !s.sessionCustomer(session).filter(who.subjectId()::equals).isPresent()) throw missing();
                    s.session(new SessionLink(taskId,session,actor,Instant.now()),command.kind()==CommandKind.UNLINK_SESSION);
                }
                case TRANSITION -> transition(s,task,actor,permissions,data,draft);
                case REVIEW -> {
                    require(permissions,Permission.REVIEW);
                    if(task.status()!=TaskStatus.IN_REVIEW) conflict("task must be submitted for review");
                    revision(task.contentRevision(),number(data,"contentRevision"));
                    if(!task.policy().allowSelfReview() && actor.equals(task.submittedBy())) conflict("submitter cannot approve their own submission");
                    if(!(data.get("approved") instanceof Boolean)) bad("approved must be a boolean");
                    s.review(new Review(taskId,task.contentRevision(),actor,(Boolean)data.get("approved"),Objects.toString(data.get("comment"),""),Instant.now()));
                }
                case START_EXECUTION -> startExecution(s,task,actor,permissions,data,draft);
                case CANCEL_EXECUTION, RESUME_EXECUTION -> {
                    require(permissions,command.kind()==CommandKind.CANCEL_EXECUTION ? Permission.EXECUTE : Permission.DECIDE);
                    var execution=s.execution(text(str(data,"executionId"),"executionId")).filter(e -> e.taskId().equals(taskId)).orElseThrow(TaskAccess::missing);
                    if(command.kind()==CommandKind.CANCEL_EXECUTION) {
                        if(!executionTerminal(execution.status())) s.progress(execution.id(),new ExecutionProgress(ExecutionStatus.CANCELLED,execution.result(),Map.of(),null));
                    } else {
                        if(!collaboration.executionVisible(s,task,actor,execution)) throw missing();
                        String waitingId=text(str(data,"waitingId"),"waitingId");
                        if(execution.status()!=ExecutionStatus.WAITING || !waitingId.equals(execution.waiting().get("id"))) conflict("execution is not waiting for this response");
                        if(s.hasSignal(execution.id(),waitingId)) conflict("this waiting point already has an accepted response");
                        s.signal(execution.id(),new ResumeSignal(id(),waitingId,actor,map(data.get("payload"))));
                    }
                    draft.objectId=execution.id();
                }
            }
            Task updated=draft.build(); s.saveTask(updated);
            // Events carry references, never a second uncontrolled copy of private context or execution input.
            s.event(new Event(taskId,updated.revision(),actor,command.kind().name(),draft.objectId==null ? Map.of() : Map.of("objectId",draft.objectId),draft.audience,Instant.now()));
            var result=new CommandResult(taskId,updated.revision(),draft.objectId,false); s.receipt(taskId,actor,command.requestId(),digest,result); return result;
        });
    }
    private void putContext(TaskStore s,Task task,String actor,Set<Permission> permissions,Map<String,Object> data,Draft draft) {
        require(permissions,Permission.CONTRIBUTE); String entryId=str(data,"entryId");
        ContextEntry previous=entryId==null ? null : entry(s,task,actor,entryId);
        if(previous!=null) {
            revision(previous.revision(),number(data,"entryRevision"));
            if(!previous.authorId().equals(actor)) require(permissions,Permission.DECIDE);
        }
        ContextKind kind=previous==null ? enumValue(ContextKind.class,str(data,"kind")) : previous.kind();
        Set<String> audience=previous==null ? Set.copyOf(stringList(data.get("visibleToRoles"))) : previous.visibleToRoles();
        if(previous!=null && data.containsKey("visibleToRoles") && !audience.equals(Set.copyOf(stringList(data.get("visibleToRoles"))))) bad("entry audience is immutable; publish a new entry with source references");
        validateAssignments(task.policy().roles(),audience);
        Map<String,Object> source=new LinkedHashMap<>(map(data.get("source")));
        Map<String,Long> sourceRevisions=new LinkedHashMap<>();
        for(String sourceId:stringList(source.get("entryIds"))) {
            if(sourceId.equals(entryId)) bad("an entry cannot cite itself");
            var origin=entry(s,task,actor,sourceId);
            sourceRevisions.put(sourceId,origin.revision());
            if(!origin.visibleToRoles().isEmpty() && (audience.isEmpty() || !origin.visibleToRoles().containsAll(audience))) bad("derived context must not broaden its source audience");
        }
        if(!sourceRevisions.isEmpty()) source.put("entryRevisions",sourceRevisions);
        // Execution-derived content must use the same restricted audience as every input it may have consumed.
        if(source.get("executionId")!=null) {
            var execution=s.execution(source.get("executionId").toString()).filter(e->e.taskId().equals(task.id())).orElseThrow(TaskAccess::missing);
            if(!collaboration.executionVisible(s,task,actor,execution)) throw missing();
            for(String sourceId:stringList(execution.input().get("contextEntryIds"))) {
                var origin=s.entry(sourceId).orElseThrow(TaskAccess::missing);
                if(!origin.visibleToRoles().isEmpty() && (audience.isEmpty() || !origin.visibleToRoles().containsAll(audience))) bad("execution output cannot broaden its input audience");
            }
            for(Object value:(List<?>)execution.input().getOrDefault("contextEntries",List.of())) {
                var snapshot=collaboration.mapper.convertValue(value,ContextEntry.class);
                sourceRevisions.put(snapshot.id(),snapshot.revision());
            }
            source.put("entryRevisions",sourceRevisions);
            source.put("contextRevision",execution.input().get("contextRevision"));
        }
        ContextState state=data.containsKey("state") ? enumValue(ContextState.class,str(data,"state")) : ContextState.PROPOSED;
        if(state!=ContextState.PROPOSED || kind==ContextKind.DECISION) require(permissions,Permission.DECIDE);
        Instant now=Instant.now(); var updated=new ContextEntry(previous==null ? id() : previous.id(),task.id(),kind,text(str(data,"title"),"title"),map(data.get("body")),state,audience,source,previous==null?actor:previous.authorId(),previous==null?1:previous.revision()+1,previous==null?now:previous.createdAt(),now);
        s.saveEntry(updated,actor); draft.objectId=updated.id(); draft.audience=audience; draft.material=true;
    }
    private void transition(TaskStore s,Task task,String actor,Set<Permission> permissions,Map<String,Object> data,Draft draft) {
        require(permissions,Permission.MANAGE); TaskStatus target=enumValue(TaskStatus.class,str(data,"status"));
        if(task.status()==target) conflict("task already has this status");
        if(terminal(task.status()) && target!=TaskStatus.ACTIVE) conflict("closed tasks can only be reopened as ACTIVE");
        if(target==TaskStatus.COMPLETED || target==TaskStatus.IN_REVIEW) {
            if(s.executions(task.id()).stream().anyMatch(e->!executionTerminal(e.status()))) conflict("unfinished executions remain");
            if(s.relations(task.id()).stream().filter(r->r.kind()!=RelationKind.RELATED).anyMatch(r->s.task(r.relatedTaskId(),false).orElseThrow().status()!=TaskStatus.COMPLETED)) conflict("child tasks and dependencies must be completed first");
        }
        if(target==TaskStatus.COMPLETED && task.policy().requiredApprovals()>0) {
            if(task.status()!=TaskStatus.IN_REVIEW) conflict("task requires review before completion");
            var reviews=s.reviews(task.id()).stream().filter(r->r.contentRevision()==task.contentRevision())
                .filter(r->permissions(task.policy().roles(),roles(s.participants(task.id()),r.actorId())).contains(Permission.REVIEW)).toList();
            if(reviews.stream().anyMatch(r->!r.approved()) || reviews.stream().filter(Review::approved).count()<task.policy().requiredApprovals()) conflict("current submission has not passed its review policy");
        }
        if(target==TaskStatus.CANCELLED) s.executions(task.id()).stream().filter(e->!executionTerminal(e.status())).forEach(e->s.progress(e.id(),new ExecutionProgress(ExecutionStatus.CANCELLED,e.result(),Map.of(),null)));
        draft.status=target; draft.submittedBy=target==TaskStatus.IN_REVIEW?actor:null;
        if(terminal(task.status())) draft.material=true;
    }
    private void startExecution(TaskStore s,Task task,String actor,Set<Permission> permissions,Map<String,Object> data,Draft draft) {
        require(permissions,Permission.EXECUTE); String executor=text(str(data,"actorId"),"actorId"); var who=activeActor(s,executor);
        if(who.kind()!=ActorKind.AGENT) bad("execution requires an Agent actor; humans contribute directly to tasks");
        require(permissions(task.policy().roles(),roles(s.participants(task.id()),executor)),Permission.EXECUTE);
        String playbookId=str(data,"playbookId"); var snapshot=collaboration.runtime.resolve(text(str(data,"releaseId"),"releaseId"),who.subjectId(),playbookId);
        var input=new LinkedHashMap<>(map(data.get("input")));
        // Selection is explicit and bounded. An omitted selection means visible entries, never another actor's hidden context.
        List<String> selected=data.containsKey("contextEntryIds") ? stringList(data.get("contextEntryIds")) : s.context(task.id(),executor,roles(s.participants(task.id()),executor),null,200).stream().map(ContextEntry::id).toList();
        for(String sourceId:selected) { entry(s,task,actor,sourceId); entry(s,task,executor,sourceId); }
        input.put("contextEntryIds",selected); input.put("contextRevision",task.contentRevision());
        input.put("contextEntries",selected.stream().map(sourceId -> s.entry(sourceId).orElseThrow()).toList());
        Instant now=Instant.now(); var execution=new Execution(id(),task.id(),executor,actor,playbookId==null?ExecutionKind.AGENT:ExecutionKind.PLAYBOOK,ExecutionStatus.QUEUED,text(str(data,"instruction"),"instruction"),input,Map.of(),Map.of(),null,now,now);
        s.createExecution(execution,snapshot); draft.objectId=execution.id(); draft.material=true;
        if(task.status()==TaskStatus.OPEN || task.status()==TaskStatus.WAITING) draft.status=TaskStatus.ACTIVE;
    }
    ContextEntry entry(TaskStore s,Task task,String actor,String entryId) {
        return s.entry(entryId).filter(e->e.taskId().equals(task.id()) && visible(e,actor,roles(s.participants(task.id()),actor))).orElseThrow(TaskAccess::missing);
    }
    private List<ContextEntry> allContext(TaskStore s,String taskId) {
        // Union of all role audiences allows validation without changing read permissions.
        var task=s.task(taskId,false).orElseThrow(); Set<String> roles=new HashSet<>(); task.policy().roles().forEach(r->roles.add(r.key()));
        List<ContextEntry> result=new ArrayList<>(); String cursor=null;
        do { var page=s.context(taskId,"",roles,cursor,200); result.addAll(page); cursor=page.size()==200?page.getLast().id():null; } while(cursor!=null);
        return result;
    }
    static boolean terminal(TaskStatus status) { return status==TaskStatus.COMPLETED || status==TaskStatus.CANCELLED; }
    static boolean executionTerminal(ExecutionStatus status) { return Set.of(ExecutionStatus.SUCCEEDED,ExecutionStatus.FAILED,ExecutionStatus.CANCELLED).contains(status); }
    static long number(Map<String,Object> data,String key) { if(!(data.get(key) instanceof Number)) bad(key+" is required"); return ((Number)data.get(key)).longValue(); }
    static <E extends Enum<E>> E enumValue(Class<E> type,String value) { try { return Enum.valueOf(type,value); } catch(RuntimeException e) { bad("invalid "+type.getSimpleName()+": "+value); return null; } }
    private static Object canonical(Object value) {
        if(value instanceof Map<?,?> map) { var result=new TreeMap<String,Object>(); map.forEach((k,v)->result.put(k.toString(),canonical(v))); return result; }
        if(value instanceof List<?> list) return list.stream().map(TaskCommandService::canonical).toList(); return value;
    }
    static final class Draft {
        final Task task; String title,goal,stage,submittedBy,objectId; TaskStatus status; Map<String,Object> attributes; Policy policy; boolean material; Set<String> audience=Set.of();
        Draft(Task t) { task=t;title=t.title();goal=t.goal();stage=t.stage();submittedBy=t.submittedBy();status=t.status();attributes=t.attributes();policy=t.policy(); }
        Task build() {
            if(material && status==TaskStatus.IN_REVIEW) { status=TaskStatus.ACTIVE;submittedBy=null; }
            return new Task(task.id(),task.organizationId(),title,goal,status,stage,task.revision()+1,task.contentRevision()+(material?1:0),attributes,policy,task.createdBy(),submittedBy,task.createdAt(),Instant.now());
        }
    }
}
