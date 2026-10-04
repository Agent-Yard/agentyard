import { request } from './api';
import type { BusinessTask, CollaborationActor, CollaborationOrganization, CollaborationRole, CreateTask, OrganizationDetail, TaskCommand, TaskCommandResult, TaskContextEntry, TaskDetail, TaskEvent, TaskPage } from '../../../../packages/contracts/src/task';

const base = '/collaboration';
const json = (method: string, body: unknown): RequestInit => ({ method, body: JSON.stringify(body) });
const path = (id: string) => `${base}/tasks/${encodeURIComponent(id)}`;
export const collaborationApi = {
  me: () => request<CollaborationActor>(`${base}/actors/me`),
  actors: () => request<CollaborationActor[]>(`${base}/actors`),
  registerAgent: (agentId: string) => request<CollaborationActor>(`${base}/actors/agents`, json('POST', { agentId })),
  organizations: () => request<CollaborationOrganization[]>(`${base}/organizations`),
  organization: (id: string) => request<OrganizationDetail>(`${base}/organizations/${encodeURIComponent(id)}`),
  createOrganization: (body: { name: string; description: string; parentId?: string; roles?: CollaborationRole[] }) => request<OrganizationDetail>(`${base}/organizations`, json('POST', body)),
  updateOrganization: (id: string, body: { expectedRevision: number; name: string; description: string; roles: CollaborationRole[] }) => request<OrganizationDetail>(`${base}/organizations/${encodeURIComponent(id)}`, json('PUT', body)),
  member: (id: string, body: { expectedRevision: number; actorId: string; roles: string[] }) => request<OrganizationDetail>(`${base}/organizations/${encodeURIComponent(id)}/members`, json('PUT', body)),
  tasks: (query = '') => request<TaskPage<BusinessTask>>(`${base}/tasks?${query}`),
  create: (body: CreateTask) => request<BusinessTask>(`${base}/tasks`, json('POST', body)),
  detail: (id: string) => request<TaskDetail>(path(id)),
  command: (id: string, body: TaskCommand) => request<TaskCommandResult>(`${path(id)}/commands`, json('POST', body)),
  context: (id: string, cursor?: string) => request<TaskPage<TaskContextEntry>>(`${path(id)}/context?limit=200${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`),
  versions: (id: string, entryId: string) => request<TaskContextEntry[]>(`${path(id)}/context/${encodeURIComponent(entryId)}/versions`),
  events: (id: string, afterRevision = 0) => request<TaskEvent[]>(`${path(id)}/events?afterRevision=${afterRevision}&limit=200`),
  sessionTasks: (sessionId: string) => request<BusinessTask[]>(`${base}/sessions/${encodeURIComponent(sessionId)}/tasks`),
};
