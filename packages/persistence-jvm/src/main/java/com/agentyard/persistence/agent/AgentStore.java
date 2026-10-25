package com.agentyard.persistence.agent;

import static com.agentyard.contracts.agent.AgentContracts.*;
import static com.agentyard.persistence.jooq.Tables.*;
import static com.agentyard.persistence.jooqsupport.JooqTimeSupport.*;

import com.agentyard.persistence.jooqsupport.JooqJsonbSupport;
import com.agentyard.persistence.task.TaskStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import tools.jackson.core.type.TypeReference;

/** The durable inbox and outbox. Notification transport never owns these records. */
public final class AgentStore {
    private final DSLContext db;
    private final JooqJsonbSupport json;
    public AgentStore(DSLContext db, JooqJsonbSupport json) { this.db=db;this.json=json; }
    public DSLContext database() { return db; }
    public TaskStore tasks() { return new TaskStore(db,json); }
    public JooqJsonbSupport json() { return json; }
    public <T> T transaction(Function<AgentStore,T> action) {
        return db.transactionResult(c -> action.apply(new AgentStore(DSL.using(c),json)));
    }
    public void lock(String key) { db.execute("select pg_advisory_xact_lock(hashtextextended(?,0))",key); }

    public Optional<Session> session(String id,boolean lock) {
        var t=AGENT_SESSION;var q=db.selectFrom(t).where(t.ID.eq(id));
        var r=lock?q.forUpdate().fetchOne():q.fetchOne();
        return r==null?Optional.empty():Optional.of(new Session(r.getId(),r.getParticipantId(),r.getTemplateVersionId(),r.getName(),
            SessionStatus.valueOf(r.getStatus()),r.getCreatedBy(),r.getReplyToParticipantId(),json.readObjectMap(r.getWorkMemory()),
            r.getRevision(),toInstant(r.getCreatedAt()),toInstant(r.getUpdatedAt())));
    }
    public Optional<Session> participantSession(String participantId) {
        var t=AGENT_SESSION;
        return db.select(t.ID).from(t).where(t.PARTICIPANT_ID.eq(participantId)).fetchOptional(t.ID).flatMap(id->session(id,false));
    }
    public void saveSession(Session s) {
        var t=AGENT_SESSION;
        db.insertInto(t).set(t.ID,s.id()).set(t.PARTICIPANT_ID,s.participantId()).set(t.TEMPLATE_VERSION_ID,s.templateVersionId())
            .set(t.NAME,s.name()).set(t.STATUS,s.status().name()).set(t.CREATED_BY,s.createdBy()).set(t.REPLY_TO_PARTICIPANT_ID,s.replyToParticipantId())
            .set(t.WORK_MEMORY,json.toJsonb(s.workMemory())).set(t.REVISION,s.revision()).set(t.CREATED_AT,toOffsetDateTime(s.createdAt()))
            .set(t.UPDATED_AT,toOffsetDateTime(s.updatedAt())).onConflict(t.ID).doUpdate().set(t.NAME,s.name()).set(t.STATUS,s.status().name())
            .set(t.WORK_MEMORY,json.toJsonb(s.workMemory())).set(t.REVISION,s.revision()).set(t.UPDATED_AT,toOffsetDateTime(s.updatedAt())).execute();
    }
    public Optional<ReceptionBinding> reception(String participantId,String entryId) {
        var t=AGENT_RECEPTION_BINDING;
        return db.selectFrom(t).where(t.PARTICIPANT_ID.eq(participantId).and(t.ENTRY_ID.eq(entryId)))
            .fetchOptional(r->new ReceptionBinding(r.getParticipantId(),r.getEntryId(),r.getSessionId(),toInstant(r.getCreatedAt())));
    }
    public void reception(ReceptionBinding b) {
        var t=AGENT_RECEPTION_BINDING;
        db.insertInto(t).set(t.PARTICIPANT_ID,b.participantId()).set(t.ENTRY_ID,b.entryId()).set(t.SESSION_ID,b.sessionId())
            .set(t.CREATED_AT,toOffsetDateTime(b.createdAt())).execute();
    }
    public Optional<Message> message(String id) {
        var t=COLLABORATION_MESSAGE;
        return db.selectFrom(t).where(t.ID.eq(id)).fetchOptional(r->new Message(r.getId(),r.getSenderId(),r.getRecipientId(),r.getTaskId(),
            r.getReplyToMessageId(),MessageKind.valueOf(r.getKind()),json.readObjectMap(r.getBody()),toInstant(r.getCreatedAt())));
    }
    public void message(Message m) {
        var t=COLLABORATION_MESSAGE;
        db.insertInto(t).set(t.ID,m.id()).set(t.SENDER_ID,m.senderId()).set(t.RECIPIENT_ID,m.recipientId()).set(t.TASK_ID,m.taskId())
            .set(t.REPLY_TO_MESSAGE_ID,m.replyToMessageId()).set(t.KIND,m.kind().name()).set(t.BODY,json.toJsonb(m.body()))
            .set(t.CREATED_AT,toOffsetDateTime(m.createdAt())).execute();
    }
    /** Oldest-first after a stable cursor; without a cursor returns the latest bounded window. */
    public List<Message> messages(String participantId,String counterpartyId,String afterId,int limit) {
        return messages(participantId,counterpartyId,afterId,null,limit);
    }
    public List<Message> messages(String participantId,String counterpartyId,String afterId,String beforeId,int limit) {
        var t=COLLABORATION_MESSAGE;
        var condition=t.SENDER_ID.eq(participantId).or(t.RECIPIENT_ID.eq(participantId));
        if(counterpartyId!=null) condition=condition.and(t.SENDER_ID.eq(counterpartyId).or(t.RECIPIENT_ID.eq(counterpartyId)));
        if(afterId!=null) {
            var cursor=message(afterId).orElseThrow();
            if(!participantId.equals(cursor.senderId())&&!participantId.equals(cursor.recipientId())) throw new IllegalArgumentException("invalid message cursor");
            condition=condition.and(DSL.row(t.CREATED_AT,t.ID).gt(DSL.row(toOffsetDateTime(cursor.createdAt()),cursor.id())));
        }
        if(beforeId!=null) {
            var cursor=message(beforeId).orElseThrow();
            if(!participantId.equals(cursor.senderId())&&!participantId.equals(cursor.recipientId())) throw new IllegalArgumentException("invalid message cursor");
            condition=condition.and(DSL.row(t.CREATED_AT,t.ID).lt(DSL.row(toOffsetDateTime(cursor.createdAt()),cursor.id())));
        }
        var order=afterId==null?List.of(t.CREATED_AT.desc(),t.ID.desc()):List.of(t.CREATED_AT.asc(),t.ID.asc());
        var ids=db.select(t.ID).from(t).where(condition).orderBy(order).limit(limit).fetch(t.ID);
        var values=ids.stream().map(id->message(id).orElseThrow()).toList();
        return afterId==null?values.reversed():values;
    }
    public void enqueue(Message message,Session recipient) {
        Instant now=Instant.now();String activationId=UUID.randomUUID().toString();
        var a=AGENT_ACTIVATION;
        db.insertInto(a).set(a.ID,activationId).set(a.SESSION_ID,recipient.id()).set(a.INPUT_MESSAGE_ID,message.id())
            .set(a.STATUS,"QUEUED").set(a.CREATED_AT,toOffsetDateTime(now)).set(a.UPDATED_AT,toOffsetDateTime(now)).execute();
        delivery(message.id(),recipient.id(),activationId,DeliveryStatus.PENDING);
    }
    public void delivery(String messageId,String sessionId,String activationId,DeliveryStatus status) {
        var d=COLLABORATION_DELIVERY;var now=toOffsetDateTime(Instant.now());
        db.insertInto(d).set(d.ID,UUID.randomUUID().toString()).set(d.MESSAGE_ID,messageId).set(d.SESSION_ID,sessionId)
            .set(d.ACTIVATION_ID,activationId).set(d.STATUS,status.name()).set(d.CREATED_AT,now).set(d.UPDATED_AT,now).execute();
    }
    public Optional<Activation> activation(String id,boolean lock) {
        var t=AGENT_ACTIVATION;var q=db.selectFrom(t).where(t.ID.eq(id));var r=lock?q.forUpdate().fetchOne():q.fetchOne();
        return r==null?Optional.empty():Optional.of(new Activation(r.getId(),r.getSessionId(),r.getInputMessageId(),ActivationStatus.valueOf(r.getStatus()),
            r.getFailureReason(),toInstant(r.getCreatedAt()),toInstant(r.getUpdatedAt()),
            (String)json.readObjectMap(r.getOutcome()).get("completion")));
    }
    public List<Activation> activations(String sessionId,int limit) {
        var t=AGENT_ACTIVATION;
        return db.select(t.ID).from(t).where(t.SESSION_ID.eq(sessionId)).orderBy(t.CREATED_AT.desc(),t.ID).limit(limit).fetch(t.ID)
            .stream().map(id->activation(id,false).orElseThrow()).toList();
    }
    public void complete(String id,ActivationStatus status,ActivationOutcome outcome) {
        var a=AGENT_ACTIVATION;var now=toOffsetDateTime(Instant.now());
        db.update(a).set(a.STATUS,status.name()).set(a.OUTCOME,json.toJsonb(outcome)).set(a.FAILURE_REASON,outcome.failureReason())
            .set(a.UPDATED_AT,now).where(a.ID.eq(id)).execute();
        var d=COLLABORATION_DELIVERY;
        var deliveryStatus=status==ActivationStatus.SUCCEEDED?DeliveryStatus.PROCESSED:
            status==ActivationStatus.CANCELLED?DeliveryStatus.CANCELLED:DeliveryStatus.FAILED;
        db.update(d).set(d.STATUS,deliveryStatus.name()).set(d.FAILURE_REASON,outcome.failureReason()).set(d.UPDATED_AT,now)
            .where(d.ACTIVATION_ID.eq(id)).execute();
    }
    /** Per-session row locking plus the partial unique index enforce one writer to persistent context. */
    public void claimActivations(int limit) {
        var s=AGENT_SESSION;var a=AGENT_ACTIVATION;var m=COLLABORATION_MESSAGE;var task=COLLABORATION_TASK;var actor=COLLABORATION_ACTOR;
        var eligible=DSL.exists(db.selectOne().from(m).leftJoin(task).on(task.ID.eq(m.TASK_ID))
            .where(m.ID.eq(a.INPUT_MESSAGE_ID).and(m.TASK_ID.isNull().or(task.STATUS.notIn("PAUSED","CANCELLED","COMPLETED")))));
        var sessions=db.select(s.ID).from(s).where(s.STATUS.eq("ACTIVE")
            .andExists(db.selectOne().from(actor).where(actor.ID.eq(s.PARTICIPANT_ID).and(actor.ACTIVE.isTrue())))
            .andExists(db.selectOne().from(a).where(a.SESSION_ID.eq(s.ID).and(a.STATUS.eq("QUEUED")).and(eligible)))
            .andNotExists(db.selectOne().from(a).where(a.SESSION_ID.eq(s.ID).and(a.STATUS.eq("RUNNING")))))
            .orderBy(s.UPDATED_AT,s.ID).limit(limit).forUpdate().skipLocked().fetch(s.ID);
        for(String sessionId:sessions) {
            var id=db.select(a.ID).from(a).where(a.SESSION_ID.eq(sessionId).and(a.STATUS.eq("QUEUED")).and(eligible))
                .orderBy(a.CREATED_AT,a.ID).limit(1).fetchOne(a.ID);
            if(id!=null) db.update(a).set(a.STATUS,"RUNNING").set(a.UPDATED_AT,toOffsetDateTime(Instant.now())).where(a.ID.eq(id)).execute();
        }
    }
    public List<String> pendingActivations() {
        var a=AGENT_ACTIVATION;
        return db.select(a.ID).from(a).where(a.STATUS.eq("RUNNING").and(a.DISPATCHED_AT.isNull()))
            .orderBy(a.CREATED_AT,a.ID).limit(50).fetch(a.ID);
    }
    public void activationDispatched(String id) {
        var a=AGENT_ACTIVATION;
        db.update(a).set(a.DISPATCHED_AT,toOffsetDateTime(Instant.now())).where(a.ID.eq(id)).execute();
        var d=COLLABORATION_DELIVERY;
        db.update(d).set(d.STATUS,"ACCEPTED").set(d.UPDATED_AT,toOffsetDateTime(Instant.now()))
            .where(d.ACTIVATION_ID.eq(id).and(d.STATUS.eq("PENDING"))).execute();
    }
    public List<Grant> grants(String participantId) {
        var t=COLLABORATION_GRANT;
        return db.select(t.ID).from(t).where(t.PARTICIPANT_ID.eq(participantId)).orderBy(t.CREATED_AT,t.ID).fetch(t.ID)
            .stream().map(id->grant(id).orElseThrow()).toList();
    }
    public Optional<Grant> grant(String id) {
        var t=COLLABORATION_GRANT;
        return db.selectFrom(t).where(t.ID.eq(id)).fetchOptional(r->new Grant(r.getId(),r.getParticipantId(),r.getOperation(),
            ScopeKind.valueOf(r.getScopeKind()),r.getScopeId(),r.getDelegable(),r.getGrantedBy(),r.getParentGrantId(),
            toInstant(r.getExpiresAt()),toInstant(r.getRevokedAt()),toInstant(r.getCreatedAt())));
    }
    public void grant(Grant g) {
        var t=COLLABORATION_GRANT;
        db.insertInto(t).set(t.ID,g.id()).set(t.PARTICIPANT_ID,g.participantId()).set(t.OPERATION,g.operation()).set(t.SCOPE_KIND,g.scopeKind().name())
            .set(t.SCOPE_ID,g.scopeId()).set(t.DELEGABLE,g.delegable()).set(t.GRANTED_BY,g.grantedBy()).set(t.PARENT_GRANT_ID,g.parentGrantId())
            .set(t.EXPIRES_AT,toOffsetDateTime(g.expiresAt())).set(t.REVOKED_AT,toOffsetDateTime(g.revokedAt()))
            .set(t.CREATED_AT,toOffsetDateTime(g.createdAt())).execute();
    }
    public void revoke(String grantId) {
        var t=COLLABORATION_GRANT;
        db.update(t).set(t.REVOKED_AT,toOffsetDateTime(Instant.now())).where(t.ID.eq(grantId)).execute();
    }
    public record Receipt(String operation,String digest,Object result) {}
    public Optional<Receipt> receipt(String participantId,String requestId) {
        var t=COLLABORATION_OPERATION_RECEIPT;
        return db.selectFrom(t).where(t.PARTICIPANT_ID.eq(participantId).and(t.REQUEST_ID.eq(requestId)))
            .fetchOptional(r->new Receipt(r.getOperation(),r.getDigest(),json.read(r.getResult(),Object.class)));
    }
    public void receipt(String participantId,String requestId,String operation,String digest,Object result) {
        var t=COLLABORATION_OPERATION_RECEIPT;
        db.insertInto(t).set(t.PARTICIPANT_ID,participantId).set(t.REQUEST_ID,requestId).set(t.OPERATION,operation).set(t.DIGEST,digest)
            .set(t.RESULT,json.toJsonb(result)).set(t.CREATED_AT,toOffsetDateTime(Instant.now())).execute();
    }
    public Optional<ToolRun> toolRun(String id,boolean lock) {
        var t=AGENT_TOOL_RUN;var q=db.selectFrom(t).where(t.ID.eq(id));var r=lock?q.forUpdate().fetchOne():q.fetchOne();
        return r==null?Optional.empty():Optional.of(new ToolRun(r.getId(),r.getSessionId(),r.getParticipantId(),r.getActivationId(),r.getTaskId(),
            r.getPlaybookVersionId(),ToolRunStatus.valueOf(r.getStatus()),json.readObjectMap(r.getInput()),json.readObjectMap(r.getResult()),
            json.readObjectMap(r.getWaiting()),r.getFailureReason(),toInstant(r.getCreatedAt()),toInstant(r.getUpdatedAt())));
    }
    public void toolRun(ToolRun r) {
        var t=AGENT_TOOL_RUN;
        db.insertInto(t).set(t.ID,r.id()).set(t.SESSION_ID,r.sessionId()).set(t.PARTICIPANT_ID,r.participantId()).set(t.ACTIVATION_ID,r.activationId())
            .set(t.TASK_ID,r.taskId()).set(t.PLAYBOOK_VERSION_ID,r.playbookVersionId()).set(t.STATUS,r.status().name()).set(t.INPUT,json.toJsonb(r.input()))
            .set(t.RESULT,json.toJsonb(r.result())).set(t.WAITING,json.toJsonb(r.waiting())).set(t.FAILURE_REASON,r.failureReason())
            .set(t.CREATED_AT,toOffsetDateTime(r.createdAt())).set(t.UPDATED_AT,toOffsetDateTime(r.updatedAt())).execute();
    }
    public void progress(String id,ToolRunProgress p) {
        var t=AGENT_TOOL_RUN;
        db.update(t).set(t.STATUS,p.status().name()).set(t.RESULT,json.toJsonb(p.result())).set(t.WAITING,json.toJsonb(p.waiting()))
            .set(t.FAILURE_REASON,p.failureReason()).set(t.UPDATED_AT,toOffsetDateTime(Instant.now())).where(t.ID.eq(id)).execute();
    }
    public List<ToolRun> toolRuns(String sessionId,int limit) {
        var t=AGENT_TOOL_RUN;
        return db.select(t.ID).from(t).where(t.SESSION_ID.eq(sessionId)).orderBy(t.CREATED_AT.desc(),t.ID).limit(limit).fetch(t.ID)
            .stream().map(id->toolRun(id,false).orElseThrow()).toList();
    }
    public List<Schedule> schedules(String sessionId) {
        var t=AGENT_SCHEDULE;
        return db.selectFrom(t).where(t.SESSION_ID.eq(sessionId)).orderBy(t.DUE_AT,t.ID).limit(200)
            .fetch(r->new Schedule(r.getId(),r.getSessionId(),r.getTaskId(),toInstant(r.getDueAt()),r.getInstruction(),r.getIntervalSeconds(),
                r.getActive(),r.getCreatedBy(),toInstant(r.getCreatedAt())));
    }
    public Optional<Schedule> schedule(String id) {
        var t=AGENT_SCHEDULE;
        return db.selectFrom(t).where(t.ID.eq(id)).fetchOptional(r->new Schedule(r.getId(),r.getSessionId(),r.getTaskId(),toInstant(r.getDueAt()),
            r.getInstruction(),r.getIntervalSeconds(),r.getActive(),r.getCreatedBy(),toInstant(r.getCreatedAt())));
    }
    public void schedule(Schedule s) {
        var t=AGENT_SCHEDULE;
        db.insertInto(t).set(t.ID,s.id()).set(t.SESSION_ID,s.sessionId()).set(t.TASK_ID,s.taskId()).set(t.DUE_AT,toOffsetDateTime(s.dueAt()))
            .set(t.INSTRUCTION,s.instruction()).set(t.INTERVAL_SECONDS,s.intervalSeconds()).set(t.ACTIVE,s.active()).set(t.CREATED_BY,s.createdBy())
            .set(t.CREATED_AT,toOffsetDateTime(s.createdAt())).onConflict(t.ID).doUpdate().set(t.DUE_AT,toOffsetDateTime(s.dueAt())).set(t.ACTIVE,s.active()).execute();
    }
    public void subscription(Subscription s) {
        var t=AGENT_SUBSCRIPTION;
        db.insertInto(t).set(t.ID,s.id()).set(t.SESSION_ID,s.sessionId()).set(t.SCOPE_KIND,s.scopeKind().name()).set(t.SCOPE_ID,s.scopeId())
            .set(t.EVENT_KINDS,json.toJsonb(s.eventKinds())).set(t.ACTIVE,s.active()).set(t.CREATED_BY,s.createdBy())
            .set(t.CREATED_AT,toOffsetDateTime(s.createdAt())).execute();
    }
    public Optional<Subscription> subscription(String id) {
        var t=AGENT_SUBSCRIPTION;
        return db.selectFrom(t).where(t.ID.eq(id)).fetchOptional(r->new Subscription(r.getId(),r.getSessionId(),ScopeKind.valueOf(r.getScopeKind()),
            r.getScopeId(),json.read(r.getEventKinds(),new TypeReference<List<String>>(){}),r.getActive(),r.getCreatedBy(),toInstant(r.getCreatedAt())));
    }
    public List<Subscription> subscriptions(String sessionId) {
        var t=AGENT_SUBSCRIPTION;
        return db.select(t.ID).from(t).where(t.SESSION_ID.eq(sessionId)).orderBy(t.CREATED_AT.desc(),t.ID).limit(200).fetch(t.ID)
            .stream().map(id->subscription(id).orElseThrow()).toList();
    }
}
