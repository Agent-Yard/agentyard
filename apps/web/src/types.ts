export type Role = 'PLATFORM_ADMIN' | 'DOMAIN_ADMIN' | 'DEVELOPER' | 'BUSINESS_USER';
export type ResourceType = 'TOOL' | 'KNOWLEDGE_BASE' | 'LLM_MODEL' | 'SKILL';
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

export interface KnowledgeBaseConfig {
  indexSnapshotId: string | null;
  defaultTopK: number;
  retrievalMode: 'LEXICAL' | 'VECTOR' | 'HYBRID';
  minScore: number;
}

export interface KnowledgeUploadSession {
  id: string;
  resourceId: string;
  status: string;
  acceptedTypes: string[];
}

export interface KnowledgeFile {
  id: string;
  resourceId: string;
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
  resourceId: string;
  fileId: string;
  status: string;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface KnowledgeDocument {
  id: string;
  resourceId: string;
  fileId: string;
  title: string;
  sourceUri: string;
  documentType: string;
  status: string;
  chunkCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeIndexSnapshot {
  id: string;
  resourceId: string;
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

export interface KnowledgeUploadCompletion {
  file: KnowledgeFile;
  importJob: KnowledgeImportJob;
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
  knowledgeBase?: KnowledgeBaseConfig | null;
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
  knowledgeBaseResourceId: string | null;
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
  knowledgeBaseResourceId: string | null;
  memoryWindowSize: number;
  skillResourceIds: string[];
  toolResourceIds: string[];
}

export interface Agent {
  id: string;
  assistantId: string;
  name: string;
  role: string;
  instructions: string;
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
  routeKey: string | null;
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
  instructions: string;
  executionPolicy: AgentExecutionPolicy;
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
  orchestrations: AssistantOrchestration[];
  resourceCenter: ResourceCenter;
  resourceBlueprints: ResourceBlueprint[];
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

export interface ToolOutcomeSummary {
  toolResourceId: string;
  toolResourceName: string;
  operation: string;
  providerType: string;
  status: string;
  externalReference: string;
  recommendedAction: string;
  detail: string;
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
  createdAt: string;
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
  latestToolOutcome: ToolOutcomeSummary | null;
  resourceAnchors: string[];
  nodes: NodeExecution[];
  toolCalls: ToolInvocationSnapshot[];
  interventions: HumanIntervention[];
  loadedSkillResourceVersionIds: string[];
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
}

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
  instructions: string;
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

export interface UpdateAgentPayload {
  name: string;
  role: string;
  instructions: string;
  executionPolicy: AgentExecutionPolicy;
}

export interface UpdateOrchestrationPayload {
  executionMode: string;
  nodes: OrchestrationNode[];
  edges: OrchestrationEdge[];
}

export interface CreateConversationSessionPayload {
  scenarioId: string;
  assistantId: string;
  requester: string;
  openingMessage: string;
}

export interface ConversationMessagePayload {
  requester: string;
  message: string;
}
