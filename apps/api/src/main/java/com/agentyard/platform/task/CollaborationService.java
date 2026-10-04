package com.agentyard.platform.task;

import static com.agentyard.contracts.task.TaskContracts.*;
import static com.agentyard.platform.task.TaskAccess.*;
import com.agentyard.persistence.task.TaskStore;
import com.agentyard.persistence.jooqsupport.JooqJsonbSupport;
import com.agentyard.platform.auth.CurrentUserResolver;
import java.time.Instant;
import java.util.*;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class CollaborationService {
    final TaskStore store;
    final ObjectMapper mapper;
    private final CurrentUserResolver users;
    final TaskRuntimeResolver runtime;
    public CollaborationService(DSLContext db, ObjectMapper mapper, CurrentUserResolver users, TaskRuntimeResolver runtime) {
        this.store = new TaskStore(db, new JooqJsonbSupport(mapper)); this.mapper = mapper; this.users = users; this.runtime = runtime;
    }
    public Actor currentActor() {
        var user = users.resolveCurrentUser();
        if (!user.isActive()) throw missing();
        return store.ensureActor(new Actor(id(), ActorKind.HUMAN, user.id(), user.displayName(), true, Instant.now()));
    }
    public List<Actor> actors(String query) { currentActor(); return store.actors(query); }
    public Actor registerAgent(CreateAgentActor request) {
        currentActor();
        return store.ensureActor(new Actor(id(), ActorKind.AGENT, request.agentId(), runtime.agentName(request.agentId()), true, Instant.now()));
    }
    public List<Organization> organizations() { return store.organizations(currentActor().id()); }
    public OrganizationDetail organization(String id) {
        String actor = currentActor().id();
        return store.transaction(s -> organization(s, id, actor, false));
    }
    private OrganizationDetail organization(TaskStore s, String id, String actor, boolean lock) {
        Organization org = s.organization(id, lock).orElseThrow(TaskAccess::missing);
        var members = s.members(id); var permissions = permissions(org.roles(), roles(members, actor));
        require(permissions, Permission.VIEW);
        return new OrganizationDetail(org, members, permissions);
    }
    public OrganizationDetail createOrganization(CreateOrganization request) {
        String actor = currentActor().id();
        return store.transaction(s -> {
            if (request.parentId() != null) require(organization(s, request.parentId(), actor, true).permissions(), Permission.MANAGE);
            var definitions = validateRoles(request.roles()); Instant now = Instant.now();
            Organization org = new Organization(id(), request.parentId(), text(request.name(), "name"), Objects.requireNonNullElse(request.description(), ""), definitions, 1, actor, now, now);
            s.saveOrganization(org); s.member(org.id(), actor, List.of(managerRole(definitions)));
            return organization(s, org.id(), actor, false);
        });
    }
    public OrganizationDetail updateOrganization(String id, UpdateOrganization request) {
        String actor = currentActor().id();
        return store.transaction(s -> {
            var detail = organization(s, id, actor, true); var org = detail.organization(); require(detail.permissions(), Permission.MANAGE);
            revision(org.revision(), request.expectedRevision()); var definitions = validateRoles(request.roles());
            detail.members().forEach(p -> validateAssignments(definitions, p.roles())); retainManager(definitions, detail.members());
            s.saveOrganization(new Organization(id, org.parentId(), text(request.name(), "name"), Objects.requireNonNullElse(request.description(), ""), definitions, org.revision()+1, org.createdBy(), org.createdAt(), Instant.now()));
            return organization(s, id, actor, false);
        });
    }
    public OrganizationDetail setMember(String id, SetMember request) {
        String actor = currentActor().id();
        return store.transaction(s -> {
            var detail = organization(s, id, actor, true); var org = detail.organization(); require(detail.permissions(), Permission.MANAGE);
            revision(org.revision(), request.expectedRevision()); activeActor(s, request.actorId());
            validateAssignments(org.roles(), request.roles()); s.member(id, request.actorId(), request.roles()); retainManager(org.roles(), s.members(id));
            // The manager may remove their own membership; return the resulting organization without a second access check.
            var updated = new Organization(id, org.parentId(), org.name(), org.description(), org.roles(), org.revision()+1, org.createdBy(), org.createdAt(), Instant.now());
            s.saveOrganization(updated);
            return new OrganizationDetail(updated, s.members(id), permissions(org.roles(), roles(s.members(id), actor)));
        });
    }
    public Task createTask(CreateTask request) { return createTask(currentActor().id(), request); }
    Task createTask(String actor, CreateTask request) {
        return store.transaction(s -> {
            activeActor(s, actor);
            if (request.organizationId() != null) require(organization(s, request.organizationId(), actor, true).permissions(), Permission.CONTRIBUTE);
            Policy policy = validatePolicy(request.policy()); Instant now = Instant.now();
            Task task = new Task(id(), request.organizationId(), text(request.title(), "title"), text(request.goal(), "goal"), TaskStatus.OPEN,
                "", 1, 1, object(request.attributes()), policy, actor, null, now, now);
            s.saveTask(task); s.participant(task.id(), actor, List.of(managerRole(policy.roles())));
            s.event(new Event(task.id(), 1, actor, "CREATED", Map.of(), Set.of(), now));
            return task;
        });
    }
    public Page<Task> tasks(String cursor, TaskStatus status, String organizationId, int limit) {
        String actor = currentActor().id(); int size = limit(limit);
        var items = store.tasks(actor, cursor, status, organizationId, size+1);
        return new Page<>(items.stream().limit(size).toList(), items.size()>size ? items.get(size-1).id() : null);
    }
    public TaskDetail detail(String id) { return detail(id, currentActor().id()); }
    TaskDetail detail(String id, String actor) { return store.transaction(s -> detail(s, task(s, id, actor, false), actor)); }
    TaskDetail detail(TaskStore s, Task task, String actor) {
        var participants = s.participants(task.id()); var roles = roles(participants, actor);
        var context = s.context(task.id(), actor, roles, null, 201);
        // The detail's context is a preview; /context is the complete paginated read API.
        return new TaskDetail(task, participants, context.stream().limit(200).toList(), s.relations(task.id()).stream()
            .filter(r -> canView(s, r.relatedTaskId(), actor)).toList(), s.sessions(task.id()).stream().filter(l -> l.actorId().equals(actor)).toList(),
            s.executions(task.id()).stream().limit(200).map(e -> executionVisible(s, task, actor, e) ? e :
                new Execution(e.id(),e.taskId(),e.actorId(),e.requestedBy(),e.kind(),e.status(),"",Map.of(),Map.of(),Map.of(),null,e.createdAt(),e.updatedAt())).toList(),
            s.reviews(task.id()), permissions(task.policy().roles(), roles));
    }
    public Page<ContextEntry> context(String id, String cursor, int limit) {
        String actor = currentActor().id(); return context(id, actor, cursor, limit);
    }
    Page<ContextEntry> context(String id, String actor, String cursor, int limit) {
        return store.transaction(s -> {
            Task task = task(s, id, actor, false); int size = limit(limit);
            var entries = s.context(id, actor, roles(s.participants(id), actor), cursor, size+1);
            return new Page<>(entries.stream().limit(size).toList(), entries.size()>size ? entries.get(size-1).id() : null);
        });
    }
    public List<ContextEntry> versions(String taskId, String entryId) {
        String actor = currentActor().id(); return store.transaction(s -> {
            task(s, taskId, actor, false); var roles = roles(s.participants(taskId), actor);
            ContextEntry entry = s.entry(entryId).orElseThrow(TaskAccess::missing);
            if (!entry.taskId().equals(taskId) || !visible(entry, actor, roles)) throw missing();
            return s.versions(entryId, 100).stream().filter(e -> visible(e, actor, roles)).toList();
        });
    }
    public List<Event> events(String id, long after, int limit) {
        String actor = currentActor().id(); return store.transaction(s -> {
            task(s, id, actor, false); return s.events(id, actor, roles(s.participants(id), actor), after, limit(limit));
        });
    }
    public List<Task> sessionTasks(String sessionId) {
        String actor = currentActor().id(); return store.sessionTasks(sessionId, actor).stream().filter(id -> canView(store,id,actor)).map(id -> store.task(id,false).orElseThrow()).toList();
    }
    Task task(TaskStore s, String id, String actor, boolean lock) {
        activeActor(s, actor); Task task = s.task(id, lock).orElseThrow(TaskAccess::missing);
        require(permissions(task.policy().roles(), roles(s.participants(id), actor)), Permission.VIEW); return task;
    }
    boolean canView(TaskStore s, String id, String actor) {
        return s.task(id,false).map(t -> permissions(t.policy().roles(), roles(s.participants(id),actor)).contains(Permission.VIEW)).orElse(false);
    }
    boolean executionVisible(TaskStore s, Task task, String actor, Execution e) {
        if (!e.actorId().equals(actor) && !e.requestedBy().equals(actor)) return false;
        var roles = roles(s.participants(task.id()), actor);
        return stringList(e.input().get("contextEntryIds")).stream().allMatch(id -> s.entry(id).map(entry -> visible(entry, actor, roles)).orElse(false));
    }
    static Actor activeActor(TaskStore s, String id) { return s.actor(id).filter(Actor::active).orElseThrow(TaskAccess::missing); }
    static Policy validatePolicy(Policy policy) {
        if (policy == null) policy = Policy.defaults(); validateRoles(policy.roles());
        if (policy.requiredApprovals()<0 || policy.requiredApprovals()>100) bad("required approvals must be between 0 and 100");
        return policy;
    }
    static void revision(long current, long expected) { if (current != expected) conflict("revision changed; refresh and reconcile before retrying"); }
    static int limit(int limit) { return Math.max(1, Math.min(limit, 200)); }
    static String id() { return UUID.randomUUID().toString(); }
    static Map<String,Object> object(Map<String,Object> value) { return value == null ? Map.of() : value; }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value) {
        if (value == null) return Map.of(); if (!(value instanceof Map)) bad("expected a JSON object"); return (Map<String,Object>)value;
    }
    static List<String> stringList(Object value) {
        if (value == null) return List.of(); if (!(value instanceof List<?>)) bad("expected a list");
        return ((List<?>)value).stream().map(v -> { if (!(v instanceof String)) bad("expected a string list"); return (String)v; }).toList();
    }
    static String str(Map<String,Object> data, String key) { return data.get(key) == null ? null : text(data.get(key).toString(),key); }
}
