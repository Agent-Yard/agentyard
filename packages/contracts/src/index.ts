export type ResourceType = 'TOOL' | 'LLM_MODEL' | 'SKILL';
export type ToolProviderType = 'HTTP' | 'MCP';
export type ShareScope = 'PRIVATE' | 'DOMAIN_SHARED';
export type VersionStatus = 'DRAFT' | 'PUBLISHED';
export type TaskStatus = 'PENDING' | 'RUNNING' | 'WAITING_HUMAN' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type WorkflowStatus = 'DRAFT' | 'RUNNING' | 'WAITING_HUMAN' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type NodeStatus = 'PENDING' | 'RUNNING' | 'WAITING_HUMAN' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type OrchestrationNodeType = 'START' | 'AGENT' | 'HUMAN' | 'END';
export type HumanTaskSource = 'GRAPH_NODE' | 'AGENT_REQUEST';
export type HumanActionType = 'CONFIRM' | 'TERMINATE';

export interface HumanNodeConfig {
  title: string;
  instruction: string;
  expectedAction: string;
  resumeRouteKey: string;
}

export interface OrchestrationNode {
  nodeKey: string;
  nodeName: string;
  nodeType: OrchestrationNodeType;
  description: string;
  agentId: string | null;
  humanNode: HumanNodeConfig | null;
}

export interface OrchestrationEdge {
  edgeKey: string;
  sourceNodeKey: string;
  targetNodeKey: string;
  routeKey: string;
  label: string;
  defaultEdge: boolean;
}

export interface ExecutionCheckpoint {
  checkpointId: string;
  currentNodeKey: string;
  waitingNodeKey: string;
  statePayload: string;
  resumeCount: number;
}

export interface HumanTaskSnapshot {
  nodeKey: string;
  title: string;
  instruction: string;
  expectedAction: string;
  source: HumanTaskSource;
  allowedActions: HumanActionType[];
}

export interface PauseReasonSnapshot {
  code: string;
  detail: string;
  source: HumanTaskSource;
}

export interface ToolInvocationSnapshot {
  id: string;
  providerType: string;
  resourceId: string;
  resourceName: string;
  operation: string;
  status: string;
  detail: string;
  createdAt: string;
}

export interface ToolOutcomeSummary {
  toolResourceId: string;
  toolResourceName: string;
  operation: string;
  providerType: string;
  result: Record<string, unknown>;
}

export interface SharedSessionState {
  facts: Record<string, unknown>;
  artifacts: Record<string, unknown>;
  agentScopes: Record<string, Record<string, unknown>>;
}

export interface HumanActionRequest {
  action: HumanActionType;
  comment: string;
  operatorId: string;
  attributes: Record<string, string>;
}

export interface KnowledgeBindingSnapshot {
  knowledgeBaseId: string;
  knowledgeBaseName: string;
  knowledgeReleaseId: string;
  knowledgeReleaseVersion: string;
  snapshotId: string;
  defaultTopK: number;
  retrievalMode: string;
  minScore: number;
}

export interface KnowledgeRetrievalProfile {
  defaultTopK: number;
  retrievalMode: 'LEXICAL' | 'VECTOR' | 'HYBRID';
  minScore: number;
}

export interface KnowledgeRelease {
  id: string;
  knowledgeBaseId: string;
  version: string;
  status: VersionStatus;
  summary: string;
  snapshotId: string;
  retrievalProfile: KnowledgeRetrievalProfile;
  createdAt: string;
  publishedAt: string | null;
}

export interface KnowledgeBase {
  id: string;
  domainId: string;
  name: string;
  shareScope: ShareScope;
  ownerType: string;
  ownerId: string;
  summary: string;
  steward: string;
  tags: string[];
  latestRelease: KnowledgeRelease | null;
  effectiveRelease: KnowledgeRelease | null;
  releases: KnowledgeRelease[];
}

export interface KnowledgeReference {
  knowledgeBaseId: string;
  referenceKind: string;
  sourceType: string;
  sourceId: string;
  sourceName: string;
  knowledgeReleaseId: string | null;
  knowledgeReleaseVersion: string | null;
  blocksDeletion: boolean;
}

export interface KnowledgeUploadSession {
  id: string;
  knowledgeBaseId: string;
  status: string;
  acceptedTypes: string[];
}

export interface KnowledgeFile {
  id: string;
  knowledgeBaseId: string;
  uploadSessionId: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  status: string;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeImportJob {
  id: string;
  knowledgeBaseId: string;
  fileId: string;
  status: string;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface KnowledgeDocument {
  id: string;
  knowledgeBaseId: string;
  fileId: string;
  title: string;
  sourceUri: string;
  documentType: string;
  status: string;
  chunkCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeSnapshot {
  id: string;
  knowledgeBaseId: string;
  retrievalBackend: string;
  retrievalMode: string;
  status: string;
  documentCount: number;
  chunkCount: number;
  failureReason: string | null;
  builtAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TaskLaunchRequest {
  scenarioId: string;
  assistantId: string;
  question: string;
  requester: string;
}
