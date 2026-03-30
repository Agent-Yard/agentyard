import type {
  AgentTurnLog as ContractsAgentTurnLog,
  AgentTurnState as ContractsAgentTurnState,
  ConversationMessage as ContractsConversationMessage,
  ConversationMessageRequest as ContractsConversationMessageRequest,
  ConversationSession as ContractsConversationSession,
  CreateConversationSessionRequest as ContractsCreateConversationSessionRequest,
  DecisionType as ContractsDecisionType,
  ExecutionCheckpoint as ContractsExecutionCheckpoint,
  HumanIntervention as ContractsHumanIntervention,
  HumanRequest as ContractsHumanRequest,
  HumanTaskSnapshot as ContractsHumanTaskSnapshot,
  NodeExecution as ContractsNodeExecution,
  PauseReasonSnapshot as ContractsPauseReasonSnapshot,
  SessionStatePatch as ContractsSessionStatePatch,
  SessionStatePatchOp as ContractsSessionStatePatchOp,
  SharedSessionState as ContractsSharedSessionState,
  StructuredAgentDecision as ContractsStructuredAgentDecision,
  TaskInstance as ContractsTaskInstance,
  ToolInvocationSnapshot as ContractsToolInvocationSnapshot,
  ToolOutcomeSummary as ContractsToolOutcomeSummary,
  ToolRequest as ContractsToolRequest,
  WorkflowInstance as ContractsWorkflowInstance,
} from '../../../packages/contracts/src';

export type Role = 'PLATFORM_ADMIN' | 'DOMAIN_ADMIN' | 'DEVELOPER' | 'BUSINESS_USER';
export type ResourceType = 'TOOL' | 'LLM_MODEL' | 'SKILL';
export type ToolProviderType = 'HTTP' | 'MCP';
export type ShareScope = 'PRIVATE' | 'DOMAIN_SHARED';
export type ResourceOwnerType = 'DOMAIN' | 'ASSISTANT';
export type VersionStatus = 'DRAFT' | 'PUBLISHED';
export type TaskStatus = 'PENDING' | 'RUNNING' | 'WAITING_HUMAN' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type WorkflowStatus = 'DRAFT' | 'RUNNING' | 'WAITING_HUMAN' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type NodeStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'WAITING_HUMAN' | 'CANCELLED';
export type HumanTaskSource = 'GRAPH_NODE' | 'AGENT_REQUEST';
export type HumanActionType = 'CONFIRM' | 'TERMINATE';
export type OrchestrationNodeType = 'START' | 'AGENT' | 'HUMAN' | 'END';
export type DecisionType = ContractsDecisionType;

export interface UserSession {
  userId: string;
  displayName: string;
  currentRole: Role;
  availableRoles: Role[];
}

export interface Version {
  version: string;
  status: VersionStatus;
  updatedAt: string;
}

export interface KnowledgeRetrievalProfile {
  defaultTopK: number;
  retrievalMode: 'LEXICAL' | 'VECTOR' | 'HYBRID';
  minScore: number;
}

export type KnowledgeBaseConfig = KnowledgeRetrievalProfile;

export interface KnowledgeBindingSnapshot extends KnowledgeRetrievalProfile {
  knowledgeBaseId: string;
  knowledgeBaseName: string;
  knowledgeReleaseId: string;
  knowledgeReleaseVersion: string;
  snapshotId: string;
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

export type KnowledgeIndexSnapshot = KnowledgeSnapshot;

export interface KnowledgeUploadCompletion {
  file: KnowledgeFile;
  importJob: KnowledgeImportJob;
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
  ownerType: ResourceOwnerType;
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

export interface ToolOperation {
  name: string;
  description: string;
  inputSchema: string;
  outputSchema: string;
}

export interface HttpToolProviderConfig {
  endpoint: string;
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'RPC';
}

export interface McpToolProviderConfig {
  serverName: string;
  transport: 'SSE' | 'STREAMABLE_HTTP' | 'STDIO';
  connectionUri: string;
  namespace: string;
  heartbeatSeconds: number;
  operationMappings: Record<string, string>;
}

export interface ToolConfig {
  operations: ToolOperation[];
  providerType: ToolProviderType;
  authType: 'NONE' | 'API_KEY' | 'SERVICE_ACCOUNT' | 'OAUTH';
  timeoutSeconds: number;
  retryPolicy: string;
  http?: HttpToolProviderConfig | null;
  mcp?: McpToolProviderConfig | null;
}

export interface LlmModelConfig {
  providerType: 'OPENAI' | 'ANTHROPIC' | 'GEMINI' | 'OPENAI_COMPATIBLE';
  modelId: string;
  baseUrl: string;
  apiKeyEnvVar: string;
  organization: string;
  project: string;
  region: string;
  temperature: number;
  maxTokens: number;
}

export interface SkillConfig {
  skillName: string;
  skillDesc: string;
  skillPrompt: string;
}

export interface ResourceVersionConfiguration {
  type: ResourceType;
  tool?: ToolConfig | null;
  llmModel?: LlmModelConfig | null;
  skill?: SkillConfig | null;
}

export interface ResourceVersion {
  id: string;
  resourceId: string;
  version: string;
  status: VersionStatus;
  summary: string;
  createdAt: string;
  publishedAt: string | null;
  configuration: ResourceVersionConfiguration;
}

export interface ResourceBlueprint {
  type: ResourceType;
  label: string;
  description: string;
  maintainedFields: string[];
  defaultConfiguration: ResourceVersionConfiguration;
}

export interface AssistantModelPolicy {
  providerResourceId: string | null;
}

export interface RagPolicy {
  enabled: boolean;
  knowledgeBaseId: string | null;
}

export interface MemoryPolicy {
  enabled: boolean;
  windowSize: number;
}

export interface AgentExecutionPolicy {
  inheritAssistantDefaults: boolean;
  modelResourceId: string | null;
  systemPrompt: string;
  ragEnabled: boolean;
  inheritAssistantKnowledge: boolean;
  knowledgeBaseId: string | null;
  memoryWindowSize: number;
  skillResourceIds: string[];
  toolResourceIds: string[];
}

export interface Agent {
  id: string;
  assistantId: string;
  name: string;
  role: string;
  responsibility: string;
  executionPolicy: AgentExecutionPolicy;
}

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

export interface AssistantOrchestration {
  assistantId: string;
  assistantName: string;
  scenarioId: string;
  executionMode: string;
  nodes: OrchestrationNode[];
  edges: OrchestrationEdge[];
}

export interface AssistantReleaseResource {
  resourceId: string;
  resourceName: string;
  resourceType: ResourceType;
  resourceVersionId: string;
  resourceVersion: string;
  boundAgents: string[];
  configuration: ResourceVersionConfiguration;
}

export interface AssistantReleaseAgent {
  agentId: string;
  name: string;
  role: string;
  responsibility: string;
  executionPolicy: AgentExecutionPolicy;
  knowledge: KnowledgeBindingSnapshot | null;
  skillResourceVersionIds: string[];
  toolResourceVersionIds: string[];
}

export interface AssistantRelease {
  id: string;
  assistantId: string;
  releaseVersion: string;
  status: VersionStatus;
  createdAt: string;
  publishedAt: string | null;
  assistantKnowledge: KnowledgeBindingSnapshot | null;
  resources: AssistantReleaseResource[];
  agents: AssistantReleaseAgent[];
  orchestration: AssistantOrchestration;
  modelPolicy: AssistantModelPolicy;
  ragPolicy: RagPolicy;
  memoryPolicy: MemoryPolicy;
}

export interface Assistant {
  id: string;
  scenarioId: string;
  name: string;
  description: string;
  version: Version;
  agents: Agent[];
  currentRelease: AssistantRelease | null;
  releases: AssistantRelease[];
  modelPolicy: AssistantModelPolicy;
  ragPolicy: RagPolicy;
  memoryPolicy: MemoryPolicy;
}

export interface Resource {
  id: string;
  domainId: string;
  name: string;
  type: ResourceType;
  shareScope: ShareScope;
  ownerType: ResourceOwnerType;
  ownerId: string;
  summary: string;
  steward: string;
  tags: string[];
  latestVersion: ResourceVersion | null;
  effectiveVersion: ResourceVersion | null;
  versions: ResourceVersion[];
}

export interface Scenario {
  id: string;
  domainId: string;
  name: string;
  goal: string;
  version: Version;
  assistants: Assistant[];
}

export interface BusinessDomain {
  id: string;
  name: string;
  description: string;
  scenarios: Scenario[];
  resources: Resource[];
  knowledgeBases: KnowledgeBase[];
}

export interface ResourceReference {
  resourceId: string;
  resourceName: string;
  type: ResourceType;
  shareScope: ShareScope;
  ownerLabel: string;
  latestVersion: string | null;
  effectiveVersion: string | null;
  referenceKind: string;
  sourceType: string;
  sourceId: string;
  sourceName: string;
  resourceVersionId: string | null;
  resourceVersion: string | null;
  blocksDeletion: boolean;
}

export interface ResourceCenter {
  totalResources: number;
  domainSharedResources: number;
  privateResources: number;
  references: ResourceReference[];
}

export interface CatalogSummary {
  domains: BusinessDomain[];
  scenarios: Scenario[];
  assistants: Assistant[];
  agents: Agent[];
  resources: Resource[];
  knowledgeBases: KnowledgeBase[];
  orchestrations: AssistantOrchestration[];
  resourceCenter: ResourceCenter;
  resourceBlueprints: ResourceBlueprint[];
}

export type TaskInstance = ContractsTaskInstance;
export type ToolOutcomeSummary = ContractsToolOutcomeSummary;
export type SharedSessionState = ContractsSharedSessionState;
export type ToolInvocationSnapshot = ContractsToolInvocationSnapshot;
export type ExecutionCheckpoint = ContractsExecutionCheckpoint;
export type HumanTaskSnapshot = ContractsHumanTaskSnapshot;
export type PauseReasonSnapshot = ContractsPauseReasonSnapshot;
export type NodeExecution = ContractsNodeExecution;
export type HumanIntervention = ContractsHumanIntervention;
export type ToolRequest = ContractsToolRequest;
export type HumanRequest = ContractsHumanRequest;
export type SessionStatePatchOp = ContractsSessionStatePatchOp;
export type SessionStatePatch = ContractsSessionStatePatch;
export type StructuredAgentDecision = ContractsStructuredAgentDecision;
export type AgentTurnLog = ContractsAgentTurnLog;
export type AgentTurnState = ContractsAgentTurnState;
export type WorkflowInstance = ContractsWorkflowInstance;
export type ConversationMessage = ContractsConversationMessage;
export type ConversationSession = ContractsConversationSession;

export interface CreateAssistantPayload {
  scenarioId: string;
  name: string;
  description: string;
  modelPolicy: AssistantModelPolicy;
  ragPolicy: RagPolicy;
  memoryPolicy: MemoryPolicy;
}

export interface CreateDomainPayload {
  name: string;
  description: string;
}

export interface UpdateDomainPayload {
  name: string;
  description: string;
}

export interface CreateScenarioPayload {
  domainId: string;
  name: string;
  goal: string;
}

export interface UpdateScenarioPayload {
  name: string;
  goal: string;
}

export interface UpdateAssistantPayload {
  name: string;
  description: string;
  status: VersionStatus;
  modelPolicy: AssistantModelPolicy;
  ragPolicy: RagPolicy;
  memoryPolicy: MemoryPolicy;
}

export interface CreateAgentPayload {
  assistantId: string;
  name: string;
  role: string;
  responsibility: string;
  executionPolicy: AgentExecutionPolicy;
}

export interface CreateResourcePayload {
  domainId: string;
  name: string;
  type: ResourceType;
  shareScope: ShareScope;
  ownerType: ResourceOwnerType;
  ownerId: string;
  summary: string;
  steward: string;
  tags: string[];
  initialVersion: CreateResourceVersionPayload;
}

export interface CreateResourceVersionPayload {
  summary: string;
  status: VersionStatus;
  configuration: ResourceVersionConfiguration;
}

export interface UpdateResourcePayload {
  name: string;
  shareScope: ShareScope;
  ownerType: ResourceOwnerType;
  ownerId: string;
  summary: string;
  steward: string;
  tags: string[];
}

export interface UpdateResourceVersionPayload {
  summary: string;
  status: VersionStatus;
  configuration: ResourceVersionConfiguration;
}

export interface UpdateAgentPayload {
  name: string;
  role: string;
  responsibility: string;
  executionPolicy: AgentExecutionPolicy;
}

export interface UpdateOrchestrationPayload {
  executionMode: string;
  nodes: OrchestrationNode[];
  edges: OrchestrationEdge[];
}

export type CreateConversationSessionPayload = ContractsCreateConversationSessionRequest;
export type ConversationMessagePayload = ContractsConversationMessageRequest;

export interface CreateKnowledgeBasePayload {
  domainId: string;
  name: string;
  shareScope: ShareScope;
  ownerType: ResourceOwnerType;
  ownerId: string;
  summary: string;
  steward: string;
  tags: string[];
}

export interface UpdateKnowledgeBasePayload {
  name: string;
  shareScope: ShareScope;
  ownerType: ResourceOwnerType;
  ownerId: string;
  summary: string;
  steward: string;
  tags: string[];
}

export interface CreateKnowledgeReleasePayload {
  summary: string;
  status: VersionStatus;
  snapshotId: string;
  retrievalProfile: KnowledgeRetrievalProfile;
}
