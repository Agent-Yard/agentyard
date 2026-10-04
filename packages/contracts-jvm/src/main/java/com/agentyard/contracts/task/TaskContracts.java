package com.agentyard.contracts.task;

import com.agentyard.contracts.session.SessionContracts.AgentConfig;
import com.agentyard.contracts.session.SessionContracts.PlaybookConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Task is the persistent collaboration boundary. Context and artifacts belong to it. */
public final class TaskContracts {
    private TaskContracts() {}
    public enum ActorKind { HUMAN, AGENT }
    public enum TaskStatus { OPEN, ACTIVE, WAITING, IN_REVIEW, COMPLETED, CANCELLED }
    public enum Permission { VIEW, CONTRIBUTE, DECIDE, EXECUTE, REVIEW, MANAGE }
    public enum ContextKind { FACT, HYPOTHESIS, PROPOSAL, DECISION, QUESTION, NOTE, SUMMARY, ARTIFACT }
    public enum ContextState { PROPOSED, CONFIRMED, RESOLVED, REJECTED, SUPERSEDED }
    public enum RelationKind { CHILD, DEPENDS_ON, RELATED }
    public enum ExecutionKind { AGENT, PLAYBOOK }
    public enum ExecutionStatus { QUEUED, RUNNING, WAITING, SUCCEEDED, FAILED, CANCELLED }
    public enum CommandKind {
        UPDATE, TRANSITION, SET_PARTICIPANT, REMOVE_PARTICIPANT,
        PUT_CONTEXT, SET_CONTEXT_STATE, CREATE_CHILD, LINK_TASK, UNLINK_TASK,
        LINK_SESSION, UNLINK_SESSION, REVIEW, START_EXECUTION, CANCEL_EXECUTION, RESUME_EXECUTION
    }
    public record Actor(String id, ActorKind kind, String subjectId, String displayName, boolean active, Instant createdAt) {}
    public record RoleDefinition(String key, String name, Set<Permission> permissions) {
        public RoleDefinition { permissions = Set.copyOf(permissions == null ? Set.of() : permissions); }
    }
    public record Organization(String id, String parentId, String name, String description,
        List<RoleDefinition> roles, long revision, String createdBy, Instant createdAt, Instant updatedAt) {}
    public record Participant(String actorId, List<String> roles) {}
    public record OrganizationDetail(Organization organization, List<Participant> members, Set<Permission> permissions) {}
    public record Policy(List<RoleDefinition> roles, int requiredApprovals, boolean allowSelfReview,
        List<String> acceptanceCriteria) {
        public Policy {
            roles = List.copyOf(roles == null ? defaultRoles() : roles);
            acceptanceCriteria = List.copyOf(acceptanceCriteria == null ? List.of() : acceptanceCriteria);
        }
        public static Policy defaults() { return new Policy(defaultRoles(), 0, false, List.of()); }
    }
    public record Task(String id, String organizationId, String title, String goal, TaskStatus status,
        String stage, long revision, long contentRevision, Map<String, Object> attributes, Policy policy,
        String createdBy, String submittedBy, Instant createdAt, Instant updatedAt) {}
    public record ContextEntry(String id, String taskId, ContextKind kind, String title, Map<String, Object> body,
        ContextState state, Set<String> visibleToRoles, Map<String, Object> source, String authorId,
        long revision, Instant createdAt, Instant updatedAt) {}
    public record Relation(String taskId, String relatedTaskId, RelationKind kind) {}
    public record SessionLink(String taskId, String sessionId, String actorId, Instant createdAt) {}
    public record Event(String taskId, long revision, String actorId, String kind, Map<String, Object> data,
        Set<String> visibleToRoles, Instant createdAt) {}
    public record Review(String taskId, long contentRevision, String actorId, boolean approved, String comment, Instant createdAt) {}
    public record Execution(String id, String taskId, String actorId, String requestedBy, ExecutionKind kind,
        ExecutionStatus status, String instruction, Map<String, Object> input, Map<String, Object> result,
        Map<String, Object> waiting, String failureReason, Instant createdAt, Instant updatedAt) {}
    public record TaskDetail(Task task, List<Participant> participants, List<ContextEntry> context,
        List<Relation> relations, List<SessionLink> sessions, List<Execution> executions, List<Review> reviews,
        Set<Permission> permissions) {}
    public record Page<T>(List<T> items, String nextCursor) {}
    public record Command(String requestId, long expectedRevision, CommandKind kind, Map<String, Object> data) {}
    public record CommandResult(String taskId, long revision, String objectId, boolean duplicate) {}
    public record CreateTask(String organizationId, String title, String goal, Map<String, Object> attributes, Policy policy) {}
    public record CreateOrganization(String parentId, String name, String description, List<RoleDefinition> roles) {}
    public record UpdateOrganization(long expectedRevision, String name, String description, List<RoleDefinition> roles) {}
    public record SetMember(long expectedRevision, String actorId, List<String> roles) {}
    public record CreateAgentActor(String agentId, String displayName) {}
    public record RuntimeSnapshot(String assistantId, String releaseVersion, AgentConfig agent, PlaybookConfig playbook) {}
    public record ExecutionStart(String executionId) {}
    public record ExecutionInput(Execution execution, RuntimeSnapshot runtime, TaskDetail context) {}
    public record ExecutionProgress(ExecutionStatus status, Map<String, Object> result, Map<String, Object> waiting, String failureReason) {}
    public record ResumeSignal(String signalId, String waitingId, String actorId, Map<String, Object> payload) {}

    public static List<RoleDefinition> defaultRoles() {
        return List.of(
            new RoleDefinition("owner", "负责人", Set.of(Permission.values())),
            new RoleDefinition("contributor", "协作者", Set.of(Permission.VIEW, Permission.CONTRIBUTE, Permission.EXECUTE)),
            new RoleDefinition("reviewer", "评审者", Set.of(Permission.VIEW, Permission.CONTRIBUTE, Permission.DECIDE, Permission.REVIEW)),
            new RoleDefinition("observer", "观察者", Set.of(Permission.VIEW))
        );
    }
}
