import type {
  Agent,
  Assistant,
  BusinessDomain,
  CatalogSummary,
  CreateAssistantPayload,
  CreateAgentPayload,
  CreateSessionPayload,
  CreateDomainPayload,
  CreateKnowledgeBasePayload,
  CreateKnowledgeReleasePayload,
  CreatePlaybookPayload,
  CreateResourcePayload,
  CreateResourceVersionPayload,
  CreateScenarioPayload,
  DeletionImpactPreview,
  KnowledgeBase,
  KnowledgeDocument,
  KnowledgeDocumentDeletionPreview,
  KnowledgeDocumentDeletionResult,
  KnowledgeFile,
  KnowledgeImportJob,
  KnowledgeIndexSnapshot,
  KnowledgeRetrievalPreviewRequest,
  KnowledgeRetrievalPreviewResult,
  KnowledgeReference,
  KnowledgeRelease,
  KnowledgeUploadCompletion,
  KnowledgeUploadSession,
  LogoutResponse,
  ObjectReferenceAnalysis,
  Playbook,
  PlatformAggregateType,
  PlatformEventPage,
  Resource,
  ResourceVersion,
  ReferenceObjectType,
  Scenario,
  SessionRuntimeDetail,
  SessionRuntimeSession,
  UpdateAssistantPayload,
  UpdateAgentPayload,
  UpdateDomainPayload,
  UpdateKnowledgeBasePayload,
  UpdatePlaybookPayload,
  UpdateResourcePayload,
  UpdateResourceVersionPayload,
  UpdateScenarioPayload,
  UserSession,
} from '../types';

export const API_BASE = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '/api';
export const AUTH_LOGIN_PATH = `${API_BASE}/auth/login`;
export const AUTH_DEV_BOOTSTRAP_LOGIN_PATH = `${API_BASE}/auth/dev-bootstrap-login`;

let unauthorizedHandler: (() => void) | null = null;

export class UnauthorizedError extends Error {
  readonly status = 401;

  constructor(message: string) {
    super(message);
    this.name = 'UnauthorizedError';
  }
}

export function setUnauthorizedHandler(handler: (() => void) | null) {
  unauthorizedHandler = handler;
}

export function isUnauthorizedError(error: unknown): error is UnauthorizedError {
  return error instanceof UnauthorizedError;
}

async function parseError(response: Response): Promise<never> {
  const rawText = await response.text();
  let detail = rawText || `Request failed: ${response.status}`;
  if (rawText) {
    try {
      const errorBody = JSON.parse(rawText) as { detail?: string; message?: string; error?: string };
      detail = errorBody.detail ?? errorBody.message ?? errorBody.error ?? detail;
    } catch {
      detail = rawText;
    }
  }
  if (response.status === 401) {
    unauthorizedHandler?.();
    throw new UnauthorizedError(detail);
  }
  throw new Error(detail);
}

async function readResponseData<T>(response: Response): Promise<T> {
  if (!response.ok) {
    return parseError(response);
  }
  const body = await response.json();
  return body.data as T;
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...(options?.headers ?? {}),
    },
    ...options,
  });
  return readResponseData<T>(response);
}

function jsonOptions(method: 'POST' | 'PUT' | 'PATCH' | 'DELETE', body?: unknown): RequestInit {
  return {
    method,
    body: body === undefined ? undefined : JSON.stringify(body),
  };
}

export const api = {
  getSession: () => request<UserSession>('/auth/session'),
  getCatalogSummary: () => request<CatalogSummary>('/catalog/summary'),
  getObjectReferenceAnalysis: (objectType: ReferenceObjectType, objectId: string) =>
    request<ObjectReferenceAnalysis>(`/catalog/references/${objectType}/${objectId}`),
  getDeletionImpactPreview: (objectType: ReferenceObjectType, objectId: string) =>
    request<DeletionImpactPreview>(`/catalog/deletion-preview/${objectType}/${objectId}`),
  listPlatformEvents: (params: {
    aggregateType?: PlatformAggregateType;
    aggregateId?: string;
    since?: string;
    limit?: number;
    cursor?: string;
  }) => {
    const query = new URLSearchParams();
    if (params.aggregateType) {
      query.set('aggregateType', params.aggregateType);
    }
    if (params.aggregateId) {
      query.set('aggregateId', params.aggregateId);
    }
    if (params.since) {
      query.set('since', params.since);
    }
    if (params.limit != null) {
      query.set('limit', String(params.limit));
    }
    if (params.cursor) {
      query.set('cursor', params.cursor);
    }
    const suffix = query.size > 0 ? `?${query.toString()}` : '';
    return request<PlatformEventPage>(`/events${suffix}`);
  },
  getRuntimeSessions: () => request<SessionRuntimeSession[]>('/session-runtime/sessions'),
  getRuntimeSessionDetail: (sessionId: string) =>
    request<SessionRuntimeDetail>(`/session-runtime/sessions/${sessionId}`),
  createRuntimeSession: (payload: CreateSessionPayload) =>
    request<SessionRuntimeSession>('/session-runtime/sessions', jsonOptions('POST', payload)),
  sendRuntimeSessionMessage: (sessionId: string, payload: { customerId: string; message: string }) =>
    request<SessionRuntimeSession>(`/session-runtime/sessions/${sessionId}/messages`, jsonOptions('POST', payload)),
  humanReplyRuntimeSession: (sessionId: string, payload: { operatorId: string; message: string; payload?: Record<string, unknown> }) =>
    request<SessionRuntimeSession>(`/session-runtime/sessions/${sessionId}/human-reply`, jsonOptions('POST', payload)),
  resumeRuntimePlaybookWithHuman: (sessionId: string, payload: { playbookRunId: string; payload?: Record<string, unknown> }) =>
    request<SessionRuntimeSession>(`/session-runtime/sessions/${sessionId}/human-resume`, jsonOptions('POST', payload)),
  resumeRuntimePlaybookWithExternalCallback: (sessionId: string, payload: { playbookRunId: string; payload?: Record<string, unknown> }) =>
    request<SessionRuntimeSession>(`/session-runtime/sessions/${sessionId}/external-callback`, jsonOptions('POST', payload)),
  endRuntimeSessionHandoff: (sessionId: string) =>
    request<SessionRuntimeSession>(`/session-runtime/sessions/${sessionId}/handoff/end`, jsonOptions('POST')),
  createDomain: (payload: CreateDomainPayload) => request<BusinessDomain>('/domains', jsonOptions('POST', payload)),
  updateDomain: (domainId: string, payload: UpdateDomainPayload) =>
    request<BusinessDomain>(`/domains/${domainId}`, jsonOptions('PUT', payload)),
  deleteDomain: (domainId: string) => request<BusinessDomain>(`/domains/${domainId}`, jsonOptions('DELETE')),
  createScenario: (payload: CreateScenarioPayload) => request<Scenario>('/scenarios', jsonOptions('POST', payload)),
  updateScenario: (scenarioId: string, payload: UpdateScenarioPayload) =>
    request<Scenario>(`/scenarios/${scenarioId}`, jsonOptions('PUT', payload)),
  deleteScenario: (scenarioId: string) => request<Scenario>(`/scenarios/${scenarioId}`, jsonOptions('DELETE')),
  createAssistant: (payload: CreateAssistantPayload) => request<Assistant>('/assistants', jsonOptions('POST', payload)),
  updateAssistant: (assistantId: string, payload: UpdateAssistantPayload) =>
    request<Assistant>(`/assistants/${assistantId}`, jsonOptions('PUT', payload)),
  deleteAssistant: (assistantId: string) => request<Assistant>(`/assistants/${assistantId}`, jsonOptions('DELETE')),
  createAgent: (payload: CreateAgentPayload) => request<Agent>('/agents', jsonOptions('POST', payload)),
  deleteAgent: (agentId: string) => request<Agent>(`/agents/${agentId}`, jsonOptions('DELETE')),
  updateAgent: (agentId: string, payload: UpdateAgentPayload) =>
    request<Agent>(`/agents/${agentId}`, jsonOptions('PUT', payload)),
  createPlaybook: (payload: CreatePlaybookPayload) => request<Playbook>('/playbooks', jsonOptions('POST', payload)),
  deletePlaybook: (playbookId: string) => request<Playbook>(`/playbooks/${playbookId}`, jsonOptions('DELETE')),
  updatePlaybook: (playbookId: string, payload: UpdatePlaybookPayload) =>
    request<Playbook>(`/playbooks/${playbookId}`, jsonOptions('PUT', payload)),
  createResource: (payload: CreateResourcePayload) => request<Resource>('/resources', jsonOptions('POST', payload)),
  deleteResource: (resourceId: string) => request<Resource>(`/resources/${resourceId}`, jsonOptions('DELETE')),
  updateResource: (resourceId: string, payload: UpdateResourcePayload) =>
    request<Resource>(`/resources/${resourceId}`, jsonOptions('PUT', payload)),
  createResourceVersion: (resourceId: string, payload: CreateResourceVersionPayload) =>
    request<ResourceVersion>(`/resources/${resourceId}/versions`, jsonOptions('POST', payload)),
  updateResourceVersion: (resourceId: string, versionId: string, payload: UpdateResourceVersionPayload) =>
    request<ResourceVersion>(`/resources/${resourceId}/versions/${versionId}`, jsonOptions('PUT', payload)),
  deleteResourceVersion: (resourceId: string, versionId: string) =>
    request<ResourceVersion>(`/resources/${resourceId}/versions/${versionId}`, jsonOptions('DELETE')),
  publishResourceVersion: (resourceId: string, versionId: string) =>
    request<ResourceVersion>(`/resources/${resourceId}/versions/${versionId}/publish`, jsonOptions('PATCH')),
  createKnowledgeBase: (payload: CreateKnowledgeBasePayload) =>
    request<KnowledgeBase>('/knowledge-bases', jsonOptions('POST', payload)),
  updateKnowledgeBase: (knowledgeBaseId: string, payload: UpdateKnowledgeBasePayload) =>
    request<KnowledgeBase>(`/knowledge-bases/${knowledgeBaseId}`, jsonOptions('PUT', payload)),
  deleteKnowledgeBase: (knowledgeBaseId: string) =>
    request<KnowledgeBase>(`/knowledge-bases/${knowledgeBaseId}`, jsonOptions('DELETE')),
  listKnowledgeReleases: (knowledgeBaseId: string) =>
    request<KnowledgeRelease[]>(`/knowledge-bases/${knowledgeBaseId}/releases`),
  createKnowledgeRelease: (knowledgeBaseId: string, payload: CreateKnowledgeReleasePayload) =>
    request<KnowledgeRelease>(`/knowledge-bases/${knowledgeBaseId}/releases`, jsonOptions('POST', payload)),
  publishKnowledgeRelease: (knowledgeBaseId: string, releaseId: string) =>
    request<KnowledgeRelease>(`/knowledge-bases/${knowledgeBaseId}/releases/${releaseId}/publish`, jsonOptions('PATCH')),
  deleteKnowledgeRelease: (knowledgeBaseId: string, releaseId: string) =>
    request<KnowledgeRelease>(`/knowledge-bases/${knowledgeBaseId}/releases/${releaseId}`, jsonOptions('DELETE')),
  listKnowledgeReferences: (knowledgeBaseId: string) =>
    request<KnowledgeReference[]>(`/knowledge-bases/${knowledgeBaseId}/references`),
  createKnowledgeUploadSession: (knowledgeBaseId: string) =>
    request<KnowledgeUploadSession>(`/knowledge-bases/${knowledgeBaseId}/upload-sessions`, jsonOptions('POST')),
  async completeKnowledgeUpload(knowledgeBaseId: string, uploadSessionId: string, file: File) {
    const formData = new FormData();
    formData.append('file', file);
    const response = await fetch(`${API_BASE}/knowledge-bases/${knowledgeBaseId}/upload-sessions/${uploadSessionId}/complete`, {
      credentials: 'include',
      method: 'POST',
      body: formData,
    });
    return readResponseData<KnowledgeUploadCompletion>(response);
  },
  importKnowledgeUrl: (knowledgeBaseId: string, url: string, title?: string) =>
    request<KnowledgeUploadCompletion>(
      `/knowledge-bases/${knowledgeBaseId}/url-imports`,
      jsonOptions('POST', { url, title: title?.trim() || null }),
    ),
  listKnowledgeFiles: (knowledgeBaseId: string) => request<KnowledgeFile[]>(`/knowledge-bases/${knowledgeBaseId}/files`),
  listKnowledgeImportJobs: (knowledgeBaseId: string) => request<KnowledgeImportJob[]>(`/knowledge-bases/${knowledgeBaseId}/import-jobs`),
  retryKnowledgeImportJob: (knowledgeBaseId: string, jobId: string) =>
    request<KnowledgeUploadCompletion>(`/knowledge-bases/${knowledgeBaseId}/import-jobs/${jobId}/retry`, jsonOptions('POST')),
  listKnowledgeDocuments: (knowledgeBaseId: string) => request<KnowledgeDocument[]>(`/knowledge-bases/${knowledgeBaseId}/documents`),
  previewKnowledgeDocumentDeletion: (knowledgeBaseId: string, documentId: string) =>
    request<KnowledgeDocumentDeletionPreview>(`/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/deletion-preview`),
  deleteKnowledgeDocument: (knowledgeBaseId: string, documentId: string) =>
    request<KnowledgeDocumentDeletionResult>(`/knowledge-bases/${knowledgeBaseId}/documents/${documentId}`, jsonOptions('DELETE')),
  createKnowledgeIndexSnapshot: (knowledgeBaseId: string, documentIds: string[]) =>
    request<KnowledgeIndexSnapshot>(`/knowledge-bases/${knowledgeBaseId}/snapshots`, jsonOptions('POST', { documentIds })),
  listKnowledgeIndexSnapshots: (knowledgeBaseId: string) =>
    request<KnowledgeIndexSnapshot[]>(`/knowledge-bases/${knowledgeBaseId}/snapshots`),
  retryKnowledgeIndexSnapshot: (knowledgeBaseId: string, snapshotId: string) =>
    request<KnowledgeIndexSnapshot>(`/knowledge-bases/${knowledgeBaseId}/snapshots/${snapshotId}/retry`, jsonOptions('POST')),
  previewKnowledgeRetrieval: (knowledgeBaseId: string, payload: KnowledgeRetrievalPreviewRequest) =>
    request<KnowledgeRetrievalPreviewResult>(`/knowledge-bases/${knowledgeBaseId}/retrieval-preview`, jsonOptions('POST', payload)),
  logout: () => request<LogoutResponse>('/auth/logout', jsonOptions('POST')),
};
