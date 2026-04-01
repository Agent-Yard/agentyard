export type ResourceType = 'TOOL' | 'LLM_MODEL' | 'SKILL';
export type ToolProviderType = 'HTTP' | 'MCP';
export type ShareScope = 'PRIVATE' | 'DOMAIN_SHARED';
export type VersionStatus = 'DRAFT' | 'PUBLISHED';
export type TaskStatus = 'PENDING' | 'RUNNING' | 'WAITING_RESUME' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type WorkflowStatus = 'DRAFT' | 'RUNNING' | 'WAITING_RESUME' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type NodeStatus = 'PENDING' | 'RUNNING' | 'WAITING_RESUME' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type OrchestrationNodeType = 'START' | 'AGENT' | 'HUMAN' | 'END';
export type PauseSource = 'GRAPH_NODE' | 'AGENT_REQUEST' | 'EXTERNAL_INTERACTION' | 'TIMEOUT_POLICY';
export type ResumeSource = 'HUMAN' | 'EXTERNAL_SYSTEM' | 'TIMEOUT_POLICY';
export type ResumeActionType = 'CONTINUE' | 'TERMINATE';
export type ResumeInterventionStatus = 'PENDING' | 'APPLIED' | 'FAILED';
export type DecisionType = 'FINAL' | 'TOOL_CALL' | 'SKILL_READ' | 'HUMAN_HANDOFF';
export type WorkflowFailureCategory =
  | 'TIMEOUT'
  | 'PROVIDER_FAILURE'
  | 'TOOL_FAILURE'
  | 'PARSING_FAILURE'
  | 'VALIDATION_FAILURE'
  | 'CONFIGURATION_FAILURE'
  | 'RUNTIME_FAILURE'
  | 'UNKNOWN';
export type SessionStatePatchTarget = 'FACTS' | 'ARTIFACTS' | 'AGENT_SCOPE';
export type SessionStatePatchOpType = 'UPSERT' | 'REMOVE';
export type ReferenceObjectType = 'DOMAIN' | 'SCENARIO' | 'ASSISTANT' | 'AGENT' | 'RESOURCE' | 'KNOWLEDGE_BASE';
export type ReferenceRelationMode = 'DIRECT' | 'INDIRECT';
export type ReferenceImpactLevel = 'BLOCKS_DELETION' | 'ADVISORY';
export type KnowledgeFileStatus = 'UPLOADED' | 'IMPORTING' | 'IMPORTED' | 'FAILED';
export type KnowledgeImportJobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
export type KnowledgeImportSourceType = 'FILE_UPLOAD' | 'URL';
export type KnowledgeIndexSnapshotStatus = 'QUEUED' | 'RUNNING' | 'READY' | 'FAILED';
export type KnowledgeImportJobStage = 'QUEUED' | 'FETCHING_SOURCE' | 'PARSING' | 'CHUNKING' | 'PERSISTING' | 'SUCCEEDED' | 'FAILED';
export type KnowledgeIndexSnapshotStage = 'QUEUED' | 'COLLECTING_DOCUMENTS' | 'INDEXING' | 'READY' | 'FAILED';

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
  resumeContext: ResumeContextSnapshot | null;
  resumeCount: number;
}

export interface ResumeContextSnapshot {
  source: ResumeSource;
  reasonCode: string;
  interactionTaskId: string | null;
  interactionType: string | null;
  timeoutPolicyKey: string | null;
}

export interface ResumeTaskSnapshot {
  nodeKey: string;
  title: string;
  instruction: string;
  expectedAction: string;
  source: PauseSource;
  allowedActions: ResumeActionType[];
}

export interface PauseReasonSnapshot {
  code: string;
  detail: string;
  source: PauseSource;
}

export interface WorkflowFailureSnapshot {
  category: WorkflowFailureCategory;
  code: string;
  rootCause: string;
  detail: string;
  failedNodeKey: string | null;
  failedNodeName: string | null;
  failedResourceId: string | null;
  failedResourceName: string | null;
  occurredAt: string;
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

export interface ToolRequest {
  toolResourceVersionId: string;
  operation: string;
  arguments: Record<string, unknown>;
}

export interface HumanRequest {
  title: string;
  instruction: string;
  expectedAction: string;
}

export interface SessionStatePatchOp {
  target: SessionStatePatchTarget;
  op: SessionStatePatchOpType;
  path: string[];
  value?: unknown;
}

export interface SessionStatePatch {
  ops: SessionStatePatchOp[];
}

export interface StructuredAgentDecision {
  decisionType: DecisionType;
  message: string;
  routeDecision: string | null;
  skillReads: string[];
  toolRequests: ToolRequest[];
  humanRequest: HumanRequest | null;
  sessionStatePatch: SessionStatePatch | null;
}

export interface AgentTurnLog {
  turnIndex: number;
  phase: string;
  decisionType: DecisionType | null;
  loadedSkillsDelta: number;
  sessionStateOpsDelta: number;
  toolCallsDelta: number;
  routeSource: string;
  failureReason: string;
}

export interface AgentTurnState {
  phase: string;
  turnIndex: number;
  latestDecision: StructuredAgentDecision | null;
  turnLogs: AgentTurnLog[];
}

export interface ResumeActionRequest {
  type: ResumeActionType;
  comment: string;
  userId: string;
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

export interface TaskLaunchRequest {
  scenarioId: string;
  assistantId: string;
  question: string;
  customerId: string;
}

export interface TaskInstance {
  id: string;
  scenarioId: string;
  assistantId: string;
  assistantName: string;
  assistantReleaseVersion: string;
  question: string;
  customerId: string;
  status: TaskStatus;
  createdAt: string;
  workflowInstanceId: string;
}

export interface NodeExecution {
  id: string;
  workflowInstanceId: string;
  nodeKey: string;
  nodeName: string;
  status: NodeStatus;
  detail: string;
  updatedAt: string;
}

export interface ResumeIntervention {
  id: string;
  workflowInstanceId: string;
  type: ResumeActionType;
  source: ResumeSource;
  userId: string;
  comment: string;
  attributes?: Record<string, string>;
  status?: ResumeInterventionStatus;
  createdAt: string;
  appliedAt?: string | null;
  failureReason?: string | null;
}

export type ModelSelectionSource = 'ASSISTANT_DEFAULT' | 'AGENT_OVERRIDE';

export interface ModelHitSnapshot {
  agentId: string;
  agentName: string;
  nodeKey: string;
  nodeName: string;
  source: ModelSelectionSource;
  resourceId: string;
  resourceName: string;
  resourceVersionId: string;
  resourceVersion: string;
  providerType: string;
  modelId: string;
  turnIndex: number;
  capturedAt: string;
}

export interface WorkflowInstance {
  id: string;
  taskId: string;
  assistantId: string;
  assistantName: string;
  assistantReleaseVersion: string;
  createdAt: string;
  updatedAt: string;
  status: WorkflowStatus;
  summary: string;
  finalReply: string | null;
  currentNodeKey: string | null;
  escalationRequired: boolean;
  checkpoint: ExecutionCheckpoint | null;
  resumeTask: ResumeTaskSnapshot | null;
  pauseReason: PauseReasonSnapshot | null;
  latestFailure: WorkflowFailureSnapshot | null;
  latestToolOutcome: ToolOutcomeSummary | null;
  resourceAnchors: string[];
  nodes: NodeExecution[];
  toolCalls: ToolInvocationSnapshot[];
  modelHits: ModelHitSnapshot[];
  resumeInterventions: ResumeIntervention[];
  loadedSkillResourceVersionIds: string[];
  sharedState: SharedSessionState;
  agentTurnState: AgentTurnState | null;
}

export interface ConversationMessage {
  id: string;
  sessionId: string;
  role: 'USER' | 'ASSISTANT' | 'SYSTEM';
  senderType: 'USER' | 'ASSISTANT' | 'SYSTEM';
  senderId: string;
  senderName: string;
  content: string;
  createdAt: string;
  taskId: string | null;
  workflowInstanceId: string | null;
}

export interface ConversationSession {
  id: string;
  scenarioId: string;
  title: string;
  customerId: string;
  assistantId: string;
  assistantName: string;
  assistantReleaseVersion: string;
  createdAt: string;
  updatedAt: string;
  messages: ConversationMessage[];
  latestTaskId: string | null;
  latestWorkflowInstanceId: string | null;
  latestToolOutcome: ToolOutcomeSummary | null;
  latestResumeTask: ResumeTaskSnapshot | null;
  latestPauseReason: PauseReasonSnapshot | null;
  loadedSkillResourceVersionIds: string[];
  sharedState: SharedSessionState;
}

export interface CreateConversationSessionRequest {
  scenarioId: string;
  assistantId: string;
  customerId: string;
  openingMessage: string;
}

export interface ConversationMessageRequest {
  customerId: string;
  message: string;
}
