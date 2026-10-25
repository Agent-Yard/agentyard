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
    public CommandResult command(String taskId, Command command) {
        return command(taskId, CommandAuthority.own(collaboration.currentActor().id()), command);
    }
    public CommandResult command(String taskId, CommandAuthority authority, Command command) {
        String actor = authority.executedBy();
        String decider = authority.decidedBy();
        text(command.requestId(), "requestId"); if (command.kind() == null) bad("command kind is required");
        var data = object(command.data());
        String digest = collaboration.store.serialize(List.of(command.kind(), command.expectedRevision(), canonical(data), authority));
        if (digest.length() > 500000) bad("command exceeds 500 KB");
        return collaboration.store.transaction(s -> {
            // All graph and completion commands share one lock order. Row locks serialize each task's command log.
            if (Set.of(CommandKind.LINK_TASK, CommandKind.UNLINK_TASK, CommandKind.CREATE_CHILD, CommandKind.TRANSITION).contains(command.kind())) s.graphLock();
            validateAuthority(s, authority);
            Task task = collaboration.task(s,taskId,decider,true);
            collaboration.task(s,taskId,actor,false);
            var receipt = s.receipt(taskId,actor,command.requestId());
            if (receipt.isPresent()) {
                if (!receipt.get().digest().equals(digest)) conflict("requestId was already used for a different command");
                var result=receipt.get().result(); return new CommandResult(taskId,result.revision(),result.objectId(),true,
                    result.status(),result.contentRevision(),result.submissionInvalidated());
            }
            revision(task.revision(),command.expectedRevision());
            var permissions = permissions(task.policy().roles(),roles(s.participants(taskId),decider));
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
                    draft.material=data.keySet().stream().anyMatch(key -> !key.equals("stage"));
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
                    Instant now=Instant.now(); Task child=new Task(id(),task.organizationId(),text(str(data,"title"),"title"),text(str(data,"goal"),"goal"),TaskStatus.OPEN,"",1,1,map(data.get("attributes")),policy,actor,actor,actor,null,now,now);
                    s.saveTask(child); s.participant(child.id(),actor,List.of(managerRole(policy.roles())));
                    // Delegation is explicit. This also lets an Agent hand a child back to people
                    // without making every parent participant a member of every child.
                    for(Object value : data.get("participants") instanceof List<?> members ? members : List.of()) {
                        var participant=collaboration.mapper.convertValue(value,Participant.class);
                        activeActor(s,participant.actorId());
                        collaboration.task(s,taskId,participant.actorId(),false);
                        validateAssignments(policy.roles(),participant.roles());
                        s.participant(child.id(),participant.actorId(),participant.roles());
                    }
                    retainManager(policy.roles(),s.participants(child.id()));
                    s.event(new Event(child.id(),1,actor,"CREATED",Map.of("parentTaskId",taskId),Set.of(),now));
                    s.publish(child.id(),"CREATED",actor,Map.of("revision",1));
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
                    String participant=s.sessionParticipant(session).orElseThrow(TaskAccess::missing);
                    if(!participant.equals(actor)) require(permissions,Permission.MANAGE);
                    collaboration.task(s,taskId,participant,false);
                    s.session(new SessionLink(taskId,session,participant,Instant.now()),command.kind()==CommandKind.UNLINK_SESSION);
                }
                case TRANSITION -> transition(s,task,authority,permissions,data,draft);
                case SUBMIT, CONFIRM, REQUEST_CHANGES, CONFIRM_STAGE, TRANSFER_CONFIRMATION ->
                    decide(s,task,authority,permissions,command.kind(),data,draft);

            }
            Task updated=draft.build(); s.saveTask(updated);
            // Events carry references, never a second uncontrolled copy of private context or execution input.
            s.event(new Event(taskId,updated.revision(),actor,command.kind().name(),draft.objectId==null ? Map.of() : Map.of("objectId",draft.objectId),draft.audience,Instant.now()));
            Map<String,Object> notification=new LinkedHashMap<>(Map.of("revision",updated.revision(),"status",updated.status().name(),"previousStatus",task.status().name()));
            if(command.kind()==CommandKind.REQUEST_CHANGES) {
                notification.put("notifyParticipantId",Objects.requireNonNullElse(task.submittedBy(),task.createdBy()));
                notification.put("decisionId",draft.objectId);
            }
            s.publish(taskId,command.kind().name(),actor,notification);
            var result=new CommandResult(taskId,updated.revision(),draft.objectId,false,updated.status(),updated.contentRevision(),
                task.status()==TaskStatus.SUBMITTED && draft.material);
            s.receipt(taskId,actor,command.requestId(),digest,result); return result;
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
        ContextState state=data.containsKey("state") ? enumValue(ContextState.class,str(data,"state")) : ContextState.PROPOSED;
        if(state!=ContextState.PROPOSED || kind==ContextKind.DECISION) require(permissions,Permission.DECIDE);
        Instant now=Instant.now(); var updated=new ContextEntry(previous==null ? id() : previous.id(),task.id(),kind,text(str(data,"title"),"title"),map(data.get("body")),state,audience,source,previous==null?actor:previous.authorId(),previous==null?1:previous.revision()+1,previous==null?now:previous.createdAt(),now);
        s.saveEntry(updated,actor); draft.objectId=updated.id(); draft.audience=audience; draft.material=true;
    }
    private void transition(TaskStore s,Task task,CommandAuthority authority,Set<Permission> permissions,
        Map<String,Object> data,Draft draft) {
        require(permissions,Permission.MANAGE);
        TaskStatus target=enumValue(TaskStatus.class,str(data,"status"));
        if(target==TaskStatus.COMPLETED || target==TaskStatus.SUBMITTED) bad("use SUBMIT or CONFIRM for a delivery");
        if(task.status()==target) conflict("task already has this status");
        if(task.status()==TaskStatus.SUBMITTED && target==TaskStatus.WAITING)
            conflict("submitted work already awaits confirmation; keep SUBMITTED, or explicitly resume ACTIVE work before requesting new information");
        if(terminal(task.status()) && target!=TaskStatus.ACTIVE) conflict("closed tasks can only be reopened as ACTIVE");
        if(terminal(task.status()) && !task.confirmerId().equals(authority.decidedBy()))
            bad("only the confirmer can reopen completed work");
        draft.status=target;
        if(target==TaskStatus.CANCELLED || target==TaskStatus.PAUSED || task.status()==TaskStatus.PAUSED) {
            DecisionKind kind=target==TaskStatus.CANCELLED ? DecisionKind.CANCEL :
                target==TaskStatus.PAUSED ? DecisionKind.PAUSE : DecisionKind.RESUME;
            recordDecision(s,task,authority,kind,data);
        }
        if(terminal(task.status())) draft.material=true;
    }
    private void decide(TaskStore s,Task task,CommandAuthority authority,Set<Permission> permissions,
        CommandKind command,Map<String,Object> data,Draft draft) {
        if(command==CommandKind.SUBMIT) {
            require(permissions,Permission.CONTRIBUTE);
            if(task.status()==TaskStatus.PAUSED) conflict("resume work before submitting");
            draft.status=TaskStatus.SUBMITTED; draft.submittedBy=authority.executedBy();
        } else {
            if(!task.confirmerId().equals(authority.decidedBy())) bad("the designated business confirmer must make this decision");
            if(command==CommandKind.TRANSFER_CONFIRMATION) {
                String participant=text(str(data,"participantId"),"participantId");
                collaboration.task(s,task.id(),participant,false);
                draft.confirmerId=participant;
            } else {
                revision(task.contentRevision(),number(data,"contentRevision"));
                if(command!=CommandKind.CONFIRM_STAGE && task.status()!=TaskStatus.SUBMITTED)
                    conflict("the work must be submitted before a final decision");
                if(command==CommandKind.CONFIRM) draft.status=TaskStatus.COMPLETED;
                if(command==CommandKind.REQUEST_CHANGES) { draft.status=TaskStatus.ACTIVE;draft.submittedBy=null; }
            }
        }
        draft.objectId=recordDecision(s,task,authority,DecisionKind.valueOf(command.name()),data);
    }
    private String recordDecision(TaskStore s,Task task,CommandAuthority authority,DecisionKind kind,Map<String,Object> data) {
        String id=id();
        s.decision(new Decision(id,task.id(),task.contentRevision(),kind,authority.decidedBy(),
            authority.executedBy(),authority.sourceMessageId(),Objects.toString(data.get("comment"),""),Instant.now()));
        return id;
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
    static long number(Map<String,Object> data,String key) { if(!(data.get(key) instanceof Number)) bad(key+" is required"); return ((Number)data.get(key)).longValue(); }
    static <E extends Enum<E>> E enumValue(Class<E> type,String value) { try { return Enum.valueOf(type,value); } catch(RuntimeException e) { bad("invalid "+type.getSimpleName()+": "+value); return null; } }
    private static Object canonical(Object value) {
        if(value instanceof Map<?,?> map) { var result=new TreeMap<String,Object>(); map.forEach((k,v)->result.put(k.toString(),canonical(v))); return result; }
        if(value instanceof List<?> list) return list.stream().map(TaskCommandService::canonical).toList(); return value;
    }
    static final class Draft {
        final Task task; String title,goal,stage,submittedBy,confirmerId,objectId; TaskStatus status; Map<String,Object> attributes; Policy policy; boolean material; Set<String> audience=Set.of();
        Draft(Task t) { task=t;title=t.title();goal=t.goal();stage=t.stage();submittedBy=t.submittedBy();confirmerId=t.confirmerId();status=t.status();attributes=t.attributes();policy=t.policy(); }
        Task build() {
            if(material && status==TaskStatus.SUBMITTED) { status=TaskStatus.ACTIVE;submittedBy=null; }
            return new Task(task.id(),task.organizationId(),title,goal,status,stage,task.revision()+1,task.contentRevision()+(material?1:0),attributes,policy,task.initiatedBy(),task.createdBy(),confirmerId,submittedBy,task.createdAt(),Instant.now());
        }
    }
}
