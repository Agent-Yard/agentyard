export type Role = 'PLATFORM_ADMIN' | 'DOMAIN_ADMIN' | 'DEVELOPER' | 'BUSINESS_USER';
export type ResourceType = 'SKILL' | 'MCP' | 'KNOWLEDGE_BASE' | 'LLM_MODEL' | 'PROMPT_TEMPLATE';
export type ShareScope = 'PRIVATE' | 'DOMAIN_SHARED';
export type ResourceOwnerType = 'DOMAIN' | 'ASSISTANT';
export type VersionStatus = 'DRAFT' | 'PUBLISHED';
export type TaskStatus = 'PENDING' | 'RUNNING' | 'WAITING_HUMAN' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type WorkflowStatus = 'DRAFT' | 'RUNNING' | 'WAITING_HUMAN' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type NodeStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'WAITING_HUMAN';

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

export interface ResourceBinding {
  id: string;
  resourceId: string;
  resourceVersionId: string;
  resourceVersion: string;
  consumerType: string;
  consumerId: string;
  createdAt: string;
}

export interface ResourceVersion {
  id: string;
  resourceId: string;
  version: string;
  status: VersionStatus;
  summary: string;
  configDigest: string;
  createdAt: string;
  publishedAt: string | null;
  configuration: ResourceVersionConfiguration;
}

export interface KnowledgeBaseConfig {
  sourceType: 'OBJECT_STORAGE' | 'WEB_SYNC' | 'MANUAL_IMPORT';
  sourceLocation: string;
  syncMode: 'MANUAL' | 'SCHEDULED';
  retrievalMode: 'SEMANTIC' | 'HYBRID' | 'KEYWORD';
  embeddingModel: string;
  chunkStrategy: string;
  defaultTopK: number;
  documentCount: number;
}

export interface SkillConfig {
  runtime: 'HTTP' | 'WORKFLOW_ACTIVITY' | 'FUNCTION_CALL';
  endpoint: string;
  method: 'GET' | 'POST' | 'RPC';
  authType: 'NONE' | 'API_KEY' | 'SERVICE_ACCOUNT';
  timeoutSeconds: number;
  retryPolicy: string;
  inputSchema: string;
  outputSchema: string;
}

export interface McpConfig {
  serverName: string;
  transport: 'SSE' | 'STREAMABLE_HTTP' | 'STDIO';
  connectionUri: string;
  namespace: string;
  authType: 'NONE' | 'API_KEY' | 'OAUTH';
  heartbeatSeconds: number;
  exposedTools: string[];
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

export interface PromptTemplateConfig {
  templateType: 'CHAT' | 'ROUTER' | 'STRUCTURED_OUTPUT';
  systemPrompt: string;
  userPromptTemplate: string;
  responseFormat: string;
}

export interface ResourceVersionConfiguration {
  type: ResourceType;
  knowledgeBase?: KnowledgeBaseConfig;
  skill?: SkillConfig;
  mcp?: McpConfig;
  llmModel?: LlmModelConfig;
  promptTemplate?: PromptTemplateConfig;
}

export interface ResourceBlueprint {
  type: ResourceType;
  label: string;
  description: string;
  maintainedFields: string[];
  defaultConfiguration: ResourceVersionConfiguration;
}

export interface Agent {
  id: string;
  assistantId: string;
  name: string;
  role: string;
  instructions: string;
  bindings: ResourceBinding[];
  executionPolicy: AgentExecutionPolicy;
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

export interface AssistantModelPolicy {
  providerResourceId: string | null;
  promptTemplateResourceId: string | null;
  temperature: number;
  maxTokens: number;
}

export interface RagPolicy {
  enabled: boolean;
  knowledgeBaseResourceId: string | null;
  topK: number;
}

export interface MemoryPolicy {
  enabled: boolean;
  windowSize: number;
}

export interface AgentExecutionPolicy {
  inheritAssistantDefaults: boolean;
  modelResourceId: string | null;
  promptTemplateResourceId: string | null;
  inlinePrompt: string;
  ragEnabled: boolean;
  knowledgeBaseResourceId: string | null;
  memoryWindowSize: number;
  toolResourceIds: string[];
}

export interface AssistantReleaseResource {
  resourceId: string;
  resourceName: string;
  resourceType: string;
  resourceVersionId: string;
  resourceVersion: string;
  boundAgents: string[];
}

export interface AssistantRelease {
  id: string;
  assistantId: string;
  releaseVersion: string;
  status: VersionStatus;
  createdAt: string;
  publishedAt: string | null;
  resources: AssistantReleaseResource[];
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

export interface AssistantOrchestration {
  assistantId: string;
  assistantName: string;
  scenarioId: string;
  executionMode: string;
  nodes: OrchestrationNode[];
  edges: OrchestrationEdge[];
}

export interface OrchestrationNode {
  nodeId: string;
  nodeName: string;
  nodeType: string;
  agentId: string;
  description: string;
  resourceIds: string[];
}

export interface OrchestrationEdge {
  edgeId: string;
  fromNodeId: string;
  toNodeId: string;
  condition: string;
  handoffPolicy: string;
}

export interface ResourceUsage {
  resourceId: string;
  resourceName: string;
  type: ResourceType;
  shareScope: ShareScope;
  ownerLabel: string;
  latestVersion: string | null;
  effectiveVersion: string | null;
  boundAgents: string[];
  boundAssistants: string[];
  bindingAnchors: string[];
}

export interface ResourceCenter {
  totalResources: number;
  domainSharedResources: number;
  privateResources: number;
  usages: ResourceUsage[];
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

export interface McpInvocationSummary {
  capabilityName: string;
  externalTicketId: string;
  status: string;
  recommendedAction: string;
  detail: string;
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
  status: WorkflowStatus;
  summary: string;
  escalationRequired: boolean;
  mcpSummary: McpInvocationSummary | null;
  resourceAnchors: string[];
  nodes: NodeExecution[];
  interventions: HumanIntervention[];
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
  latestMcpSummary: McpInvocationSummary | null;
}

export interface CreateAssistantPayload {
  scenarioId: string;
  name: string;
  description: string;
  modelPolicy: AssistantModelPolicy;
  ragPolicy: RagPolicy;
  memoryPolicy: MemoryPolicy;
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
  configDigest: string;
  status: VersionStatus;
  configuration: ResourceVersionConfiguration;
}

export interface UpdateAgentPayload {
  name: string;
  role: string;
  instructions: string;
  executionPolicy: AgentExecutionPolicy;
}

export interface UpdateAgentBindingsPayload {
  bindings: Array<{
    resourceId: string;
    resourceVersionId: string;
  }>;
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
