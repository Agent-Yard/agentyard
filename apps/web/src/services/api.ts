import type {
  AgentSession,
  AgentExecutionStatus,
  AgentSessionDetail,
  AgentSessionStatus,
  CatalogSummary,
  ChannelConversationBinding,
  ChannelInboundEvent,
  ChannelOutboundFrameCheckpoint,
  ChannelProfile,
  ChannelProviderDefinition,
  ChannelProviderJobConfig,
  ChannelProviderJobConfigWritePayload,
  ChannelProviderJobRun,
  ChannelTemplateBinding,
  ChannelTemplateBindingWritePayload,
  CollaborationMessage,
  CreateChannelProfilePayload,
  CreateIntegrationAccountCredentialPayload,
  CreateIntegrationAccountPayload,
  CreateKnowledgeBasePayload,
  CreateKnowledgeReleasePayload,
  CreatePlaybookPayload,
  CreatePlaybookVersionPayload,
  CreateResourcePayload,
  CreateResourceVersionPayload,
  CreateRoleTemplatePayload,
  CreateRoleTemplateVersionPayload,
  DeletionImpactPreview,
  Entry,
  EntryPayload,
  IntegrationAccount,
  KnowledgeBase,
  KnowledgeDocument,
  KnowledgeDocumentDeletionPreview,
  KnowledgeDocumentDeletionResult,
  KnowledgeFile,
  KnowledgeImportJob,
  KnowledgeIndexSnapshot,
  KnowledgeReference,
  KnowledgeRelease,
  KnowledgeRetrievalPreviewRequest,
  KnowledgeRetrievalPreviewResult,
  KnowledgeUploadCompletion,
  KnowledgeUploadSession,
  LogoutResponse,
  ObjectReferenceAnalysis,
  OwnedConfiguration,
  PlatformAggregateType,
  PlatformEventPage,
  Playbook,
  PlaybookVersion,
  Reception,
  ReferenceObjectType,
  Resource,
  ResourceVersion,
  RoleTemplate,
  RoleTemplateVersion,
  RotateIntegrationAccountCredentialPayload,
  SendMessage,
  ToolConnectorDefinition,
  UpdateChannelProfilePayload,
  UpdateIntegrationAccountPayload,
  UpdateKnowledgeBasePayload,
  UpdatePlaybookPayload,
  UpdateResourcePayload,
  UpdateResourceVersionPayload,
  UserSession
} from '../types';


export const API_BASE = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '/api';
export const AUTH_LOGIN_PATH = `${API_BASE}/auth/login`;
export const AUTH_DEV_BOOTSTRAP_LOGIN_PATH = `${API_BASE}/auth/dev-bootstrap-login`;
const LOGIN_PATH = '/login';

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

function containsUnsafeReturnToCharacters(value: string) {
  return /[\u0000-\u001f\u007f\\]/.test(value);
}

function containsUnsafePathSegment(path: string) {
  return path.split('/').some((segment) => {
    if (segment === '.' || segment === '..') {
      return true;
    }
    try {
      const decodedSegment = decodeURIComponent(segment);
      if (containsUnsafeReturnToCharacters(decodedSegment)) {
        return true;
      }
      return decodedSegment === '.' || decodedSegment === '..';
    } catch {
      return true;
    }
  });
}

export function isSafeFrontendReturnTo(value: unknown): value is string {
  if (typeof value !== 'string' || !value || value !== value.trim()) {
    return false;
  }
  if (!value.startsWith('/') || value.startsWith('//') || containsUnsafeReturnToCharacters(value)) {
    return false;
  }
  if (/^[a-zA-Z][a-zA-Z\d+.-]*:/.test(value)) {
    return false;
  }

  const path = value.split(/[?#]/, 1)[0];
  if (containsUnsafePathSegment(path)) {
    return false;
  }
  if (path === LOGIN_PATH || path.startsWith(`${LOGIN_PATH}/`)) {
    return false;
  }
  return path === '/' || path === '/console' || path.startsWith('/console/');
}

export function appendSafeReturnTo(path: string, returnTo: unknown) {
  if (!isSafeFrontendReturnTo(returnTo)) {
    return path;
  }
  const separator = path.includes('?') ? '&' : '?';
  return `${path}${separator}${new URLSearchParams({ returnTo }).toString()}`;
}

export function buildLoginRedirectPath(route: { path: string; fullPath: string }) {
  if (route.path === LOGIN_PATH) {
    return LOGIN_PATH;
  }
  return appendSafeReturnTo(LOGIN_PATH, route.fullPath);
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

export async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const { headers: optionHeaders, ...requestOptions } = options ?? {};
  const response = await fetch(`${API_BASE}${path}`, {
    credentials: 'include',
    ...requestOptions,
    headers: {
      'Content-Type': 'application/json',
      ...(optionHeaders ?? {}),
    },
  });
  return readResponseData<T>(response);
}

function jsonOptions(
  method: 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  body?: unknown,
  headers?: HeadersInit,
): RequestInit {
  const options: RequestInit = {
    method,
    body: body === undefined ? undefined : JSON.stringify(body),
  };
  if (headers !== undefined) {
    options.headers = headers;
  }
  return options;
}

export const api = {
  getSession: () => request<UserSession>('/auth/session'),
  getCatalogSummary: () => request<CatalogSummary>('/catalog/summary'),
  listToolConnectorDefinitions: () => request<ToolConnectorDefinition[]>('/extensions/tool-connectors'),
  listChannelProviderDefinitions: () => request<ChannelProviderDefinition[]>('/extensions/channel-providers'),
  listChannelProfiles: () => request<ChannelProfile[]>('/channel-admin/profiles'),
  getChannelProfile: (channelProfileId: string) => request<ChannelProfile>(`/channel-admin/profiles/${channelProfileId}`),
  createChannelProfile: (payload: CreateChannelProfilePayload) =>
    request<ChannelProfile>('/channel-admin/profiles', jsonOptions('POST', payload)),
  updateChannelProfile: (channelProfileId: string, payload: UpdateChannelProfilePayload) =>
    request<ChannelProfile>(`/channel-admin/profiles/${channelProfileId}`, jsonOptions('PUT', payload)),
  deleteChannelProfile: (channelProfileId: string, expectedRevision: number) =>
    request<ChannelProfile>(
      `/channel-admin/profiles/${channelProfileId}?${new URLSearchParams({ expectedRevision: String(expectedRevision) }).toString()}`,
      jsonOptions('DELETE'),
    ),
  listChannelBindings: (channelProfileId: string) =>
    request<ChannelConversationBinding[]>(`/channel-admin/profiles/${channelProfileId}/bindings`),
  listChannelInboundEvents: (channelProfileId: string) =>
    request<ChannelInboundEvent[]>(`/channel-admin/profiles/${channelProfileId}/inbound-events`),
  listChannelOutboundFinalCheckpoints: (channelProfileId: string) =>
    request<ChannelOutboundFrameCheckpoint[]>(`/channel-admin/profiles/${channelProfileId}/outbound-final-checkpoints`),
  listChannelTemplateBindings: (channelProfileId: string) =>
    request<ChannelTemplateBinding[]>(`/channel-admin/profiles/${channelProfileId}/template-bindings`),
  upsertChannelTemplateBinding: (
    channelProfileId: string,
    entryId: string,
    messageType: string,
    messageSubtype: string,
    messageVersion: string,
    payload: ChannelTemplateBindingWritePayload,
  ) =>
    request<ChannelTemplateBinding>(
      `/channel-admin/profiles/${channelProfileId}/template-bindings/${encodeURIComponent(entryId)}/${encodeURIComponent(messageType)}/${encodeURIComponent(messageSubtype)}/${encodeURIComponent(messageVersion)}`,
      jsonOptions('PUT', payload),
    ),
  deleteChannelTemplateBinding: (
    channelProfileId: string,
    entryId: string,
    messageType: string,
    messageSubtype: string,
    messageVersion: string,
    expectedRevision: number,
  ) =>
    request<ChannelTemplateBinding>(
      `/channel-admin/profiles/${channelProfileId}/template-bindings/${encodeURIComponent(entryId)}/${encodeURIComponent(messageType)}/${encodeURIComponent(messageSubtype)}/${encodeURIComponent(messageVersion)}?${new URLSearchParams({ expectedRevision: String(expectedRevision) }).toString()}`,
      jsonOptions('DELETE'),
    ),
  listChannelProviderJobs: (channelProfileId: string) =>
    request<ChannelProviderJobConfig[]>(`/channel-admin/profiles/${channelProfileId}/jobs`),
  upsertChannelProviderJob: (channelProfileId: string, jobType: string, payload: ChannelProviderJobConfigWritePayload) =>
    request<ChannelProviderJobConfig>(`/channel-admin/profiles/${channelProfileId}/jobs/${encodeURIComponent(jobType)}`, jsonOptions('PUT', payload)),
  deleteChannelProviderJob: (channelProfileId: string, jobType: string, expectedRevision: number) =>
    request<ChannelProviderJobConfig>(
      `/channel-admin/profiles/${channelProfileId}/jobs/${encodeURIComponent(jobType)}?${new URLSearchParams({ expectedRevision: String(expectedRevision) }).toString()}`,
      jsonOptions('DELETE'),
    ),
  listChannelProviderJobRuns: (channelProfileId: string, jobType: string) =>
    request<ChannelProviderJobRun[]>(`/channel-admin/profiles/${channelProfileId}/jobs/${encodeURIComponent(jobType)}/runs`),
  runChannelProviderJob: (channelProfileId: string, jobType: string) =>
    request<ChannelProviderJobRun>(`/channel-admin/profiles/${channelProfileId}/jobs/${encodeURIComponent(jobType)}/runs`, jsonOptions('POST')),
  listIntegrationAccounts: () => request<IntegrationAccount[]>('/integration/accounts'),
  getIntegrationAccount: (accountId: string) => request<IntegrationAccount>(`/integration/accounts/${accountId}`),
  createIntegrationAccount: (payload: CreateIntegrationAccountPayload) =>
    request<IntegrationAccount>('/integration/accounts', jsonOptions('POST', payload)),
  updateIntegrationAccount: (accountId: string, payload: UpdateIntegrationAccountPayload) =>
    request<IntegrationAccount>(`/integration/accounts/${accountId}`, jsonOptions('PUT', payload)),
  createIntegrationAccountCredential: (accountId: string, payload: CreateIntegrationAccountCredentialPayload) =>
    request<IntegrationAccount>(`/integration/accounts/${accountId}/credentials`, jsonOptions('POST', payload)),
  rotateIntegrationAccountCredential: (accountId: string, payload: RotateIntegrationAccountCredentialPayload) =>
    request<IntegrationAccount>(`/integration/accounts/${accountId}/credentials/rotate`, jsonOptions('POST', payload)),
  validateIntegrationAccountCredential: (accountId: string) =>
    request<IntegrationAccount>(`/integration/accounts/${accountId}/credentials/validate`, jsonOptions('POST')),
  revokeIntegrationAccountCredential: (accountId: string) =>
    request<IntegrationAccount>(`/integration/accounts/${accountId}/credentials/revoke`, jsonOptions('POST')),
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
  listRoleTemplates: () => request<RoleTemplate[]>('/role-templates'),
  createRoleTemplate: (body: CreateRoleTemplatePayload) => request<RoleTemplate>('/role-templates', jsonOptions('POST', body)),
  updateRoleTemplate: (id: string, body: OwnedConfiguration) => request<RoleTemplate>(`/role-templates/${id}`, jsonOptions('PUT', body)),
  deleteRoleTemplate: (id: string) => request<RoleTemplate>(`/role-templates/${id}`, jsonOptions('DELETE')),
  createRoleTemplateVersion: (id: string, body: CreateRoleTemplateVersionPayload) => request<RoleTemplateVersion>(`/role-templates/${id}/versions`, jsonOptions('POST', body)),
  publishRoleTemplateVersion: (id: string, versionId: string) => request<RoleTemplateVersion>(`/role-templates/${id}/versions/${versionId}/publish`, jsonOptions('POST')),
  listEntries: () => request<Entry[]>('/entries'),
  createEntry: (body: EntryPayload) => request<Entry>('/entries', jsonOptions('POST', body)),
  updateEntry: (id: string, body: EntryPayload) => request<Entry>(`/entries/${id}`, jsonOptions('PUT', body)),
  deleteEntry: (id: string) => request<Entry>(`/entries/${id}`, jsonOptions('DELETE')),
  createPlaybookVersion: (id: string, body: CreatePlaybookVersionPayload) => request<PlaybookVersion>(`/playbooks/${id}/versions`, jsonOptions('POST', body)),
  publishPlaybookVersion: (id: string, versionId: string) => request<PlaybookVersion>(`/playbooks/${id}/versions/${versionId}/publish`, jsonOptions('POST')),
  receptionEntries: () => request<Entry[]>('/agent/entries'),
  reception: (entryId?: string) => request<Reception>('/agent/reception', jsonOptions('POST', { entryId })),
  receptionDetail: (id: string, pagination?: {cursor?:string;before?:string}) => {
    const query = new URLSearchParams();
    if(pagination?.cursor) query.set('cursor',pagination.cursor);
    if(pagination?.before) query.set('before',pagination.before);
    return request<Reception>(`/agent/reception/${encodeURIComponent(id)}${query.size?'?'+query.toString():''}`);
  },
  sendMessage: (body: SendMessage) => request<CollaborationMessage>('/agent/messages', jsonOptions('POST', body)),
  agentSession: (id: string) => request<AgentSessionDetail>(`/agent/sessions/${id}`),
  agentStatus: (id: string, taskId: string) => request<AgentExecutionStatus>(`/agent/sessions/${id}/status?taskId=${encodeURIComponent(taskId)}`),
  controlAgent: (id: string, status: AgentSessionStatus) => request<AgentSession>(`/agent/sessions/${id}/control`, jsonOptions('POST', { requestId: crypto.randomUUID(), status })),
  openReceptionStream: (id: string, onReception: (value: Reception) => void, onError?: () => void) => {
    const source = new EventSource(`${API_BASE}/agent/reception/${encodeURIComponent(id)}/stream`, { withCredentials: true });
    source.addEventListener('reception', event => {
      try { onReception(JSON.parse((event as MessageEvent<string>).data) as Reception); } catch { onError?.(); }
    });
    source.onerror = () => onError?.();
    return source;
  },
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
