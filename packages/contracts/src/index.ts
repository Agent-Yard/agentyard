export type ResourceType = 'TOOL' | 'LLM_MODEL' | 'SKILL';
export type ToolProviderType = 'HTTP' | 'MCP';
export type ShareScope = 'PRIVATE' | 'DOMAIN_SHARED';
export type VersionStatus = 'DRAFT' | 'PUBLISHED';
export type ReferenceObjectType = 'DOMAIN' | 'SCENARIO' | 'ASSISTANT' | 'PLAYBOOK' | 'AGENT' | 'RESOURCE' | 'KNOWLEDGE_BASE';
export type PlatformAggregateType =
  | 'DOMAIN'
  | 'SCENARIO'
  | 'ASSISTANT'
  | 'AGENT'
  | 'PLAYBOOK'
  | 'RESOURCE'
  | 'KNOWLEDGE_BASE'
  | 'SESSION'
  | 'PLAYBOOK_RUN';
export type ReferenceRelationMode = 'DIRECT' | 'INDIRECT';
export type ReferenceImpactLevel = 'BLOCKS_DELETION' | 'ADVISORY';
export type KnowledgeFileStatus = 'UPLOADED' | 'IMPORTING' | 'IMPORTED' | 'FAILED';
export type KnowledgeImportJobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
export type KnowledgeImportSourceType = 'FILE_UPLOAD' | 'URL';
export type KnowledgeIndexSnapshotStatus = 'QUEUED' | 'RUNNING' | 'READY' | 'FAILED';
export type KnowledgeImportJobStage = 'QUEUED' | 'FETCHING_SOURCE' | 'PARSING' | 'CHUNKING' | 'PERSISTING' | 'SUCCEEDED' | 'FAILED';
export type KnowledgeIndexSnapshotStage = 'QUEUED' | 'COLLECTING_DOCUMENTS' | 'INDEXING' | 'READY' | 'FAILED';
export type ToolKind = 'RESOURCE' | 'BUILTIN';

export interface ToolOutcomeSummary {
  callId: string;
  toolId: string;
  toolName: string;
  toolKind: ToolKind;
  operation: string;
  providerType: string;
  resourceId: string | null;
  resourceName: string | null;
  result: Record<string, unknown>;
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

export interface KnowledgeChunkRead {
  chunkId: string;
  documentId: string;
  documentTitle: string;
  sourceUri: string;
  headingPath: string;
  pageNumber: number | null;
  content: string;
}

export interface KnowledgeSearchRequest {
  query: string;
  topK?: number;
  minScore?: number;
  retrievalMode?: 'LEXICAL' | 'VECTOR' | 'HYBRID' | null;
}

export interface KnowledgeSearchResult {
  knowledgeBaseId: string;
  knowledgeBaseName: string;
  knowledgeReleaseId: string;
  knowledgeReleaseVersion: string;
  hits: KnowledgeRetrievalPreviewHit[];
  lowConfidence: boolean;
}

export interface KnowledgeReadRequest {
  chunkIds: string[];
}

export interface KnowledgeReadResult {
  knowledgeBaseId: string;
  knowledgeBaseName: string;
  knowledgeReleaseId: string;
  knowledgeReleaseVersion: string;
  chunks: KnowledgeChunkRead[];
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

export interface ObjectReferenceRelation {
  relationKind: string;
  relationRole: string;
  relationMode: ReferenceRelationMode;
  impactLevel: ReferenceImpactLevel;
  targetType: string;
  targetId: string;
  targetName: string;
  releaseId: string | null;
  releaseVersion: string | null;
  resourceVersionId: string | null;
  resourceVersion: string | null;
  knowledgeReleaseId: string | null;
  knowledgeReleaseVersion: string | null;
}

export interface ObjectReferenceAnalysis {
  objectType: ReferenceObjectType;
  objectId: string;
  objectName: string;
  relations: ObjectReferenceRelation[];
}

export interface DeletionCascadeItem {
  action: string;
  relationKind: string;
  targetType: string;
  targetId: string;
  targetName: string;
  description: string;
  releaseVersion: string | null;
  resourceVersion: string | null;
  knowledgeReleaseVersion: string | null;
}

export interface DeletionImpactPreview {
  objectType: ReferenceObjectType;
  objectId: string;
  objectName: string;
  canDelete: boolean;
  blockers: ObjectReferenceRelation[];
  advisories: ObjectReferenceRelation[];
  cascadeDeletes: DeletionCascadeItem[];
}

export interface PlatformEvent {
  id: string;
  eventType: string;
  aggregateType: PlatformAggregateType;
  aggregateId: string;
  actorId: string | null;
  payload: Record<string, unknown>;
  occurredAt: string;
}

export interface PlatformEventPage {
  items: PlatformEvent[];
  nextCursor: string | null;
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
  sourceType: KnowledgeImportSourceType;
  sourceUri: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  status: KnowledgeFileStatus;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeImportJob {
  id: string;
  knowledgeBaseId: string;
  fileId: string;
  sourceType: KnowledgeImportSourceType;
  sourceUri: string;
  fileName: string;
  status: KnowledgeImportJobStatus;
  stage: KnowledgeImportJobStage;
  progressPercent: number;
  retryCount: number;
  retryable: boolean;
  failureReason: string | null;
  startedAt: string | null;
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
  retrievalBackend: 'PGVECTOR';
  retrievalMode: string;
  status: KnowledgeIndexSnapshotStatus;
  stage: KnowledgeIndexSnapshotStage;
  progressPercent: number;
  retryCount: number;
  retryable: boolean;
  documentCount: number;
  chunkCount: number;
  failureReason: string | null;
  startedAt: string | null;
  builtAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeRetrievalPreviewRequest {
  snapshotId: string;
  query: string;
  topK?: number;
  minScore?: number;
  retrievalMode?: 'LEXICAL' | 'VECTOR' | 'HYBRID' | null;
}

export interface KnowledgeRetrievalPreviewHit {
  chunkId: string;
  documentId: string;
  documentTitle: string;
  sourceUri: string;
  snippet: string;
  score: number;
  pageNumber: number | null;
  headingPath: string;
}

export interface KnowledgeRetrievalPreviewResult {
  hits: KnowledgeRetrievalPreviewHit[];
  lowConfidence: boolean;
}

export type SessionMessageDeliveryStatus = 'ACCEPTED' | 'BUSY' | 'REJECTED';
export type AgentDecisionAction =
  | 'REPLY'
  | 'NO_REPLY'
  | 'SWITCH_OWNER'
  | 'RUN_PLAYBOOK'
  | 'SESSION_HUMAN_HANDOFF';
export type SessionTriggerType = 'USER_MESSAGE' | 'PLAYBOOK_COMPLETED';
export type SessionActorType = 'USER' | 'AGENT' | 'SYSTEM' | 'HUMAN_OPERATOR' | 'EXTERNAL_SYSTEM';
export type SessionEventType =
  | 'USER_MESSAGE'
  | 'OWNER_REPLY'
  | 'HUMAN_OPERATOR_REPLY'
  | 'AGENT_DECISION_REJECTED'
  | 'AGENT_TURN_FAILED'
  | 'OWNER_SWITCH'
  | 'PLAYBOOK_STARTED'
  | 'PLAYBOOK_WAITING'
  | 'PLAYBOOK_RESUMED'
  | 'PLAYBOOK_COMPLETED'
  | 'SESSION_HUMAN_HANDOFF_STARTED'
  | 'SESSION_HUMAN_HANDOFF_ENDED'
  | 'HUMAN_RESUME_RECEIVED'
  | 'EXTERNAL_CALLBACK_RECEIVED';
export type PlaybookRunStatus = 'RUNNING' | 'WAITING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
export type PlaybookNodeType = 'STEP' | 'TOOL_TASK' | 'HUMAN_TASK' | 'EXTERNAL_INTERACTION' | 'END';
export type PlaybookWaitingType = 'HUMAN_TASK' | 'EXTERNAL_INTERACTION';
export type PlaybookResumeSource = 'HUMAN' | 'EXTERNAL_SYSTEM';

export interface SessionOwnerPolicy {
  maxOwnerSwitchesPerTurn: number;
}

export interface SessionPolicy {
  idleTimeout: string;
  maxWorkflowAge: string;
  maxWorkflowHistoryEvents: number;
}

export interface PlaybookExecutionPolicy {
  timeoutPolicy: string | null;
  retryPolicy: string | null;
}

export interface LlmModelDescriptor {
  resourceId: string;
  resourceName: string;
  resourceVersionId: string;
  resourceVersion: string;
  providerType: string;
  modelId: string;
  baseUrl: string;
  apiKeyEnvVar: string;
  temperature: number;
  maxTokens: number;
}

export interface KnowledgeBindingDescriptor {
  knowledgeBaseId: string;
  knowledgeBaseName: string;
  knowledgeReleaseId: string;
  knowledgeReleaseVersion: string;
  snapshotId: string;
  defaultTopK: number;
  retrievalMode: string;
  minScore: number;
}

export interface SkillDescriptor {
  resourceId: string;
  resourceName: string;
  resourceVersionId: string;
  resourceVersion: string;
  skillName: string;
  skillDesc: string;
  skillPrompt: string;
}

export interface ToolOperationDescriptor {
  name: string;
  description: string;
  inputSchema: string;
  outputSchema: string;
}

export interface HttpToolProviderDescriptor {
  endpoint: string;
  method: string;
}

export interface McpToolProviderDescriptor {
  serverName: string;
  transport: string;
  connectionUri: string;
  namespace: string;
  heartbeatSeconds: number;
  operationMappings: Record<string, string>;
}

export interface ToolDescriptor {
  resourceId: string;
  resourceName: string;
  resourceVersionId: string;
  resourceVersion: string;
  operations: ToolOperationDescriptor[];
  providerType: string;
  authType: string;
  timeoutSeconds: number;
  retryPolicy: string;
  http: HttpToolProviderDescriptor | null;
  mcp: McpToolProviderDescriptor | null;
}

export interface AssistantSessionConfig {
  assistantId: string;
  assistantName: string;
  assistantReleaseVersion: string;
  primaryAgentId: string;
  ownerPolicy: SessionOwnerPolicy;
  sessionPolicy: SessionPolicy;
  playbookPolicy: PlaybookExecutionPolicy;
}

export interface OwnerAgentConfig {
  agentId: string;
  name: string;
  role: string;
  responsibility: string;
  model: LlmModelDescriptor | null;
  systemPrompt: string;
  knowledgeEnabled: boolean;
  knowledgeBaseId: string | null;
  knowledgeBinding: KnowledgeBindingDescriptor | null;
  memoryWindowSize: number;
  canOwnSession: boolean;
  allowedActions: AgentDecisionAction[];
  switchableOwnerAgentIds: string[];
  playbookIds: string[];
  skills: SkillDescriptor[];
  tools: ToolDescriptor[];
}

export interface PlaybookNode {
  nodeKey: string;
  nodeName: string;
  nodeType: PlaybookNodeType;
  description: string;
  scriptRef: string | null;
  scriptVersion: string | null;
  toolId: string | null;
  toolOperation: string | null;
  config: Record<string, unknown>;
}

export interface PlaybookEdge {
  edgeKey: string;
  sourceNodeKey: string;
  targetNodeKey: string;
  routeKey: string;
  label: string;
  defaultEdge: boolean;
}

export interface PlaybookConfig {
  playbookId: string;
  name: string;
  description: string;
  inputSchema: string;
  resultSchema: string;
  executionPolicy: PlaybookExecutionPolicy | null;
  allowHumanTask: boolean;
  allowExternalInteraction: boolean;
  entryNodeKey: string;
  nodes: PlaybookNode[];
  edges: PlaybookEdge[];
}

export interface SessionEvent {
  eventId: string;
  sessionId: string;
  sequence: number;
  eventType: SessionEventType;
  createdAt: string;
  actorType: SessionActorType;
  actorId: string | null;
  payload: Record<string, unknown>;
  relatedPlaybookRunId: string | null;
  relatedOwnerAgentId: string | null;
}

export interface PlaybookRun {
  runId: string;
  sessionId: string;
  parentSessionEventId: string;
  playbookId: string;
  ownerAgentId: string;
  status: PlaybookRunStatus;
  input: Record<string, unknown>;
  result: Record<string, unknown>;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
  waitingReason: string | null;
}

export interface ActivePlaybookSummary {
  runId: string;
  playbookId: string;
  playbookName: string;
  status: PlaybookRunStatus;
  waitingReason: string | null;
  latestResult: Record<string, unknown>;
}

export interface SessionTrigger {
  triggerType: SessionTriggerType;
  eventId: string;
  payload: Record<string, unknown>;
}

export interface AgentDecision {
  action: AgentDecisionAction;
  replyContent: string | null;
  targetAgentId: string | null;
  playbookId: string | null;
  playbookInput: Record<string, unknown>;
  accompanyingReply: string | null;
}

export interface AgentTurnRequestV2 {
  sessionId: string;
  assistantId: string;
  assistantReleaseVersion: string;
  currentOwner: OwnerAgentConfig;
  availableAgents: OwnerAgentConfig[];
  availablePlaybooks: PlaybookConfig[];
  activePlaybook: ActivePlaybookSummary | null;
  sharedState: Record<string, unknown>;
  trigger: SessionTrigger;
  recentEvents: SessionEvent[];
}

export interface AgentTurnResultV2 {
  decision: AgentDecision;
  sharedState: Record<string, unknown>;
}

export interface SessionUserMessageUpdateResult {
  status: SessionMessageDeliveryStatus;
  sessionId: string;
  reason: string | null;
}

export interface UserMessageV2 {
  messageId: string;
  customerId: string;
  content: string;
  payload: Record<string, unknown>;
}

export interface SessionStartRequestV2 {
  sessionId: string;
  scenarioId: string;
  sessionTitle: string;
  customerId: string;
  assistant: AssistantSessionConfig;
  agents: OwnerAgentConfig[];
  playbooks: PlaybookConfig[];
  initialSharedState: Record<string, unknown>;
}

export interface HumanResumeSignal {
  sessionId: string;
  playbookRunId: string;
  payload: Record<string, unknown>;
}

export interface ExternalCallbackSignal {
  sessionId: string;
  playbookRunId: string;
  payload: Record<string, unknown>;
}

export interface HumanResumeRequest {
  playbookRunId: string;
  payload: Record<string, unknown>;
}

export interface ExternalCallbackRequest {
  playbookRunId: string;
  payload: Record<string, unknown>;
}

export interface HumanOperatorReplyRequest {
  operatorId: string;
  message: string;
  payload: Record<string, unknown>;
}

export interface SessionSnapshot {
  sessionId: string;
  assistantId: string;
  assistantReleaseVersion: string;
  primaryAgentId: string;
  currentOwnerAgentId: string;
  ownerSwitchCountInTurn: number;
  sharedState: Record<string, unknown>;
  activePlaybookRunId: string | null;
  agentTurnActive: boolean;
  sessionHumanHandoffActive: boolean;
  pendingOwnerReevaluation: boolean;
  draining: boolean;
  idleDeadline: string | null;
}

export interface PlaybookStartRequest {
  sessionId: string;
  playbookRunId: string;
  triggeringEventId: string;
  ownerAgentId: string;
  ownerAgent: OwnerAgentConfig;
  playbook: PlaybookConfig;
  input: Record<string, unknown>;
}

export interface PlaybookToolTaskRequest {
  sessionId: string;
  playbookRunId: string;
  playbookId: string;
  nodeKey: string;
  nodeName: string;
  ownerAgent: OwnerAgentConfig;
  toolId: string;
  toolOperation: string;
  input: Record<string, unknown>;
  config: Record<string, unknown>;
}

export interface PlaybookToolTaskResult {
  statePatch: Record<string, unknown>;
  routeKey: string | null;
  terminalStatus: PlaybookRunStatus | null;
  failureReason: string | null;
}

export interface PlaybookResumeSignal {
  playbookRunId: string;
  source: PlaybookResumeSource;
  payload: Record<string, unknown>;
}
