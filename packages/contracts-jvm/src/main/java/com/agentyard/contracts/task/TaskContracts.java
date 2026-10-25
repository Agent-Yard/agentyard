package com.agentyard.contracts.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Work and shared information; participants make decisions, never the Task itself. */
public final class TaskContracts {
    private TaskContracts() {}
    public enum ActorKind { HUMAN, AGENT }
    public enum TaskStatus { OPEN, ACTIVE, WAITING, PAUSED, SUBMITTED, COMPLETED, CANCELLED }
    public enum Permission { VIEW, CONTRIBUTE, DECIDE, EXECUTE, MANAGE }
    public enum ContextKind { FACT, HYPOTHESIS, PROPOSAL, DECISION, QUESTION, NOTE, SUMMARY, ARTIFACT }
    public enum ContextState { PROPOSED, CONFIRMED, RESOLVED, REJECTED, SUPERSEDED }
    public enum RelationKind { CHILD, DEPENDS_ON, RELATED }
    public enum DecisionKind { SUBMIT, CONFIRM, REQUEST_CHANGES, CONFIRM_STAGE, TRANSFER_CONFIRMATION, PAUSE, RESUME, CANCEL }
    public enum CommandKind {
        UPDATE, TRANSITION, SET_PARTICIPANT, REMOVE_PARTICIPANT,
        PUT_CONTEXT, SET_CONTEXT_STATE, CREATE_CHILD, LINK_TASK, UNLINK_TASK,
        LINK_SESSION, UNLINK_SESSION, SUBMIT, CONFIRM, REQUEST_CHANGES, CONFIRM_STAGE, TRANSFER_CONFIRMATION
    }
    /** AGENT subjectId is the concrete Session ID; a template is never an actor. */
    public record Actor(String id, ActorKind kind, String subjectId, String displayName, boolean active, Instant createdAt) {}
    public record RoleDefinition(String key, String name, Set<Permission> permissions) {
        public RoleDefinition { permissions = Set.copyOf(permissions == null ? Set.of() : permissions); }
    }
    public record Organization(String id, String parentId, String name, String description,
        List<RoleDefinition> roles, long revision, String createdBy, Instant createdAt, Instant updatedAt) {}
    public record Participant(String actorId, List<String> roles) {}
    public record OrganizationDetail(Organization organization, List<Participant> members, Set<Permission> permissions) {}
    public record Policy(List<RoleDefinition> roles, List<String> acceptanceCriteria) {
        public Policy {
            roles = List.copyOf(roles == null ? defaultRoles() : roles);
            acceptanceCriteria = List.copyOf(acceptanceCriteria == null ? List.of() : acceptanceCriteria);
        }
        public static Policy defaults() { return new Policy(defaultRoles(), List.of()); }
    }
    public record Task(String id, String organizationId, String title, String goal, TaskStatus status,
        String stage, long revision, long contentRevision, Map<String, Object> attributes, Policy policy,
        String initiatedBy, String createdBy, String confirmerId, String submittedBy,
        Instant createdAt, Instant updatedAt) {}
    public record ContextEntry(String id, String taskId, ContextKind kind, String title, Map<String, Object> body,
        ContextState state, Set<String> visibleToRoles, Map<String, Object> source, String authorId,
        long revision, Instant createdAt, Instant updatedAt) {}
    public record Relation(String taskId, String relatedTaskId, RelationKind kind) {}
    public record SessionLink(String taskId, String sessionId, String actorId, Instant createdAt) {}
    public record Event(String taskId, long revision, String actorId, String kind, Map<String, Object> data,
        Set<String> visibleToRoles, Instant createdAt) {}
    public record Decision(String id, String taskId, long contentRevision, DecisionKind kind,
        String decidedBy, String executedBy, String sourceMessageId, String comment, Instant createdAt) {}
    /** Constructed by a trusted API path, never accepted as model-supplied identity. */
    public record CommandAuthority(String executedBy, String decidedBy, String sourceMessageId) {
        public static CommandAuthority own(String actorId) { return new CommandAuthority(actorId, actorId, null); }
    }
    public record TaskDetail(Task task, List<Participant> participants, List<ContextEntry> context,
        List<Relation> relations, List<SessionLink> sessions, List<Decision> decisions, Set<Permission> permissions) {}
    public record Page<T>(List<T> items, String nextCursor) {}
    public record Command(String requestId, long expectedRevision, CommandKind kind, Map<String, Object> data) {}
    public record CommandResult(String taskId, long revision, String objectId, boolean duplicate,
        TaskStatus status, long contentRevision, boolean submissionInvalidated) {}
    public record CreateTask(String organizationId, String title, String goal, Map<String, Object> attributes, Policy policy) {}
    public record CreateOrganization(String parentId, String name, String description, List<RoleDefinition> roles) {}
    public record UpdateOrganization(long expectedRevision, String name, String description, List<RoleDefinition> roles) {}
    public record SetMember(long expectedRevision, String actorId, List<String> roles) {}

    public static List<RoleDefinition> defaultRoles() {
        return List.of(
            new RoleDefinition("owner", "负责人", Set.of(Permission.values())),
            new RoleDefinition("contributor", "协作者", Set.of(Permission.VIEW, Permission.CONTRIBUTE, Permission.EXECUTE)),
            new RoleDefinition("reviewer", "评审者", Set.of(Permission.VIEW, Permission.CONTRIBUTE, Permission.DECIDE)),
            new RoleDefinition("observer", "观察者", Set.of(Permission.VIEW))
        );
    }
}
