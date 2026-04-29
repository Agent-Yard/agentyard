export type ResourceType = 'TOOL' | 'LLM_MODEL' | 'SKILL';
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
export type ToolKind = 'RESOURCE' | 'BUILTIN';
export type IntegrationAccountSubjectType = 'TOOL_CONNECTOR' | 'CHANNEL_PROVIDER';
export type IntegrationAccountStatus = 'ENABLED' | 'DISABLED' | 'ARCHIVED';
export type IntegrationAccountCredentialStatus =
  | 'NOT_CONFIGURED'
  | 'ACTIVE'
  | 'VALIDATION_FAILED'
  | 'ROTATION_REQUIRED'
  | 'REVOKE_FAILED'
  | 'REVOKED';
export type ChannelProviderType = 'FEISHU';
export type ChannelAccountStatus = 'ACTIVE' | 'INACTIVE';
export type ChannelConversationBindingStatus = 'ACTIVE' | 'ARCHIVED';
export type ChannelInboundEventStatus = 'RECEIVED' | 'REJECTED';
export type ChannelOutboundDeliveryStatus = 'PENDING' | 'SENT' | 'FAILED';
export type CredentialCapabilityMode = 'REMOTE_LIFECYCLE' | 'CORE_ENCRYPTED_REFERENCE';

export interface CredentialCapability {
  supported: boolean;
  mode: CredentialCapabilityMode | null;
  credentialSchema: Record<string, unknown> | null;
  credentialUiSchema: Record<string, unknown>[];
}

export interface ChannelProviderJobDefinition {
  jobType: string;
  title: string;
  description: string | null;
  jobConfigSchema: Record<string, unknown>;
  jobConfigUiSchema: Record<string, unknown>[];
  defaultSchedule: Record<string, unknown> | null;
  defaultEnabled: boolean | null;
  defaultJobTimeoutSeconds: number | null;
}

export interface ChannelProviderDefinition {
  providerType: string;
  title: string;
  description: string | null;
  definitionDigest: string;
  accountConfigSchema: Record<string, unknown>;
  accountConfigUiSchema: Record<string, unknown>[];
  credentialCapability: CredentialCapability;
  configSchema: Record<string, unknown>;
  configUiSchema: Record<string, unknown>[];
  defaultConfig: Record<string, unknown>;
  jobDefinitions: ChannelProviderJobDefinition[];
}

export interface ToolConnectorDefinition {
  connectorType: string;
  title: string;
  description: string | null;
  definitionDigest: string;
  accountConfigSchema: Record<string, unknown>;
  accountConfigUiSchema: Record<string, unknown>[];
  credentialCapability: CredentialCapability;
  configSchema: Record<string, unknown>;
  configUiSchema: Record<string, unknown>[];
  operationMappingSchema: Record<string, unknown>;
  operationMappingUiSchema: Record<string, unknown>[];
}

export interface ToolOutcomeSummary {
  callId: string;
  toolId: string;
  toolName: string;
  toolKind: ToolKind;
  operation: string;
  connectorType: string;
  resourceId: string | null;
  resourceName: string | null;
  result: Record<string, unknown>;
}

export interface IntegrationAccount {
  id: string;
  subjectType: IntegrationAccountSubjectType;
  subjectId: string;
  name: string;
  status: IntegrationAccountStatus;
  config: Record<string, unknown>;
  hasExternalSecretRef: boolean;
  credentialConfigured: boolean;
  credentialStatus: IntegrationAccountCredentialStatus;
  createdAt: string;
  updatedAt: string;
}

export interface CreateIntegrationAccountPayload {
  subjectType: IntegrationAccountSubjectType;
  subjectId: string;
  name: string;
  status?: IntegrationAccountStatus | null;
  config?: Record<string, unknown> | null;
  credential?: Record<string, unknown> | null;
}

export interface UpdateIntegrationAccountPayload {
  name?: string | null;
  status?: IntegrationAccountStatus | null;
  config?: Record<string, unknown> | null;
}

export interface UpdateIntegrationAccountStatusPayload {
  status: IntegrationAccountStatus;
}

export interface CreateIntegrationAccountCredentialPayload {
  credential: Record<string, unknown>;
}

export interface RotateIntegrationAccountCredentialPayload {
  credential: Record<string, unknown>;
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

export interface ChannelAccount {
  id: string;
  providerType: ChannelProviderType;
  name: string;
  status: ChannelAccountStatus;
  config: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface ChannelConversationBinding {
  id: string;
  channelAccountId: string;
  externalConversationId: string;
  externalUserId: string | null;
  assistantId: string | null;
  customerId: string | null;
  sessionId: string | null;
  status: ChannelConversationBindingStatus;
  metadata: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface ChannelInboundEvent {
  eventId: string;
  channelAccountId: string;
  providerType: ChannelProviderType;
  eventType: string;
  externalEventId: string | null;
  externalConversationId: string | null;
  externalMessageId: string | null;
  dedupKey: string;
  rawPayload: Record<string, unknown>;
  normalizedPayload: Record<string, unknown>;
  status: ChannelInboundEventStatus;
  createdAt: string;
  updatedAt: string;
}

export interface ChannelOutboundDelivery {
  deliveryId: string;
  channelAccountId: string;
  providerType: ChannelProviderType;
  sessionId: string | null;
  sessionMessageId: string | null;
  externalConversationId: string | null;
  payload: Record<string, unknown>;
  status: ChannelOutboundDeliveryStatus;
  attemptCount: number;
  lastError: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateChannelAccountPayload {
  providerType: ChannelProviderType;
  name: string;
  status?: ChannelAccountStatus | null;
  config: Record<string, unknown>;
}

export interface UpdateChannelAccountPayload {
  name: string;
  status?: ChannelAccountStatus | null;
  config: Record<string, unknown>;
}

export interface SessionRuntimeStreamEvent<Detail = unknown> {
  id: string;
  type: 'SESSION_SNAPSHOT' | 'SESSION_UPDATED';
  occurredAt: string;
  sessionId: string;
  detail: Detail;
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
export type SessionMessageRole = 'USER' | 'ASSISTANT' | 'HUMAN_OPERATOR' | 'SYSTEM';
export type SessionMessageStatus = 'SENT' | 'STREAMING' | 'DELIVERED' | 'FAILED';
export type SessionMessageSenderType = 'CUSTOMER' | 'AGENT' | 'HUMAN_OPERATOR' | 'SYSTEM';
export type SessionMessageBlockType = 'TEXT' | 'IMAGE' | 'RICH_TEXT' | 'CARD';
export type RichTextFormat = 'MARKDOWN';
export type CardActionType = 'LINK';
export type AgentDecisionAction =
  | 'REPLY'
  | 'NO_REPLY'
  | 'SWITCH_OWNER'
  | 'RUN_PLAYBOOK'
  | 'SESSION_HUMAN_HANDOFF';
export type SessionTriggerType = 'USER_MESSAGE' | 'PLAYBOOK_COMPLETED';
export type SessionActorType = 'CUSTOMER' | 'AGENT' | 'SYSTEM' | 'HUMAN_OPERATOR' | 'EXTERNAL_SYSTEM';
export type SessionEventType =
  | 'AGENT_DECISION_REJECTED'
  | 'AGENT_TURN_FAILED'
  | 'USER_MESSAGE_SECURITY_BLOCKED'
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
  privateDeployment: boolean;
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

export type ToolConnectorRetryMode = 'NONE' | 'FIXED' | 'EXPONENTIAL';

export interface ToolConnectorRuntimeRetryPolicy {
  mode: ToolConnectorRetryMode;
  maxAttempts: number;
  initialDelayMs: number;
  maxDelayMs: number;
  backoffMultiplier: number;
  retryableCategories: string[];
  retryableErrorCodes: string[];
}

export interface ToolConnectorDescriptor {
  connectorType: string;
  accountSnapshot: ToolConnectorAccountSnapshot | null;
  timeoutSeconds: number;
  retryPolicy: ToolConnectorRuntimeRetryPolicy;
  config: Record<string, unknown>;
  operationMappings: Record<string, Record<string, unknown>>;
}

export interface ToolConnectorAccountSnapshot {
  accountId: string;
  externalSecretRef: string | null;
}

export interface ToolDescriptor {
  resourceId: string;
  resourceName: string;
  resourceVersionId: string;
  resourceVersion: string;
  operations: ToolOperationDescriptor[];
  connector: ToolConnectorDescriptor | null;
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
  effectivePrivacyModelBinding: LlmModelDescriptor | null;
  effectivePrivacyMappingEnabled: boolean;
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
  layout: PlaybookNodeLayout;
}

export interface PlaybookNodeLayout {
  x: number;
  y: number;
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

export interface SessionMessageSender {
  senderType: SessionMessageSenderType;
  senderId: string | null;
  senderName: string | null;
}

export interface TextMessageBlock {
  type: 'TEXT';
  text: string;
}

export interface ImageMessageBlock {
  type: 'IMAGE';
  url: string;
  mimeType: string | null;
  width: number | null;
  height: number | null;
  alt: string | null;
}

export interface RichTextMessageBlock {
  type: 'RICH_TEXT';
  format: RichTextFormat;
  content: string;
}

export interface CardLinkAction {
  actionType: CardActionType;
  label: string;
  url: string;
}

export interface CardMessageBlock {
  type: 'CARD';
  cardType: string;
  version: string;
  data: Record<string, unknown>;
  actions: CardLinkAction[];
}

export type SessionMessageBlock =
  | TextMessageBlock
  | ImageMessageBlock
  | RichTextMessageBlock
  | CardMessageBlock;

export interface SessionMessageInput {
  blocks: SessionMessageBlock[];
  metadata: Record<string, unknown>;
}

export interface SessionMessage {
  messageId: string;
  sessionId: string;
  sequence: number;
  role: SessionMessageRole;
  sender: SessionMessageSender;
  status: SessionMessageStatus;
  blocks: SessionMessageBlock[];
  metadata: Record<string, unknown>;
  relatedPlaybookRunId: string | null;
  relatedOwnerAgentId: string | null;
  sourceEventId: string | null;
  createdAt: string;
  updatedAt: string;
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
  relatedMessageId: string | null;
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
  triggerMessageId: string | null;
  payload: Record<string, unknown>;
}

export type PrivacyChannel =
  | 'PROMPT_INSTRUCTION'
  | 'PROMPT_RUNTIME_MESSAGE'
  | 'SKILL_PAYLOAD'
  | 'MODEL_TOOL_ARGUMENT'
  | 'TOOL_RESULT'
  | 'MODEL_FINAL_RESPONSE';

export interface PrivacyMappingTelemetry {
  enabled: boolean;
  privacyModelResourceId: string | null;
  privacyModelResourceName: string | null;
  sanitizeCountByChannel: Record<PrivacyChannel, number>;
  restoreCountByChannel: Record<PrivacyChannel, number>;
  entityTypeBreakdown: Record<string, number>;
  placeholderCount: number;
  unresolvedPlaceholderCount: number;
  blockedEventCount: number;
  lastProcessedAt: string | null;
}

export interface AgentDecision {
  action: AgentDecisionAction;
  replyMessage: SessionMessageInput | null;
  targetAgentId: string | null;
  playbookId: string | null;
  playbookInput: Record<string, unknown>;
  accompanyingMessage: SessionMessageInput | null;
}

export type LlmUsageSourceType = 'SESSION_OWNER_MODEL' | 'SESSION_PRIVACY_MODEL';

export interface LlmUsageEntry {
  sourceType: LlmUsageSourceType;
  callSequence: number;
  toolLoopStep: number;
  providerType: string;
  modelResourceId: string | null;
  modelResourceVersionId: string | null;
  modelId: string;
  usageAvailable: boolean;
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  rawUsage: Record<string, unknown>;
  occurredAt: string;
}

export interface AgentTurnRequest {
  sessionId: string;
  assistantId: string;
  assistantReleaseVersion: string;
  currentOwner: OwnerAgentConfig;
  availableAgents: OwnerAgentConfig[];
  availablePlaybooks: PlaybookConfig[];
  activePlaybook: ActivePlaybookSummary | null;
  sharedState: Record<string, unknown>;
  effectivePrivacyModelBinding: LlmModelDescriptor | null;
  effectivePrivacyMappingEnabled: boolean;
  trigger: SessionTrigger;
  recentMessages: SessionMessage[];
  recentEvents: SessionEvent[];
}

export interface AgentTurnResult {
  decision: AgentDecision;
  sharedState: Record<string, unknown>;
  mappingTelemetry: PrivacyMappingTelemetry | null;
  securityAssessment: SecurityAssessment | null;
}

export interface SecurityAssessment {
  action: 'ALLOW' | 'BLOCK' | string;
  categories: string[];
  reason: string | null;
  confidence: number | null;
}

export interface AgentTurnExecutionOutcome {
  success: boolean;
  result: AgentTurnResult | null;
  failureReason: string | null;
  llmUsage: LlmUsageEntry[];
}

export interface SessionUserMessageUpdateResult {
  status: SessionMessageDeliveryStatus;
  sessionId: string;
  reason: string | null;
}

export interface UserMessage {
  messageId: string;
  customerId: string;
  message: SessionMessageInput;
}

export interface SessionStartRequest {
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
  operatorId: string;
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
  message: SessionMessageInput;
  payload: Record<string, unknown>;
}

export interface EndHumanHandoffSignal {
  sessionId: string;
  operatorId: string;
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
  operatorId: string;
  payload: Record<string, unknown>;
}
