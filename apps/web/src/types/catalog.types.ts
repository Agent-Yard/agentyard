export type Role = 'PLATFORM_ADMIN' | 'DOMAIN_ADMIN' | 'DEVELOPER' | 'BUSINESS_USER';
export type ResourceType = 'TOOL' | 'LLM_MODEL' | 'SKILL';
export type ToolProviderType = 'HTTP' | 'MCP';
export type ShareScope = 'PRIVATE' | 'DOMAIN_SHARED';
export type ResourceOwnerType = 'DOMAIN' | 'ASSISTANT';
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
  | 'PLAYBOOK_RUN'
  | 'SESSION_PRIVACY_MAPPING';
export type ReferenceRelationMode = 'DIRECT' | 'INDIRECT';
export type ReferenceImpactLevel = 'BLOCKS_DELETION' | 'ADVISORY';
export type KnowledgeFileStatus = 'UPLOADED' | 'IMPORTING' | 'IMPORTED' | 'FAILED';
export type KnowledgeImportJobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
export type KnowledgeImportSourceType = 'FILE_UPLOAD' | 'URL';
export type KnowledgeIndexSnapshotStatus = 'QUEUED' | 'RUNNING' | 'READY' | 'FAILED';
export type KnowledgeImportJobStage = 'QUEUED' | 'FETCHING_SOURCE' | 'PARSING' | 'CHUNKING' | 'PERSISTING' | 'SUCCEEDED' | 'FAILED';
export type KnowledgeIndexSnapshotStage = 'QUEUED' | 'COLLECTING_DOCUMENTS' | 'INDEXING' | 'READY' | 'FAILED';
export type AgentDecisionAction =
  | 'REPLY'
  | 'NO_REPLY'
  | 'SWITCH_OWNER'
  | 'RUN_PLAYBOOK'
  | 'SESSION_HUMAN_HANDOFF';

export interface UserSession {
  userId: string;
  displayName: string;
  currentRole: Role;
  availableRoles: Role[];
}

export interface LogoutResponse {
  postLogoutRedirectUrl: string;
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

export interface KnowledgeDocumentDeletionBlocker {
  snapshotId: string;
  status: string;
  stage: string;
  retrievalMode: string;
  reason: string;
}

export interface KnowledgeDocumentDeletionPreview {
  documentId: string;
  knowledgeBaseId: string;
  fileId: string;
  fileName: string;
  sourceUri: string;
  title: string;
  chunkCount: number;
  canDelete: boolean;
  blockers: KnowledgeDocumentDeletionBlocker[];
}

export interface KnowledgeDocumentDeletionResult {
  documentId: string;
  knowledgeBaseId: string;
  fileId: string;
  fileName: string;
  title: string;
  deletedChunkCount: number;
  deletedImportJobCount: number;
  deletedDocumentCount: number;
  deletedStorageObject: boolean;
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

export type KnowledgeIndexSnapshot = KnowledgeSnapshot;

export interface KnowledgeUploadCompletion {
  file: KnowledgeFile;
  importJob: KnowledgeImportJob;
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
  temperature: number;
  maxTokens: number;
  privateDeployment: boolean;
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
  defaultModelResourceId: string | null;
}

export interface AssistantOwnerPolicy {
  maxOwnerSwitchesPerTurn: number;
}

export interface AssistantSessionPolicy {
  idleTimeout: string;
  maxWorkflowAge: string;
  maxWorkflowHistoryEvents: number;
}

export interface AssistantReplyPolicy {
  ownerOnly: boolean;
}

export interface AssistantPlaybookPolicy {
  timeoutPolicy: string | null;
  retryPolicy: string | null;
}

export interface KnowledgeAccessPolicy {
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
  privacyModelResourceId: string | null;
  privacyMappingEnabled: boolean | null;
  systemPrompt: string;
  knowledgeEnabled: boolean;
  inheritAssistantKnowledge: boolean;
  knowledgeBaseId: string | null;
  memoryWindowSize: number;
  skillResourceIds: string[];
  toolResourceIds: string[];
}

export interface PlaybookExecutionPolicy {
  timeoutPolicy: string | null;
  retryPolicy: string | null;
}

export type PlaybookNodeType = 'STEP' | 'TOOL_TASK' | 'HUMAN_TASK' | 'EXTERNAL_INTERACTION' | 'END';

export interface PlaybookNode {
  nodeKey: string;
  nodeName: string;
  nodeType: PlaybookNodeType;
  description: string | null;
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
  routeKey: string | null;
  label: string | null;
  defaultEdge: boolean;
}

export interface Playbook {
  id: string;
  assistantId: string;
  name: string;
  description: string | null;
  inputSchema: string | null;
  resultSchema: string | null;
  executionPolicy: PlaybookExecutionPolicy;
  allowHumanTask: boolean;
  allowExternalInteraction: boolean;
  entryNodeKey: string;
  nodes: PlaybookNode[];
  edges: PlaybookEdge[];
}

export interface Agent {
  id: string;
  assistantId: string;
  name: string;
  role: string;
  responsibility: string;
  executionPolicy: AgentExecutionPolicy;
  canOwnSession: boolean;
  allowedActions: AgentDecisionAction[];
  switchableOwnerAgentIds: string[];
  playbookIds: string[];
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

export interface DefaultModelBinding {
  resourceId: string;
  resourceName: string;
  resourceVersionId: string;
  resourceVersion: string;
  providerType: string | null;
  modelId: string | null;
}

export interface AssistantReleaseAgent {
  agentId: string;
  name: string;
  role: string;
  responsibility: string;
  executionPolicy: AgentExecutionPolicy;
  knowledgeBinding: KnowledgeBindingSnapshot | null;
  effectivePrivacyModelBinding: DefaultModelBinding | null;
  effectivePrivacyMappingEnabled: boolean;
  canOwnSession: boolean;
  allowedActions: AgentDecisionAction[];
  switchableOwnerAgentIds: string[];
  playbookIds: string[];
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
  assistantKnowledgeBinding: KnowledgeBindingSnapshot | null;
  defaultModelBinding: DefaultModelBinding | null;
  privacyModelBinding: DefaultModelBinding | null;
  privacyMappingEnabled: boolean;
  resources: AssistantReleaseResource[];
  agents: AssistantReleaseAgent[];
  playbooks: Playbook[];
  primaryAgentId: string | null;
  ownerPolicy: AssistantOwnerPolicy;
  sessionPolicy: AssistantSessionPolicy;
  replyPolicy: AssistantReplyPolicy;
  playbookPolicy: AssistantPlaybookPolicy;
  modelPolicy: AssistantModelPolicy;
  knowledgeAccessPolicy: KnowledgeAccessPolicy;
  memoryPolicy: MemoryPolicy;
}

export interface Assistant {
  id: string;
  scenarioId: string;
  name: string;
  description: string;
  version: Version;
  agents: Agent[];
  playbooks: Playbook[];
  currentRelease: AssistantRelease | null;
  releases: AssistantRelease[];
  primaryAgentId: string | null;
  ownerPolicy: AssistantOwnerPolicy;
  sessionPolicy: AssistantSessionPolicy;
  replyPolicy: AssistantReplyPolicy;
  playbookPolicy: AssistantPlaybookPolicy;
  modelPolicy: AssistantModelPolicy;
  privacyModelResourceId: string | null;
  privacyMappingEnabled: boolean;
  knowledgeAccessPolicy: KnowledgeAccessPolicy;
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
  resourceCenter: ResourceCenter;
  resourceBlueprints: ResourceBlueprint[];
}

export interface CreateAssistantPayload {
  scenarioId: string;
  name: string;
  description: string;
  primaryAgentId: string | null;
  ownerPolicy: AssistantOwnerPolicy;
  sessionPolicy: AssistantSessionPolicy;
  replyPolicy: AssistantReplyPolicy;
  playbookPolicy: AssistantPlaybookPolicy;
  modelPolicy: AssistantModelPolicy;
  privacyModelResourceId: string | null;
  privacyMappingEnabled: boolean;
  knowledgeAccessPolicy: KnowledgeAccessPolicy;
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
  primaryAgentId: string | null;
  ownerPolicy: AssistantOwnerPolicy;
  sessionPolicy: AssistantSessionPolicy;
  replyPolicy: AssistantReplyPolicy;
  playbookPolicy: AssistantPlaybookPolicy;
  modelPolicy: AssistantModelPolicy;
  privacyModelResourceId: string | null;
  privacyMappingEnabled: boolean;
  knowledgeAccessPolicy: KnowledgeAccessPolicy;
  memoryPolicy: MemoryPolicy;
}

export interface CreateAgentPayload {
  assistantId: string;
  name: string;
  role: string;
  responsibility: string;
  executionPolicy: AgentExecutionPolicy;
  canOwnSession: boolean;
  allowedActions: AgentDecisionAction[];
  switchableOwnerAgentIds: string[];
  playbookIds: string[];
}

export interface CreatePlaybookPayload {
  assistantId: string;
  name: string;
  description: string | null;
  inputSchema: string | null;
  resultSchema: string | null;
  executionPolicy: PlaybookExecutionPolicy;
  allowHumanTask: boolean;
  allowExternalInteraction: boolean;
  entryNodeKey: string;
  nodes: PlaybookNode[];
  edges: PlaybookEdge[];
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
  canOwnSession: boolean;
  allowedActions: AgentDecisionAction[];
  switchableOwnerAgentIds: string[];
  playbookIds: string[];
}

export interface UpdatePlaybookPayload {
  name: string;
  description: string | null;
  inputSchema: string | null;
  resultSchema: string | null;
  executionPolicy: PlaybookExecutionPolicy;
  allowHumanTask: boolean;
  allowExternalInteraction: boolean;
  entryNodeKey: string;
  nodes: PlaybookNode[];
  edges: PlaybookEdge[];
}

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
