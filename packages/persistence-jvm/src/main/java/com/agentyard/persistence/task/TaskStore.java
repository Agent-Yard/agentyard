package com.agentyard.persistence.task;

import static com.agentyard.persistence.jooq.Tables.*;
import static com.agentyard.persistence.jooqsupport.JooqTimeSupport.*;
import static com.agentyard.contracts.task.TaskContracts.*;

import com.agentyard.persistence.jooqsupport.JooqJsonbSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import tools.jackson.core.type.TypeReference;

/** Explicit transaction boundary shared by all collaboration mutations. */
public final class TaskStore {
    private static final TypeReference<List<RoleDefinition>> ROLES = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<Set<String>> STRING_SET = new TypeReference<>() {};
    private final DSLContext db;
    private final JooqJsonbSupport json;

    public TaskStore(DSLContext db, JooqJsonbSupport json) { this.db = db; this.json = json; }
    public <T> T transaction(Function<TaskStore, T> action) {
        return db.transactionResult(configuration -> action.apply(new TaskStore(DSL.using(configuration), json)));
    }
    public String serialize(Object value) { return json.writeJson(value); }
    public void graphLock() { db.execute("select pg_advisory_xact_lock(713041982)"); }

    public Actor ensureActor(Actor actor) {
        var t = COLLABORATION_ACTOR;
        db.insertInto(t).set(t.ID, actor.id()).set(t.KIND, actor.kind().name()).set(t.SUBJECT_ID, actor.subjectId())
            .set(t.DISPLAY_NAME, actor.displayName()).set(t.ACTIVE, actor.active()).set(t.CREATED_AT, toOffsetDateTime(actor.createdAt()))
            .onConflict(t.KIND, t.SUBJECT_ID).doNothing().execute();
        return actorBySubject(actor.kind(), actor.subjectId()).orElseThrow();
    }
    public Optional<Actor> actor(String id) {
        var t = COLLABORATION_ACTOR;
        return db.selectFrom(t).where(t.ID.eq(id)).fetchOptional(r -> new Actor(r.getId(), ActorKind.valueOf(r.getKind()), r.getSubjectId(), r.getDisplayName(), r.getActive(), toInstant(r.getCreatedAt())));
    }
    public Optional<Actor> actorBySubject(ActorKind kind, String subject) {
        var t = COLLABORATION_ACTOR;
        return db.select(t.ID).from(t).where(t.KIND.eq(kind.name()).and(t.SUBJECT_ID.eq(subject))).fetchOptional(t.ID).flatMap(this::actor);
    }
    public List<Actor> actors(String query) {
        var t = COLLABORATION_ACTOR;
        return db.selectFrom(t).where(t.ACTIVE.isTrue().and(t.DISPLAY_NAME.containsIgnoreCase(query == null ? "" : query)))
            .orderBy(t.DISPLAY_NAME, t.ID).limit(100)
            .fetch(r -> new Actor(r.getId(), ActorKind.valueOf(r.getKind()), r.getSubjectId(), r.getDisplayName(), r.getActive(), toInstant(r.getCreatedAt())));
    }
    public Optional<Organization> organization(String id, boolean lock) {
        var t = COLLABORATION_ORGANIZATION;
        var query = db.selectFrom(t).where(t.ID.eq(id));
        var r = lock ? query.forUpdate().fetchOne() : query.fetchOne();
        return r == null ? Optional.empty() : Optional.of(new Organization(r.getId(), r.getParentId(), r.getName(), r.getDescription(),
            json.read(r.getRoles(), ROLES), r.getRevision(), r.getCreatedBy(), toInstant(r.getCreatedAt()), toInstant(r.getUpdatedAt())));
    }
    public List<Organization> organizations(String actorId) {
        var m = COLLABORATION_MEMBERSHIP;
        return db.select(m.ORGANIZATION_ID).from(m).where(m.ACTOR_ID.eq(actorId)).orderBy(m.ORGANIZATION_ID)
            .fetch(m.ORGANIZATION_ID).stream().map(id -> organization(id, false).orElseThrow()).toList();
    }
    public void saveOrganization(Organization o) {
        var t = COLLABORATION_ORGANIZATION;
        db.insertInto(t).set(t.ID, o.id()).set(t.PARENT_ID, o.parentId()).set(t.NAME, o.name()).set(t.DESCRIPTION, o.description())
            .set(t.ROLES, json.toJsonb(o.roles())).set(t.REVISION, o.revision()).set(t.CREATED_BY, o.createdBy())
            .set(t.CREATED_AT, toOffsetDateTime(o.createdAt())).set(t.UPDATED_AT, toOffsetDateTime(o.updatedAt()))
            .onConflict(t.ID).doUpdate().set(t.NAME, o.name()).set(t.DESCRIPTION, o.description()).set(t.ROLES, json.toJsonb(o.roles()))
            .set(t.REVISION, o.revision()).set(t.UPDATED_AT, toOffsetDateTime(o.updatedAt())).execute();
    }
    public List<Participant> members(String organizationId) {
        var t = COLLABORATION_MEMBERSHIP;
        return db.selectFrom(t).where(t.ORGANIZATION_ID.eq(organizationId)).orderBy(t.ACTOR_ID)
            .fetch(r -> new Participant(r.getActorId(), json.read(r.getRoles(), STRINGS)));
    }
    public void member(String organizationId, String actorId, List<String> roles) {
        var t = COLLABORATION_MEMBERSHIP;
        if (roles.isEmpty()) { db.deleteFrom(t).where(t.ORGANIZATION_ID.eq(organizationId).and(t.ACTOR_ID.eq(actorId))).execute(); return; }
        db.insertInto(t).set(t.ORGANIZATION_ID, organizationId).set(t.ACTOR_ID, actorId).set(t.ROLES, json.toJsonb(roles))
            .onConflict(t.ORGANIZATION_ID, t.ACTOR_ID).doUpdate().set(t.ROLES, json.toJsonb(roles)).execute();
    }
    public Optional<Task> task(String id, boolean lock) {
        var t = COLLABORATION_TASK;
        var query = db.selectFrom(t).where(t.ID.eq(id));
        var r = lock ? query.forUpdate().fetchOne() : query.fetchOne();
        return r == null ? Optional.empty() : Optional.of(new Task(r.getId(), r.getOrganizationId(), r.getTitle(), r.getGoal(),
            TaskStatus.valueOf(r.getStatus()), r.getStage(), r.getRevision(), r.getContentRevision(), json.readObjectMap(r.getAttributes()),
            json.read(r.getPolicy(), Policy.class), r.getInitiatedBy(), r.getCreatedBy(), r.getConfirmerId(), r.getSubmittedBy(), toInstant(r.getCreatedAt()), toInstant(r.getUpdatedAt())));
    }
    public List<Task> tasks(String actorId, String beforeId, TaskStatus status, String organizationId, String query, int limit) {
        var t = COLLABORATION_TASK; var p = COLLABORATION_PARTICIPANT;
        Condition condition = t.ID.in(db.select(p.TASK_ID).from(p).where(p.ACTOR_ID.eq(actorId)));
        if (beforeId != null) condition = condition.and(t.ID.lt(beforeId));
        if (status != null) condition = condition.and(t.STATUS.eq(status.name()));
        if (organizationId != null) condition = condition.and(t.ORGANIZATION_ID.eq(organizationId));
        if (query != null && !query.isBlank()) condition=condition.and(t.TITLE.containsIgnoreCase(query).or(t.GOAL.containsIgnoreCase(query)));
        return db.select(t.ID).from(t).where(condition).orderBy(t.ID.desc()).limit(limit).fetch(t.ID).stream()
            .map(id -> task(id, false).orElseThrow()).toList();
    }
    public void saveTask(Task task) {
        var t = COLLABORATION_TASK;
        db.insertInto(t).set(t.ID, task.id()).set(t.ORGANIZATION_ID, task.organizationId()).set(t.TITLE, task.title()).set(t.GOAL, task.goal())
            .set(t.STATUS, task.status().name()).set(t.STAGE, task.stage()).set(t.REVISION, task.revision()).set(t.CONTENT_REVISION, task.contentRevision())
            .set(t.ATTRIBUTES, json.toJsonb(task.attributes())).set(t.POLICY, json.toJsonb(task.policy())).set(t.CREATED_BY, task.createdBy())
            .set(t.INITIATED_BY, task.initiatedBy()).set(t.CONFIRMER_ID, task.confirmerId())
            .set(t.SUBMITTED_BY, task.submittedBy()).set(t.CREATED_AT, toOffsetDateTime(task.createdAt())).set(t.UPDATED_AT, toOffsetDateTime(task.updatedAt()))
            .onConflict(t.ID).doUpdate().set(t.TITLE, task.title()).set(t.GOAL, task.goal()).set(t.STATUS, task.status().name()).set(t.STAGE, task.stage())
            .set(t.REVISION, task.revision()).set(t.CONTENT_REVISION, task.contentRevision()).set(t.ATTRIBUTES, json.toJsonb(task.attributes()))
            .set(t.POLICY, json.toJsonb(task.policy())).set(t.CONFIRMER_ID, task.confirmerId()).set(t.SUBMITTED_BY, task.submittedBy()).set(t.UPDATED_AT, toOffsetDateTime(task.updatedAt())).execute();
    }
    public List<Participant> participants(String taskId) {
        var t = COLLABORATION_PARTICIPANT;
        return db.selectFrom(t).where(t.TASK_ID.eq(taskId)).orderBy(t.ACTOR_ID).fetch(r -> new Participant(r.getActorId(), json.read(r.getRoles(), STRINGS)));
    }
    public void participant(String taskId, String actorId, List<String> roles) {
        var t = COLLABORATION_PARTICIPANT;
        if (roles.isEmpty()) { db.deleteFrom(t).where(t.TASK_ID.eq(taskId).and(t.ACTOR_ID.eq(actorId))).execute(); return; }
        db.insertInto(t).set(t.TASK_ID, taskId).set(t.ACTOR_ID, actorId).set(t.ROLES, json.toJsonb(roles))
            .onConflict(t.TASK_ID, t.ACTOR_ID).doUpdate().set(t.ROLES, json.toJsonb(roles)).execute();
    }
    public Optional<ContextEntry> entry(String id) {
        var t = COLLABORATION_CONTEXT_ENTRY;
        return db.selectFrom(t).where(t.ID.eq(id)).fetchOptional(r -> new ContextEntry(r.getId(), r.getTaskId(), ContextKind.valueOf(r.getKind()), r.getTitle(),
            json.readObjectMap(r.getBody()), ContextState.valueOf(r.getState()), json.read(r.getVisibleToRoles(), STRING_SET), json.readObjectMap(r.getSource()),
            r.getAuthorId(), r.getRevision(), toInstant(r.getCreatedAt()), toInstant(r.getUpdatedAt())));
    }
    public List<ContextEntry> context(String taskId, String actorId, Set<String> roles, String beforeId, int limit) {
        var t = COLLABORATION_CONTEXT_ENTRY;
        Condition condition = t.TASK_ID.eq(taskId).and(visible(t.VISIBLE_TO_ROLES, roles).or(t.AUTHOR_ID.eq(actorId)));
        if (beforeId != null) condition = condition.and(t.ID.lt(beforeId));
        return db.select(t.ID).from(t).where(condition).orderBy(t.ID.desc()).limit(limit).fetch(t.ID).stream().map(id -> entry(id).orElseThrow()).toList();
    }
    public void saveEntry(ContextEntry e, String editorId) {
        var t = COLLABORATION_CONTEXT_ENTRY;
        db.insertInto(t).set(t.ID, e.id()).set(t.TASK_ID, e.taskId()).set(t.KIND, e.kind().name()).set(t.TITLE, e.title()).set(t.BODY, json.toJsonb(e.body()))
            .set(t.STATE, e.state().name()).set(t.VISIBLE_TO_ROLES, json.toJsonb(e.visibleToRoles())).set(t.SOURCE, json.toJsonb(e.source()))
            .set(t.AUTHOR_ID, e.authorId()).set(t.REVISION, e.revision()).set(t.CREATED_AT, toOffsetDateTime(e.createdAt())).set(t.UPDATED_AT, toOffsetDateTime(e.updatedAt()))
            .onConflict(t.ID).doUpdate().set(t.TITLE, e.title()).set(t.BODY, json.toJsonb(e.body())).set(t.STATE, e.state().name())
            .set(t.VISIBLE_TO_ROLES, json.toJsonb(e.visibleToRoles())).set(t.SOURCE, json.toJsonb(e.source())).set(t.REVISION, e.revision())
            .set(t.UPDATED_AT, toOffsetDateTime(e.updatedAt())).execute();
        var v = COLLABORATION_CONTEXT_VERSION;
        db.insertInto(v).set(v.ENTRY_ID, e.id()).set(v.REVISION, e.revision()).set(v.TASK_ID, e.taskId()).set(v.ACTOR_ID, editorId)
            .set(v.SNAPSHOT, json.toJsonb(e)).set(v.CREATED_AT, toOffsetDateTime(e.updatedAt())).execute();
    }
    public List<ContextEntry> versions(String entryId, int limit) {
        var t = COLLABORATION_CONTEXT_VERSION;
        return db.select(t.SNAPSHOT).from(t).where(t.ENTRY_ID.eq(entryId)).orderBy(t.REVISION.desc()).limit(limit)
            .fetch(r -> json.read(r.value1(), ContextEntry.class));
    }
    public Optional<ContextEntry> version(String entryId,long revision) {
        var t=COLLABORATION_CONTEXT_VERSION;
        return db.select(t.SNAPSHOT).from(t).where(t.ENTRY_ID.eq(entryId).and(t.REVISION.eq(revision)))
            .fetchOptional(r->json.read(r.value1(),ContextEntry.class));
    }
    public List<Relation> relations(String taskId) {
        var t = COLLABORATION_TASK_RELATION;
        return db.selectFrom(t).where(t.TASK_ID.eq(taskId)).orderBy(t.RELATED_TASK_ID).fetch(r -> new Relation(r.getTaskId(), r.getRelatedTaskId(), RelationKind.valueOf(r.getKind())));
    }
    public void relation(Relation r, boolean remove) {
        var t = COLLABORATION_TASK_RELATION;
        if (remove) { db.deleteFrom(t).where(t.TASK_ID.eq(r.taskId()).and(t.RELATED_TASK_ID.eq(r.relatedTaskId())).and(t.KIND.eq(r.kind().name()))).execute(); return; }
        db.insertInto(t).set(t.TASK_ID, r.taskId()).set(t.RELATED_TASK_ID, r.relatedTaskId()).set(t.KIND, r.kind().name()).onConflictDoNothing().execute();
    }
    public boolean wouldCycle(String source, String target) {
        return !db.fetch("""
            with recursive reachable(id) as (
              select cast(? as text)
              union select r.related_task_id from collaboration_task_relation r join reachable p on r.task_id = p.id
              where r.kind in ('CHILD', 'DEPENDS_ON')
            ) select id from reachable where id = ?
            """, target, source).isEmpty();
    }
    public List<SessionLink> sessions(String taskId) {
        var t = COLLABORATION_TASK_SESSION;
        return db.selectFrom(t).where(t.TASK_ID.eq(taskId)).orderBy(t.CREATED_AT).fetch(r -> new SessionLink(r.getTaskId(), r.getSessionId(), r.getActorId(), toInstant(r.getCreatedAt())));
    }
    public List<String> sessionTasks(String sessionId, String actorId) {
        var t = COLLABORATION_TASK_SESSION;
        return db.select(t.TASK_ID).from(t).where(t.SESSION_ID.eq(sessionId).and(t.ACTOR_ID.eq(actorId))).orderBy(t.TASK_ID).fetch(t.TASK_ID);
    }
    public List<String> sessionsForParticipant(String sessionId) {
        var t = COLLABORATION_TASK_SESSION;
        return db.select(t.TASK_ID).from(t).where(t.SESSION_ID.eq(sessionId)).orderBy(t.TASK_ID).fetch(t.TASK_ID);
    }
    public Optional<String> sessionParticipant(String sessionId) {
        return db.select(AGENT_SESSION.PARTICIPANT_ID).from(AGENT_SESSION)
            .where(AGENT_SESSION.ID.eq(sessionId)).fetchOptional(AGENT_SESSION.PARTICIPANT_ID);
    }
    public void session(SessionLink link, boolean remove) {
        var t = COLLABORATION_TASK_SESSION;
        if (remove) { db.deleteFrom(t).where(t.TASK_ID.eq(link.taskId()).and(t.SESSION_ID.eq(link.sessionId())).and(t.ACTOR_ID.eq(link.actorId()))).execute(); return; }
        db.insertInto(t).set(t.TASK_ID, link.taskId()).set(t.SESSION_ID, link.sessionId()).set(t.ACTOR_ID, link.actorId())
            .set(t.CREATED_AT, toOffsetDateTime(link.createdAt())).onConflictDoNothing().execute();
    }
    public void event(Event event) {
        var t = COLLABORATION_TASK_EVENT;
        db.insertInto(t).set(t.TASK_ID, event.taskId()).set(t.REVISION, event.revision()).set(t.ACTOR_ID, event.actorId()).set(t.KIND, event.kind())
            .set(t.DATA, json.toJsonb(event.data())).set(t.VISIBLE_TO_ROLES, json.toJsonb(event.visibleToRoles())).set(t.CREATED_AT, toOffsetDateTime(event.createdAt())).execute();
    }
    public List<Event> events(String taskId, String actorId, Set<String> roles, long afterRevision, int limit) {
        var t = COLLABORATION_TASK_EVENT;
        return db.selectFrom(t).where(t.TASK_ID.eq(taskId).and(t.REVISION.gt(afterRevision)).and(visible(t.VISIBLE_TO_ROLES, roles).or(t.ACTOR_ID.eq(actorId))))
            .orderBy(t.REVISION).limit(limit).fetch(r -> new Event(r.getTaskId(), r.getRevision(), r.getActorId(), r.getKind(),
                json.readObjectMap(r.getData()), json.read(r.getVisibleToRoles(), STRING_SET), toInstant(r.getCreatedAt())));
    }
    public record Receipt(String digest, CommandResult result) {}
    public Optional<Receipt> receipt(String taskId, String actorId, String requestId) {
        var t = COLLABORATION_TASK_COMMAND;
        return db.selectFrom(t).where(t.TASK_ID.eq(taskId).and(t.ACTOR_ID.eq(actorId)).and(t.REQUEST_ID.eq(requestId)))
            .fetchOptional(r -> new Receipt(r.getDigest(), json.read(r.getResult(), CommandResult.class)));
    }
    public void receipt(String taskId, String actorId, String requestId, String digest, CommandResult result) {
        var t = COLLABORATION_TASK_COMMAND;
        db.insertInto(t).set(t.TASK_ID, taskId).set(t.ACTOR_ID, actorId).set(t.REQUEST_ID, requestId).set(t.DIGEST, digest).set(t.RESULT, json.toJsonb(result)).execute();
    }
    public List<Decision> decisions(String taskId) {
        return decisions(taskId,null,200).reversed();
    }
    public List<Decision> decisions(String taskId,String cursor,int limit) {
        var t = COLLABORATION_DECISION;
        var condition=t.TASK_ID.eq(taskId);
        if(cursor!=null) {
            var previous=db.selectFrom(t).where(t.ID.eq(cursor).and(t.TASK_ID.eq(taskId))).fetchOptional()
                .orElseThrow(()->new IllegalArgumentException("invalid decision cursor"));
            condition=condition.and(DSL.row(t.CREATED_AT,t.ID).lt(DSL.row(previous.getCreatedAt(),previous.getId())));
        }
        return db.selectFrom(t).where(condition).orderBy(t.CREATED_AT.desc(),t.ID.desc()).limit(limit)
            .fetch(r -> new Decision(r.getId(), r.getTaskId(), r.getContentRevision(), DecisionKind.valueOf(r.getKind()),
                r.getDecidedBy(), r.getExecutedBy(), r.getSourceMessageId(), r.getComment(), toInstant(r.getCreatedAt())));
    }
    public void decision(Decision decision) {
        var t = COLLABORATION_DECISION;
        db.insertInto(t).set(t.ID,decision.id()).set(t.TASK_ID,decision.taskId())
            .set(t.CONTENT_REVISION,decision.contentRevision()).set(t.KIND,decision.kind().name())
            .set(t.DECIDED_BY,decision.decidedBy()).set(t.EXECUTED_BY,decision.executedBy())
            .set(t.SOURCE_MESSAGE_ID,decision.sourceMessageId()).set(t.COMMENT,decision.comment())
            .set(t.CREATED_AT,toOffsetDateTime(decision.createdAt())).execute();
    }
    public void publish(String taskId, String kind, String actorId, Map<String,Object> payload) {
        var t = COLLABORATION_EVENT_OUTBOX;
        db.insertInto(t).set(t.ID,java.util.UUID.randomUUID().toString()).set(t.SCOPE_KIND,"TASK")
            .set(t.SCOPE_ID,taskId).set(t.KIND,kind).set(t.ACTOR_ID,actorId).set(t.PAYLOAD,json.toJsonb(payload))
            .set(t.CREATED_AT,toOffsetDateTime(Instant.now())).execute();
    }
    public DSLContext database() { return db; }
    private Condition visible(Field<JSONB> field, Set<String> roles) {
        return DSL.condition("jsonb_array_length({0}) = 0 or jsonb_exists_any({0}, {1})", field, DSL.val(roles.toArray(String[]::new)));
    }
}
