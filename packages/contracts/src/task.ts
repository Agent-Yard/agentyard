/** Persistent collaboration contracts shared by the console and API integrations. */
export type TaskPermission = 'VIEW' | 'CONTRIBUTE' | 'DECIDE' | 'EXECUTE' | 'REVIEW' | 'MANAGE';
export type TaskStatus = 'OPEN' | 'ACTIVE' | 'WAITING' | 'IN_REVIEW' | 'COMPLETED' | 'CANCELLED';
export type ContextKind = 'FACT' | 'HYPOTHESIS' | 'PROPOSAL' | 'DECISION' | 'QUESTION' | 'NOTE' | 'SUMMARY' | 'ARTIFACT';
export type ContextState = 'PROPOSED' | 'CONFIRMED' | 'RESOLVED' | 'REJECTED' | 'SUPERSEDED';
export type TaskRelationKind = 'CHILD' | 'DEPENDS_ON' | 'RELATED';
export type TaskCommandKind = 'UPDATE' | 'TRANSITION' | 'SET_PARTICIPANT' | 'REMOVE_PARTICIPANT' | 'PUT_CONTEXT' | 'SET_CONTEXT_STATE' | 'CREATE_CHILD' | 'LINK_TASK' | 'UNLINK_TASK' | 'LINK_SESSION' | 'UNLINK_SESSION' | 'REVIEW' | 'START_EXECUTION' | 'CANCEL_EXECUTION' | 'RESUME_EXECUTION';
export interface CollaborationActor { id: string; kind: 'HUMAN' | 'AGENT'; subjectId: string; displayName: string; active: boolean; createdAt: string }
export interface CollaborationRole { key: string; name: string; permissions: TaskPermission[] }
export interface TaskParticipant { actorId: string; roles: string[] }
export interface TaskPolicy { roles: CollaborationRole[]; requiredApprovals: number; allowSelfReview: boolean; acceptanceCriteria: string[] }
export interface CollaborationOrganization { id: string; parentId: string | null; name: string; description: string; roles: CollaborationRole[]; revision: number; createdBy: string; createdAt: string; updatedAt: string }
export interface OrganizationDetail { organization: CollaborationOrganization; members: TaskParticipant[]; permissions: TaskPermission[] }
export interface BusinessTask { id: string; organizationId: string | null; title: string; goal: string; status: TaskStatus; stage: string; revision: number; contentRevision: number; attributes: Record<string, unknown>; policy: TaskPolicy; createdBy: string; submittedBy: string | null; createdAt: string; updatedAt: string }
export interface TaskContextEntry { id: string; taskId: string; kind: ContextKind; title: string; body: Record<string, unknown>; state: ContextState; visibleToRoles: string[]; source: Record<string, unknown>; authorId: string; revision: number; createdAt: string; updatedAt: string }
export interface TaskRelation { taskId: string; relatedTaskId: string; kind: TaskRelationKind }
export interface TaskSessionLink { taskId: string; sessionId: string; actorId: string; createdAt: string }
export interface TaskReview { taskId: string; contentRevision: number; actorId: string; approved: boolean; comment: string; createdAt: string }
export interface TaskExecution { id: string; taskId: string; actorId: string; requestedBy: string; kind: 'AGENT' | 'PLAYBOOK'; status: 'QUEUED' | 'RUNNING' | 'WAITING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'; instruction: string; input: Record<string, unknown>; result: Record<string, unknown>; waiting: Record<string, unknown>; failureReason: string | null; createdAt: string; updatedAt: string }
export interface TaskDetail { task: BusinessTask; participants: TaskParticipant[]; context: TaskContextEntry[]; relations: TaskRelation[]; sessions: TaskSessionLink[]; executions: TaskExecution[]; reviews: TaskReview[]; permissions: TaskPermission[] }
export interface TaskEvent { taskId: string; revision: number; actorId: string; kind: string; data: Record<string, unknown>; visibleToRoles: string[]; createdAt: string }
export interface TaskPage<T> { items: T[]; nextCursor: string | null }
export interface TaskCommand { requestId: string; expectedRevision: number; kind: TaskCommandKind; data: Record<string, unknown> }
export interface TaskCommandResult { taskId: string; revision: number; objectId: string | null; duplicate: boolean }
export interface CreateTask { organizationId?: string | null; title: string; goal: string; attributes?: Record<string, unknown>; policy?: TaskPolicy }
export const defaultCollaborationRoles = (): CollaborationRole[] => [
  { key: 'owner', name: '负责人', permissions: ['VIEW', 'CONTRIBUTE', 'DECIDE', 'EXECUTE', 'REVIEW', 'MANAGE'] },
  { key: 'contributor', name: '协作者', permissions: ['VIEW', 'CONTRIBUTE', 'EXECUTE'] },
  { key: 'reviewer', name: '评审者', permissions: ['VIEW', 'CONTRIBUTE', 'DECIDE', 'REVIEW'] },
  { key: 'observer', name: '观察者', permissions: ['VIEW'] },
];
