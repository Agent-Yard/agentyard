package com.agentyard.platform.agent;

import static com.agentyard.contracts.agent.AgentContracts.*;
import static com.agentyard.contracts.task.TaskContracts.*;
import static com.agentyard.platform.agent.AgentAuthorization.*;

import com.agentyard.persistence.agent.AgentStore;
import com.agentyard.persistence.jooqsupport.JooqJsonbSupport;
import com.agentyard.platform.catalog.CatalogDtos.EntryDto;
import com.agentyard.platform.catalog.CatalogService;
import com.agentyard.platform.task.CollaborationService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Application service shared by reception, collaboration tools and verified channel adapters. */
@Service
public class AgentService {
    private final AgentStore store;
    private final AgentAuthorization authorization;
    private final CatalogService catalog;
    private final CollaborationService collaboration;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Value("${agentyard.agent.max-tool-steps:32}")
    private int maxToolSteps=32;

    public AgentService(DSLContext db,ObjectMapper mapper,AgentAuthorization authorization,
        CatalogService catalog,CollaborationService collaboration) {
        this.store=new AgentStore(db,new JooqJsonbSupport(mapper));this.mapper=mapper;
        this.authorization=authorization;this.catalog=catalog;this.collaboration=collaboration;
    }
    public AgentStore store() {return store;}
    public AgentAuthorization authorization() {return authorization;}
    public CatalogService catalog() {return catalog;}
    public CollaborationService collaboration() {return collaboration;}
    public ObjectMapper mapper() {return mapper;}
    public Actor currentActor() { return collaboration.currentActor(); }
    public List<EntryDto> entries() { return entries(currentActor().id()); }
    public List<EntryDto> entries(String participantId) {
        authorization.active(store,participantId);
        return catalog.listEntries().stream().filter(e->authorization.entryVisible(store,participantId,e)).toList();
    }
    /** Invoked only after a channel adapter has authenticated and verified its namespaced identity. */
    @Transactional
    public Actor ensureHumanIdentity(String namespace,String subjectId,String displayName) {
        String subject="external:"+UUID.nameUUIDFromBytes((required(namespace,"namespace")+"\u0000"+required(subjectId,"subjectId")).getBytes(StandardCharsets.UTF_8));
        return store.tasks().ensureActor(new Actor(id(),ActorKind.HUMAN,subject,required(displayName,"displayName"),true,Instant.now()));
    }
    public Reception reception(ReceptionRequest request) { return receptionFor(currentActor().id(),request.entryId()); }
    @Transactional
    public Reception receptionFor(String participantId,String entryId) {
        var human=authorization.active(store,participantId);
        if(human.kind()!=ActorKind.HUMAN) throw denied("reception is bound to a human participant");
        EntryDto entry=entryId==null?entries(participantId).stream().findFirst().orElseThrow(()->
            new ResponseStatusException(HttpStatus.CONFLICT,"No reception entry is configured. An administrator must configure a model and reception role.")):catalog.getEntry(entryId);
        if(!authorization.entryVisible(store,participantId,entry)) throw denied("reception entry is not available");
        return store.transaction(s->{
            s.lock("reception:"+participantId+":"+entry.id());
            var binding=s.reception(participantId,entry.id());
            Session session;
            if(binding.isPresent()) session=s.session(binding.get().sessionId(),false).orElseThrow();
            else {
                AgentProfile profile=catalog.resolveRoleTemplateVersion(entry.receptionTemplateVersionId());
                session=create(s,participantId,entry.receptionTemplateVersionId(),profile.name(),participantId);
                s.reception(new ReceptionBinding(participantId,entry.id(),session.id(),Instant.now()));
            }
            synchronizeEntryGrants(s,session,entry,participantId);
            return receptionView(s,session,participantId,null,null);
        });
    }
    public Reception receptionDetail(String sessionId,String cursor) {
        return receptionDetail(currentActor().id(),sessionId,cursor);
    }
    public Reception receptionDetail(String human,String sessionId,String cursor) {
        return receptionDetail(human,sessionId,cursor,null);
    }
    public Reception receptionDetail(String human,String sessionId,String cursor,String before) {
        authorization.active(store,human);
        var session=store.session(sessionId,false).orElseThrow(()->notFound("Session"));
        var b=com.agentyard.persistence.jooq.Tables.AGENT_RECEPTION_BINDING;
        if(!store.database().fetchExists(store.database().selectOne().from(b).where(b.SESSION_ID.eq(sessionId).and(b.PARTICIPANT_ID.eq(human)))))
            throw denied("this reception belongs to another participant");
        return receptionView(store,session,human,cursor,before);
    }
    private Reception receptionView(AgentStore s,Session session,String human,String cursor,String before) {
        var a=com.agentyard.persistence.jooq.Tables.AGENT_ACTIVATION;
        var m=com.agentyard.persistence.jooq.Tables.COLLABORATION_MESSAGE;
        String activationId=s.database().select(a.ID).from(a).join(m).on(m.ID.eq(a.INPUT_MESSAGE_ID))
            .where(a.SESSION_ID.eq(session.id()).and(m.SENDER_ID.eq(human)))
            .orderBy(a.CREATED_AT.desc(),a.ID.desc()).limit(1).fetchOne(a.ID);
        var publicSession=new Session(session.id(),session.participantId(),session.templateVersionId(),session.name(),session.status(),session.createdBy(),
            session.replyToParticipantId(),Map.of(),session.revision(),session.createdAt(),session.updatedAt());
        var page=s.messages(human,null,cursor,before,100);
        var messages=page.stream().filter(message->message.taskId()==null||authorization.taskPermissions(s,human,message.taskId()).contains(Permission.VIEW)).toList();
        var participantIds=new java.util.LinkedHashSet<String>();participantIds.add(human);participantIds.add(session.participantId());
        messages.forEach(message->{participantIds.add(message.senderId());participantIds.add(message.recipientId());});
        var participants=participantIds.stream().map(id->s.tasks().actor(id).orElseThrow()).toList();
        return new Reception(publicSession,human,messages,activationId==null?null:s.activation(activationId,false).orElse(null),participants,
            page.size()==100?page.getFirst().id():null);
    }
    private Session create(AgentStore s,String creator,String version,String name,String replyTo) {
        String sessionId=id();Instant now=Instant.now();
        var participant=s.tasks().ensureActor(new Actor(id(),ActorKind.AGENT,sessionId,required(name,"name"),true,now));
        var session=new Session(sessionId,participant.id(),version,name,SessionStatus.ACTIVE,creator,replyTo,Map.of(),1,now,now);
        s.saveSession(session);return session;
    }
    private void rootGrant(AgentStore s,String target,String issuer,GrantRequest request) {
        s.grant(new Grant(id(),target,request.operation(),request.scopeKind(),request.scopeId(),request.delegable(),issuer,null,
            request.expiresAt(),null,Instant.now()));
    }
    private void synchronizeEntryGrants(AgentStore s,Session session,EntryDto entry,String human) {
        s.lock("entry-grants:"+session.id());
        var desired=new ArrayList<GrantRequest>(entry.capabilityGrants());
        entry.allowedTemplateIds().forEach(template->desired.add(new GrantRequest("agent.create",ScopeKind.TEMPLATE,template,true,null)));
        var existing=s.grants(session.participantId()).stream().filter(g->g.parentGrantId()==null).toList();
        for(var request:desired) {
            var previous=existing.stream().filter(g->g.operation().equals(request.operation())&&g.scopeKind()==request.scopeKind()&&g.scopeId().equals(request.scopeId())).findFirst();
            if(previous.isEmpty()) rootGrant(s,session.participantId(),human,request);
            else if(previous.get().revokedAt()==null) {
                var t=com.agentyard.persistence.jooq.Tables.COLLABORATION_GRANT;
                s.database().update(t).set(t.DELEGABLE,request.delegable()).set(t.EXPIRES_AT,com.agentyard.persistence.jooqsupport.JooqTimeSupport.toOffsetDateTime(request.expiresAt()))
                    .where(t.ID.eq(previous.get().id())).execute();
            }
        }
    }
    @Transactional
    public Session createAs(String creator,CreateSession request) {
        return store.transaction(s->mapper.convertValue(idempotent(s,creator,request.requestId(),"agent.create",request,ignored->{
            var version=catalog.getRoleTemplateVersion(required(request.templateVersionId(),"templateVersionId"));
            authorization.require(s,creator,"agent.create",ScopeKind.TEMPLATE,version.templateId(),false);
            var profile=catalog.resolveRoleTemplateVersion(version.id());
            if(request.taskId()!=null) authorization.requireTask(s,creator,request.taskId(),Permission.EXECUTE);
            var session=create(s,creator,version.id(),request.name()==null?profile.name():request.name(),creator);
            if(request.taskId()!=null) {
                var task=s.tasks().task(request.taskId(),true).orElseThrow();
                if(java.util.Set.of(TaskStatus.COMPLETED,TaskStatus.CANCELLED).contains(task.status()))
                    throw new IllegalArgumentException("reopen the Task before delegating new work");
                // Adding a new participant is an explicit change to the Task's audience and permissions.
                authorization.requireTask(s,creator,task.id(),Permission.MANAGE);
                var assigned=request.taskRoles();
                if(assigned.isEmpty()) assigned=List.of(task.policy().roles().stream().filter(r->r.permissions().contains(Permission.CONTRIBUTE)
                    &&!r.permissions().contains(Permission.MANAGE)).findFirst().orElseThrow(()->
                        new IllegalArgumentException("specify taskRoles from this Task's policy")).key());
                var available=task.policy().roles().stream().map(RoleDefinition::key).toList();
                if(!available.containsAll(assigned)) throw new IllegalArgumentException("unknown Task role");
                var issuerPermissions=authorization.taskPermissions(s,creator,task.id());
                var selectedRoles=assigned;
                if(task.policy().roles().stream().filter(r->selectedRoles.contains(r.key()))
                    .anyMatch(r->!issuerPermissions.containsAll(r.permissions()))) throw denied("cannot delegate Task permissions the creator does not possess");
                s.tasks().participant(task.id(),session.participantId(),assigned);
                s.tasks().session(new SessionLink(task.id(),session.id(),session.participantId(),Instant.now()),false);
                var updated=new Task(task.id(),task.organizationId(),task.title(),task.goal(),task.status()==TaskStatus.SUBMITTED?TaskStatus.ACTIVE:task.status(),
                    task.stage(),task.revision()+1,task.contentRevision()+1,task.attributes(),task.policy(),task.initiatedBy(),task.createdBy(),task.confirmerId(),
                    task.status()==TaskStatus.SUBMITTED?null:task.submittedBy(),task.createdAt(),Instant.now());
                s.tasks().saveTask(updated);
                s.tasks().event(new Event(task.id(),updated.revision(),creator,"AGENT_JOINED",Map.of("participantId",session.participantId()),java.util.Set.of(),Instant.now()));
                s.tasks().publish(task.id(),"AGENT_JOINED",creator,Map.of("revision",updated.revision()));
            }
            for(var grant:request.grants()) delegate(s,creator,session.participantId(),grant);
            var message=new SendMessage(request.requestId()+":instruction",session.participantId(),request.taskId(),null,
                MessageKind.DELEGATION,Map.of("text",required(request.instruction(),"instruction")));
            send(s,creator,message);
            return session;
        }),Session.class));
    }
    public Message send(SendMessage request) { return sendAs(currentActor().id(),request); }
    @Transactional
    public Message sendAs(String participantId,SendMessage request) {
        return store.transaction(s->mapper.convertValue(idempotent(s,participantId,request.requestId(),"message.send",request,
            ignored->send(s,participantId,request)),Message.class));
    }
    Message send(AgentStore s,String sender,SendMessage request) {
        authorization.requireMessage(s,sender,required(request.recipientId(),"recipientId"),request.taskId());
        if(request.kind()==null) throw new IllegalArgumentException("message kind is required");
        if(request.body().isEmpty() || s.json().writeJson(request.body()).length()>200000)
            throw new IllegalArgumentException("message content must be nonempty and no larger than 200 KB");
        if(request.replyToMessageId()!=null) {
            var parent=s.message(request.replyToMessageId()).orElseThrow(()->notFound("reply message"));
            if(!sender.equals(parent.senderId())&&!sender.equals(parent.recipientId())) throw denied("reply source is private");
            if(parent.taskId()!=null&&!Objects.equals(parent.taskId(),request.taskId())) throw new IllegalArgumentException("reply must preserve its work association");
        }
        var recipientSession=s.participantSession(request.recipientId());
        if(recipientSession.filter(session->session.status()==SessionStatus.CLOSED).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"recipient Session is closed");
        var message=new Message(id(),sender,request.recipientId(),request.taskId(),request.replyToMessageId(),request.kind(),request.body(),Instant.now());
        s.message(message);
        if(recipientSession.isPresent()) s.enqueue(message,recipientSession.get());
        else s.delivery(message.id(),null,null,DeliveryStatus.PENDING);
        return message;
    }

    /** One terminal message per activation/audience/work, independent of model call IDs.
     * Receipts are authoritative; the model cannot create another delivery by rephrasing it. */
    Message sendFromActivation(AgentStore s,Activation activation,Session session,SendMessage request) {
        if(!java.util.Set.of(MessageKind.RESULT,MessageKind.QUESTION).contains(request.kind()))
            return send(s,session.participantId(),request);
        String key=activation.id()+":delivery:"+request.recipientId()+":"+Objects.toString(request.taskId(),"");
        s.lock(key);
        authorization.requireMessage(s,session.participantId(),request.recipientId(),request.taskId());
        var previous=s.receipt(session.participantId(),key);
        if(previous.isPresent()) return mapper.convertValue(previous.get().result(),Message.class);
        var message=send(s,session.participantId(),request);
        s.receipt(session.participantId(),key,"activation.delivery",key,message);
        var input=s.message(activation.inputMessageId()).orElseThrow();
        if(Objects.equals(request.recipientId(),replyRecipient(s,session,input)))
            s.database().execute("update agent_activation set reply_message_id=? where id=?",message.id(),activation.id());
        return message;
    }
    @Transactional
    public Grant delegate(String issuer,String participantId,GrantRequest request) {
        return store.transaction(s->delegate(s,issuer,participantId,request));
    }
    Grant delegate(AgentStore s,String issuer,String participantId,GrantRequest request) {
        authorization.active(s,participantId);
        var parent=authorization.require(s,issuer,required(request.operation(),"operation"),
            Objects.requireNonNull(request.scopeKind()),required(request.scopeId(),"scopeId"),true);
        Instant expires=request.expiresAt()==null?parent.expiresAt():request.expiresAt();
        if(expires!=null&&(!expires.isAfter(Instant.now())||parent.expiresAt()!=null&&expires.isAfter(parent.expiresAt())))
            throw denied("delegation cannot exceed the parent's lifetime");
        var grant=new Grant(id(),participantId,request.operation(),request.scopeKind(),request.scopeId(),request.delegable(),issuer,parent.id(),expires,null,Instant.now());
        s.grant(grant);return grant;
    }
    @Transactional
    public void revoke(String issuer,String grantId) {
        store.transaction(s->{
            var grant=s.grant(grantId).orElseThrow(()->notFound("grant"));
            if(!grant.grantedBy().equals(issuer)&&!grant.participantId().equals(issuer))
                authorization.require(s,issuer,"grant.manage",grant.scopeKind(),grant.scopeId(),false);
            s.revoke(grantId);return null;
        });
    }
    public SessionDetail detail(String sessionId) { return detail(currentActor().id(),sessionId); }
    public SessionDetail detail(String actor,String sessionId) {
        var session=store.session(sessionId,false).orElseThrow(()->notFound("Session"));
        authorization.requireSessionRead(store,actor,session);
        return new SessionDetail(session,store.messages(session.participantId(),null,null,100),store.activations(session.id(),100),
            store.toolRuns(session.id(),100),store.schedules(session.id()),store.subscriptions(session.id()));
    }
    @Transactional
    public Session control(String sessionId,String requestId,SessionStatus status) {
        return controlAs(currentActor().id(),sessionId,requestId,status);
    }
    @Transactional
    public Session controlAs(String actor,String sessionId,String requestId,SessionStatus status) {
        return store.transaction(s->mapper.convertValue(idempotent(s,actor,requestId,"session.control",Map.of("sessionId",sessionId,"status",status),ignored->{
            var session=s.session(sessionId,true).orElseThrow(()->notFound("Session"));
            if(!session.createdBy().equals(actor)&&!session.participantId().equals(actor)) authorization.require(s,actor,"session.control",ScopeKind.SESSION,sessionId,false);
            if(session.status()==SessionStatus.CLOSED&&status!=SessionStatus.CLOSED) throw new IllegalArgumentException("a closed instance cannot be silently recreated");
            var updated=new Session(session.id(),session.participantId(),session.templateVersionId(),session.name(),status,session.createdBy(),
                session.replyToParticipantId(),session.workMemory(),session.revision()+1,session.createdAt(),Instant.now());
            s.saveSession(updated);
            if(session.status()==SessionStatus.PAUSED&&status==SessionStatus.ACTIVE)
                send(s,session.participantId(),new SendMessage(requestId+":resume",session.participantId(),null,null,MessageKind.CONTROL,
                    Map.of("text","This instance was explicitly resumed. Inspect persisted work and tool receipts before continuing any interrupted operation.")));
            return updated;
        }),Session.class));
    }
    public ActivationRuntime runtime(String activationId) {
        var activation=running(store,activationId);var session=store.session(activation.sessionId(),false).orElseThrow();
        var binding=com.agentyard.persistence.jooq.Tables.AGENT_RECEPTION_BINDING;
        store.transaction(s->{
            var b=s.database().selectFrom(binding).where(binding.SESSION_ID.eq(session.id())).fetchOne();
            if(b!=null) synchronizeEntryGrants(s,session,catalog.getEntry(b.getEntryId()),b.getParticipantId());
            return null;
        });
        var trigger=store.message(activation.inputMessageId()).orElseThrow();
        var profile=catalog.resolveRoleTemplateVersion(session.templateVersionId());
        Map<String,Object> context=new LinkedHashMap<>();
        var tasks=collaboration.tasks(session.participantId(),null,null,null,30);
        context.put("tasks",tasks.items().stream().map(t->Map.of("id",t.id(),"title",t.title(),"status",t.status(),
            "stage",t.stage(),"revision",t.revision(),"contentRevision",t.contentRevision())).toList());
        context.put("nextTaskCursor",tasks.nextCursor());
        context.put("replyToParticipantId",Objects.toString(session.replyToParticipantId(),""));
        context.put("replyRecipientId",Objects.toString(replyRecipient(store,session,trigger),""));
        context.put("replyAlreadyDelivered",store.database().fetchOne("select reply_message_id is not null from agent_activation where id=?",activationId).get(0,Boolean.class));
        context.put("schedules",store.schedules(session.id()));
        context.put("subscriptions",store.subscriptions(session.id()));
        context.put("toolRuns",store.toolRuns(session.id(),50).stream().map(run->{
            Map<String,Object> summary=new LinkedHashMap<>();summary.put("id",run.id());summary.put("taskId",run.taskId());
            summary.put("playbookVersionId",run.playbookVersionId());summary.put("status",run.status());
            summary.put("waiting",run.waiting());summary.put("failureReason",run.failureReason());return summary;
        }).toList());
        context.put("grants",store.grants(session.participantId()).stream()
            .filter(g->authorization.effective(store,g,new java.util.HashSet<>())).toList());
        if(trigger.taskId()!=null) {
            var detail=collaboration.summary(trigger.taskId(),session.participantId());
            var page=collaboration.context(trigger.taskId(),session.participantId(),null,20);
            Map<String,Object> current=new LinkedHashMap<>();current.put("task",detail.task());
            current.put("participants",detail.participants());current.put("permissions",detail.permissions());
            current.put("relations",detail.relations());current.put("sessions",detail.sessions());
            current.put("decisions",detail.decisions().reversed().stream().limit(20).toList());
            current.put("contextReferences",page.items().stream().map(e->Map.of("kind","TASK_CONTENT","taskId",e.taskId(),
                "entryId",e.id(),"revision",e.revision(),"contentKind",e.kind(),"title",e.title(),"state",e.state())).toList());
            current.put("nextContextCursor",page.nextCursor());context.put("currentTask",current);
        }
        return new ActivationRuntime(activation.id(),session.id(),session.participantId(),session.templateVersionId(),profile,trigger,
            context,session.workMemory(),Math.max(1,Math.min(256,maxToolSteps)));
    }

    /** Shared work exposes execution liveness, never the colleague's private transcript. */
    public ExecutionStatus executionStatus(String actor,String sessionId,String taskId) {
        authorization.active(store,actor);
        var session=store.session(sessionId,false).orElseThrow(()->notFound("Session"));
        boolean readable=session.participantId().equals(actor)||authorization.allowed(store,actor,"session.read",ScopeKind.SESSION,sessionId);
        if(taskId!=null) {
            authorization.requireTask(store,actor,taskId,Permission.VIEW);
            authorization.requireTask(store,session.participantId(),taskId,Permission.VIEW);
            if(store.tasks().sessions(taskId).stream().noneMatch(link->link.sessionId().equals(sessionId))) throw denied("instance is not linked to this work");
        } else if(!readable) throw denied("a shared work scope or Session read grant is required");
        var a=com.agentyard.persistence.jooq.Tables.AGENT_ACTIVATION;
        // taskId authorizes visibility of the instance, not a filter on execution.
        // A direct continuation can omit taskId, and a shared instance can be busy
        // with other work. Expose only liveness, never that work's identity or input.
        var id=store.database().select(a.ID).from(a).where(a.SESSION_ID.eq(sessionId))
            .orderBy(org.jooq.impl.DSL.when(a.STATUS.eq("RUNNING"),0).when(a.STATUS.eq("QUEUED"),1).otherwise(2),a.CREATED_AT.desc(),a.ID.desc()).limit(1).fetchOne(a.ID);
        var activation=id==null?null:store.activation(id,false).orElseThrow();
        String execution=activation==null?"IDLE":activation.status()==ActivationStatus.SUCCEEDED?
            java.util.Set.of("WAITING","BLOCKED").contains(Objects.toString(activation.completion(),""))?activation.completion():"IDLE":activation.status().name();
        return new ExecutionStatus(sessionId,session.status(),execution,activation==null?session.updatedAt():activation.updatedAt(),readable);
    }
    Activation running(AgentStore s,String activationId) {
        var activation=s.activation(activationId,false).orElseThrow(()->notFound("Activation"));
        if(activation.status()!=ActivationStatus.RUNNING) throw denied("activation is not running");
        var session=s.session(activation.sessionId(),false).orElseThrow();authorization.active(s,session.participantId());
        if(session.status()!=SessionStatus.ACTIVE) throw denied("Session is not active");
        var input=s.message(activation.inputMessageId()).orElseThrow();
        if(input.taskId()!=null) {
            authorization.requireTask(s,session.participantId(),input.taskId(),Permission.VIEW);
            var task=s.tasks().task(input.taskId(),false).orElseThrow();
            if(task.status()==TaskStatus.CANCELLED||task.status()==TaskStatus.COMPLETED||task.status()==TaskStatus.PAUSED)
                throw denied("the associated work is not active");
        }
        return activation;
    }
    @Transactional
    public void complete(String activationId,ActivationOutcome outcome) {
        store.transaction(s->{
            var activation=s.activation(activationId,true).orElseThrow(()->notFound("Activation"));
            // A control cancellation is not an execution receipt. Preserve the first actual late
            // outcome and usage without reopening the instance or sending another message.
            if(activation.status()!=ActivationStatus.RUNNING&&activation.status()!=ActivationStatus.CANCELLED) return null;
            if(s.database().fetchOne("select completion_received_at is not null from agent_activation where id=?",activationId).get(0,Boolean.class)) return null;
            var session=s.session(activation.sessionId(),true).orElseThrow();
            var input=s.message(activation.inputMessageId()).orElseThrow();
            boolean closed=activation.status()==ActivationStatus.CANCELLED||session.status()!=SessionStatus.ACTIVE;
            if(input.taskId()!=null) closed=closed||s.tasks().task(input.taskId(),false)
                .map(t->t.status()==TaskStatus.CANCELLED||t.status()==TaskStatus.PAUSED).orElse(true);
            var status=closed?ActivationStatus.CANCELLED:outcome.success()?ActivationStatus.SUCCEEDED:ActivationStatus.FAILED;
            s.complete(activationId,status,outcome);
            s.database().execute("update agent_activation set completion_received_at=now() where id=?",activationId);
            new com.agentyard.persistence.usage.LlmUsageStore(s.database(),s.json()).append(session,activation,input.taskId(),outcome.llmUsage());
            if(!closed&&outcome.success()) {
                var memory=merge(session.workMemory(),outcome.workMemory());
                s.saveSession(new Session(session.id(),session.participantId(),session.templateVersionId(),session.name(),session.status(),session.createdBy(),
                    session.replyToParticipantId(),memory,session.revision()+1,session.createdAt(),Instant.now()));
                boolean alreadyDelivered=s.database().fetchOne("select reply_message_id is not null from agent_activation where id=?",activationId).get(0,Boolean.class);
                if(!alreadyDelivered&&outcome.replyMessage()!=null&&!outcome.replyMessage().blocks().isEmpty()) {
                    String recipient=replyRecipient(s,session,input);
                    if(recipient!=null) {
                        try {
                            var reply=send(s,session.participantId(),new SendMessage(activationId+":reply",recipient,input.taskId(),input.id(),
                                MessageKind.RESULT,Map.of("blocks",outcome.replyMessage().blocks(),"metadata",outcome.replyMessage().metadata())));
                            s.database().execute("update agent_activation set reply_message_id=? where id=?",reply.id(),activationId);
                        } catch(ResponseStatusException deliveryDenied) {
                            // Revocation after model execution must not roll back its durable outcome/usage.
                            s.database().execute("update agent_activation set failure_reason=? where id=?","Reply delivery is no longer authorized",activationId);
                        }
                    }
                }
            }
            if(!closed&&!outcome.success()) {
                String recipient=replyRecipient(s,session,input);
                if(recipient!=null) {
                    try {
                        send(s,session.participantId(),new SendMessage(activationId+":failure",recipient,input.taskId(),input.id(),MessageKind.EVENT,
                            Map.of("text","本次执行未完成，执行记录和已产生的工具回执已保留。请核对已有结果，再决定继续、调整工作或请求帮助。",
                                "eventKind","ACTIVATION_FAILED","activationId",activationId,"sessionId",session.id())));
                    } catch(ResponseStatusException deliveryDenied) {
                        // A revoked audience must not receive a failure notification either.
                    }
                }
            }
            return null;
        });
    }
    @Transactional
    public Map<String,Object> memory(String sessionId,Map<String,Object> patch) {
        return store.transaction(s->{
            var session=s.session(sessionId,true).orElseThrow(()->notFound("Session"));
            if(patch!=null&&!patch.isEmpty()) {
                var memory=merge(session.workMemory(),patch);
                if(s.json().writeJson(memory).length()>200000) throw new IllegalArgumentException("working memory exceeds 200 KB; use resource references");
                session=new Session(session.id(),session.participantId(),session.templateVersionId(),session.name(),session.status(),session.createdBy(),
                    session.replyToParticipantId(),memory,session.revision()+1,session.createdAt(),Instant.now());s.saveSession(session);
            }
            return session.workMemory();
        });
    }
    Object idempotent(AgentStore s,String actor,String requestId,String operation,Object request,Function<AgentStore,Object> action) {
        required(requestId,"requestId");authorization.active(s,actor);s.lock("command:"+actor+":"+requestId);
        String digest=s.json().writeJson(canonical(mapper.convertValue(request,Object.class)));
        var receipt=s.receipt(actor,requestId);
        if(receipt.isPresent()) {
            if(!receipt.get().operation().equals(operation)||!receipt.get().digest().equals(digest))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"request ID was used with another command");
            return receipt.get().result();
        }
        Object result=action.apply(s);s.receipt(actor,requestId,operation,digest,result);return result;
    }
    static Object canonical(Object value) {
        if(value instanceof Map<?,?> m) { var sorted=new java.util.TreeMap<String,Object>();m.forEach((k,v)->sorted.put(k.toString(),canonical(v)));return sorted; }
        if(value instanceof List<?> list) return list.stream().map(AgentService::canonical).toList();return value;
    }
    /** Results travel toward the responsible recipient, never implicitly acknowledge themselves back to their sender. */
    public String replyRecipient(AgentStore s,Session session,Message input) {
        var sender=s.tasks().actor(input.senderId()).orElseThrow();
        if(sender.kind()==ActorKind.HUMAN) return sender.id();
        String recipient=input.senderId();
        if(input.senderId().equals(session.participantId())||java.util.Set.of(MessageKind.RESULT,MessageKind.EVENT,MessageKind.CONTROL).contains(input.kind()))
            recipient=session.replyToParticipantId();
        if(input.kind()==MessageKind.RESULT&&Objects.equals(recipient,input.senderId())) return null;
        return recipient;
    }
    static Map<String,Object> merge(Map<String,Object> initial,Map<String,Object> patch) {
        var result=new LinkedHashMap<>(initial);patch.forEach((k,v)->{if(v==null)result.remove(k);else result.put(k,v);});return result;
    }
    static String required(String value,String name) {
        if(value==null||value.isBlank()||value.length()>100000) throw new IllegalArgumentException(name+" is required and must be bounded");return value.trim();
    }
    static String id() { return UUID.randomUUID().toString(); }
}
