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

export interface TaskInstance {
  id: string;
  scenarioId: string;
  assistantId: string;
  assistantName: string;
  assistantReleaseVersion: string;
  question: string;
  requester: string;
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

export interface HumanIntervention {
  id: string;
  workflowInstanceId: string;
  action: string;
  operator: string;
  comment: string;
  attributes?: Record<string, string>;
  status?: string;
  createdAt: string;
  appliedAt?: string | null;
  failureReason?: string | null;
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
  humanTask: HumanTaskSnapshot | null;
  pauseReason: PauseReasonSnapshot | null;
  latestFailure: WorkflowFailureSnapshot | null;
  latestToolOutcome: ToolOutcomeSummary | null;
  resourceAnchors: string[];
  nodes: NodeExecution[];
  toolCalls: ToolInvocationSnapshot[];
  interventions: HumanIntervention[];
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
  requester: string;
  assistantId: string;
  assistantName: string;
  assistantReleaseVersion: string;
  createdAt: string;
  updatedAt: string;
  messages: ConversationMessage[];
  latestTaskId: string | null;
  latestWorkflowInstanceId: string | null;
  latestToolOutcome: ToolOutcomeSummary | null;
  latestHumanTask: HumanTaskSnapshot | null;
  latestPauseReason: PauseReasonSnapshot | null;
  loadedSkillResourceVersionIds: string[];
  sharedState: SharedSessionState;
}

export interface CreateConversationSessionRequest {
  scenarioId: string;
  assistantId: string;
  requester: string;
  openingMessage: string;
}

export interface ConversationMessageRequest {
  requester: string;
  message: string;
}
