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
export type ChannelProfileStatus = 'ACTIVE' | 'INACTIVE';
export type ChannelConversationBindingStatus = 'ACTIVE' | 'ARCHIVED';
export type ChannelInboundEventStatus = 'RECEIVED' | 'REJECTED';
export type ChannelOutboundFrameKind =
  | 'TYPING_START'
  | 'TYPING_STOP'
  | 'DRAFT_UPDATE'
  | 'DRAFT_COMPLETE'
  | 'DRAFT_DISCARD'
  | 'FINAL_DELIVERY';
export type ChannelOutboundConsumerKind = 'REMOTE_EXTENSION' | 'GATEWAY_NATIVE';
export type ChannelProviderJobScheduleType = 'INTERVAL' | 'CRON' | 'MANUAL';
export type ChannelProviderJobStatus = 'ACTIVE' | 'RUNNING' | 'PAUSED' | 'DISABLED';
export type ChannelProviderJobRunStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT';
export type ChannelRunJobResponseStatus = 'SUCCEEDED' | 'NOOP';
export type NormalizedChannelEventType =
  | 'MESSAGE_RECEIVED'
  | 'MESSAGE_UPDATED'
  | 'MESSAGE_DELETED'
  | 'CONVERSATION_UPDATED'
  | 'MEMBER_JOINED'
  | 'MEMBER_LEFT'
  | 'REACTION_ADDED'
  | 'FILE_RECEIVED'
  | 'WEBHOOK_VERIFIED'
  | 'UNKNOWN';
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

export interface ChannelProfile {
  id: string;
  providerType: string;
  displayName: string;
  status: ChannelProfileStatus;
  inboundEnabled: boolean;
  config: Record<string, unknown>;
  assistantBinding: ChannelAssistantBinding | null;
  accountId: string | null;
  hasExternalSecretRef: boolean;
  revision: number;
  integrationAccount: ChannelProfileIntegrationAccountSummary | null;
  createdAt: string;
  updatedAt: string;
}

export interface ChannelGatewayProfile {
  id: string;
  providerType: string;
  displayName: string;
  status: ChannelProfileStatus;
  inboundEnabled: boolean;
  config: Record<string, unknown>;
  assistantBinding: ChannelAssistantBinding | null;
  accountId: string | null;
  hasExternalSecretRef: boolean;
  revision: number;
  createdAt: string;
  updatedAt: string;
}

export interface ChannelAssistantBinding {
  assistantId: string | null;
  scenarioId: string | null;
}

export type IntegrationAccountAvailabilityBlock =
  | 'SUBJECT_MISMATCH'
  | 'ACCOUNT_STATUS_NOT_ENABLED'
  | 'CREDENTIAL_REVOKE_FAILED'
  | 'CREDENTIAL_REVOKED';

export type IntegrationAccountAvailabilityRisk =
  | 'CREDENTIAL_NOT_CONFIGURED'
  | 'CREDENTIAL_VALIDATION_FAILED'
  | 'CREDENTIAL_ROTATION_REQUIRED';

export interface ChannelProfileIntegrationAccountSummary {
  id: string;
  name: string;
  status: IntegrationAccountStatus;
  credentialStatus: IntegrationAccountCredentialStatus;
  credentialConfigured: boolean;
  availabilityHardBlock: IntegrationAccountAvailabilityBlock | null;
  risks: IntegrationAccountAvailabilityRisk[];
}

export interface ChannelProfileAccountSnapshot {
  accountId?: string | null;
  externalSecretRef?: string | null;
}

export interface NormalizedChannelConversation {
  externalConversationId?: string | null;
  type?: string | null;
  title?: string | null;
  metadata: Record<string, unknown>;
}

export interface NormalizedChannelSender {
  externalUserId?: string | null;
  displayName?: string | null;
  metadata: Record<string, unknown>;
}

export interface NormalizedChannelAttachment {
  externalAttachmentId?: string | null;
  externalFileId?: string | null;
  fileName?: string | null;
  mimeType?: string | null;
  url?: string | null;
  sizeBytes?: number | null;
  metadata: Record<string, unknown>;
}

export interface NormalizedChannelMessage {
  externalMessageId?: string | null;
  type?: string | null;
  text?: string | null;
  attachments: NormalizedChannelAttachment[];
  metadata: Record<string, unknown>;
}

export interface NormalizedChannelTraceContext {
  traceparent: string;
  tracestate?: string | null;
}

export interface NormalizedChannelInboundEvent {
  providerType: string;
  channelProfileId: string;
  eventType: NormalizedChannelEventType;
  dedupKey: string;
  externalEventId?: string | null;
  externalConversationId?: string | null;
  externalMessageId?: string | null;
  externalUserId?: string | null;
  occurredAt?: string | null;
  conversation?: NormalizedChannelConversation | null;
  sender?: NormalizedChannelSender | null;
  message?: NormalizedChannelMessage | null;
  normalizedPayload: Record<string, unknown>;
  rawPayload?: Record<string, unknown> | null;
  traceContext: NormalizedChannelTraceContext;
  metadata?: Record<string, unknown> | null;
}

export interface NormalizedChannelInboundEventResult {
  eventId: string;
  duplicate: boolean;
}

export interface ChannelConversationBinding {
  id: string;
  channelProfileId: string;
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
  channelProfileId: string;
  providerType: string;
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

export interface ChannelOutboundResolvedTemplate {
  messageType: string;
  messageSubtype?: string | null;
  messageVersion: string;
  externalTemplateId: string;
  externalTemplateVersion?: string | null;
}

export interface ChannelProviderOutboundCapability {
  mode: 'FRAME_STREAM';
  supportsTyping: boolean;
  supportsDraftUpdate: boolean;
  supportsFinalDelivery: true;
  supportsCredentialRef: boolean;
  requiresIdempotentFinalDelivery: true;
}

export interface ChannelOutboundTypingPayload {
  messageId: string;
}

export interface ChannelOutboundDraftUpdatePayload {
  messageId: string;
  blockId: string;
  blockType: string;
  delta?: string | null;
}

export interface ChannelOutboundDraftCompletePayload {
  messageId: string;
  blockId: string;
  blockType: string;
  block: Record<string, unknown>;
}

export interface ChannelOutboundDraftDiscardPayload {
  messageId: string;
  reason?: string | null;
}

export interface ChannelOutboundFinalDeliveryPayload {
  sessionMessageId: string;
  messageSequence: number;
  messageBlocks: Record<string, unknown>[];
  resolvedTemplate?: ChannelOutboundResolvedTemplate | null;
}

export type ChannelOutboundTransientFrame =
  | {
      protocol: 'lynxus.channel-outbound-frame.v1';
      frameId: string;
      channelProfileId: string;
      providerType: string;
      assistantId?: string | null;
      externalConversationId: string;
      sessionId: string;
      turnId: string;
      turnExecutionId: string;
      sourceSeq: number;
      kind: 'TYPING_START' | 'TYPING_STOP';
      occurredAt: string;
      idempotencyKey: string;
      credentialRef?: string | null;
      payload: ChannelOutboundTypingPayload;
      traceContext?: NormalizedChannelTraceContext | null;
    }
  | {
      protocol: 'lynxus.channel-outbound-frame.v1';
      frameId: string;
      channelProfileId: string;
      providerType: string;
      assistantId?: string | null;
      externalConversationId: string;
      sessionId: string;
      turnId: string;
      turnExecutionId: string;
      sourceSeq: number;
      kind: 'DRAFT_UPDATE';
      occurredAt: string;
      idempotencyKey: string;
      credentialRef?: string | null;
      payload: ChannelOutboundDraftUpdatePayload;
      traceContext?: NormalizedChannelTraceContext | null;
    }
  | {
      protocol: 'lynxus.channel-outbound-frame.v1';
      frameId: string;
      channelProfileId: string;
      providerType: string;
      assistantId?: string | null;
      externalConversationId: string;
      sessionId: string;
      turnId: string;
      turnExecutionId: string;
      sourceSeq: number;
      kind: 'DRAFT_COMPLETE';
      occurredAt: string;
      idempotencyKey: string;
      credentialRef?: string | null;
      payload: ChannelOutboundDraftCompletePayload;
      traceContext?: NormalizedChannelTraceContext | null;
    }
  | {
      protocol: 'lynxus.channel-outbound-frame.v1';
      frameId: string;
      channelProfileId: string;
      providerType: string;
      assistantId?: string | null;
      externalConversationId: string;
      sessionId: string;
      turnId: string;
      turnExecutionId: string;
      sourceSeq: number;
      kind: 'DRAFT_DISCARD';
      occurredAt: string;
      idempotencyKey: string;
      credentialRef?: string | null;
      payload: ChannelOutboundDraftDiscardPayload;
      traceContext?: NormalizedChannelTraceContext | null;
    };

export interface ChannelOutboundFinalDeliveryFrame {
  protocol: 'lynxus.channel-outbound-frame.v1';
  frameId: string;
  channelProfileId: string;
  providerType: string;
  assistantId?: string | null;
  externalConversationId: string;
  sessionId: string;
  finalSequence: number;
  kind: 'FINAL_DELIVERY';
  occurredAt: string;
  idempotencyKey: string;
  credentialRef?: string | null;
  payload: ChannelOutboundFinalDeliveryPayload;
  traceContext?: NormalizedChannelTraceContext | null;
}

export type ChannelOutboundFrame = ChannelOutboundTransientFrame | ChannelOutboundFinalDeliveryFrame;

export interface ChannelOutboundProfileConsumer {
  channelProfileId: string;
  providerType: string;
  consumerKind: ChannelOutboundConsumerKind;
  consumerId: string;
  registrationId?: string | null;
}

export interface ChannelOutboundFrameCheckpoint {
  consumer: ChannelOutboundProfileConsumer;
  lastAckedFinalSequence?: number | null;
  lastAckedFinalFrameId?: string | null;
  lastAckedSessionId?: string | null;
  lastAckedSessionMessageId?: string | null;
  lastAckedAt?: string | null;
}

export interface ChannelOutboundFrameStreamCursor {
  streamCursor?: string | null;
  lastAckedFinalSequence?: number | null;
  lastAckedSessionId?: string | null;
  lastAckedSessionMessageId?: string | null;
  maxFinalReplayFrames?: number | null;
}

export interface ChannelOutboundFrameAck {
  protocol: 'lynxus.channel-outbound-frame-ack.v1';
  channelProfileId: string;
  providerType: string;
  frameId: string;
  finalSequence: number;
  sessionId: string;
  sessionMessageId: string;
  metadata?: Record<string, unknown> | null;
}

export interface ChannelOutboundBindingSnapshotRefreshRequest {
  channelProfileId?: string | null;
  bindingId?: string | null;
  sessionId?: string | null;
  reason: string;
  bindingUpdatedAt?: string | null;
}

export interface ChannelTemplateBindingKey {
  channelProfileId: string;
  assistantId: string;
  messageType: string;
  messageSubtype: string;
  messageVersion: string;
}

export interface ChannelTemplateBinding {
  id: string;
  channelProfileId: string;
  assistantId: string;
  messageType: string;
  messageSubtype: string;
  messageVersion: string;
  externalTemplateId: string;
  externalTemplateVersion: string | null;
  enabled: boolean;
  variableSchema: Record<string, unknown>;
  displayName: string;
  externalEditUrl: string | null;
  revision: number;
  createdAt: string;
  updatedAt: string;
}

export interface ChannelTemplateBindingWritePayload {
  externalTemplateId: string;
  externalTemplateVersion?: string | null;
  variableSchema?: Record<string, unknown> | null;
  displayName: string;
  externalEditUrl?: string | null;
  enabled?: boolean | null;
  expectedRevision?: number | null;
}

export interface ResolvedChannelTemplate {
  externalTemplateId: string;
  externalTemplateVersion: string | null;
  variableSchema: Record<string, unknown>;
  displayName: string;
  externalEditUrl: string | null;
  bindingRevision: number;
}

export interface ChannelProviderJobScheduleConfig {
  scheduleType: ChannelProviderJobScheduleType;
  intervalSeconds: number | null;
  cronExpression: string | null;
  timezone: string;
  jobTimeoutSeconds: number;
  jobConfig: Record<string, unknown>;
}

export interface ChannelProviderJobScheduleWriteConfig {
  enabled: boolean;
  scheduleType?: ChannelProviderJobScheduleType | null;
  intervalSeconds?: number | null;
  cronExpression?: string | null;
  timezone?: string | null;
  jobTimeoutSeconds?: number | null;
  jobConfig?: Record<string, unknown> | null;
}

export interface ChannelProviderJobConfig {
  jobId: string;
  jobType: string;
  status: ChannelProviderJobStatus;
  scheduleConfig: ChannelProviderJobScheduleConfig;
  nextRunAt: string | null;
  lastRunAt: string | null;
  lastSuccessAt: string | null;
  lastError: string | null;
  failureCount: number;
  revision: number;
  createdAt: string;
  updatedAt: string;
}

export interface ChannelProviderJobConfigWritePayload {
  scheduleConfig?: ChannelProviderJobScheduleWriteConfig | null;
  expectedRevision?: number | null;
}

export interface ChannelProviderJobRun {
  id: string;
  runId: string;
  jobId: string;
  status: ChannelProviderJobRunStatus;
  scheduledAt: string;
  startedAt: string;
  jobTimeoutSeconds: number;
  finishedAt: string | null;
  durationMs: number | null;
  idempotencyKey: string;
  attempt: number;
  eventsIngested: number;
  nextCursor: string | null;
  error: Record<string, unknown>;
  metadata: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface ChannelRunJobPayload {
  jobType: string;
  jobConfig: Record<string, unknown>;
  scheduledAt?: string | null;
}

export interface ChannelRunJobRequest {
  providerType: string;
  channelProfileId: string;
  config: Record<string, unknown>;
  externalSecretRef?: string | null;
  idempotencyKey: string;
  traceContext: NormalizedChannelTraceContext;
  payload: ChannelRunJobPayload;
}

export interface ChannelRunJobResponse {
  status: ChannelRunJobResponseStatus;
  nextCursor?: string | null;
  events: NormalizedChannelInboundEvent[];
  metadata: Record<string, unknown>;
}

export interface CreateChannelProfilePayload {
  providerType: string;
  displayName: string;
  status?: ChannelProfileStatus | null;
  inboundEnabled?: boolean | null;
  config: Record<string, unknown>;
  assistantBinding?: ChannelAssistantBinding | null;
  integrationAccountId?: string | null;
}

export interface UpdateChannelProfilePayload {
  providerType: string;
  displayName: string;
  status?: ChannelProfileStatus | null;
  inboundEnabled?: boolean | null;
  config: Record<string, unknown>;
  assistantBinding?: ChannelAssistantBinding | null;
  integrationAccountId?: string | null;
  expectedRevision: number;
}

export interface CreateChannelProfileInternalPayload {
  providerType: string;
  displayName: string;
  status?: ChannelProfileStatus | null;
  inboundEnabled?: boolean | null;
  config?: Record<string, unknown> | null;
  assistantBinding?: ChannelAssistantBinding | null;
  accountSnapshot?: ChannelProfileAccountSnapshot | null;
}

export interface UpdateChannelProfileInternalPayload {
  providerType: string;
  displayName: string;
  status?: ChannelProfileStatus | null;
  inboundEnabled?: boolean | null;
  config?: Record<string, unknown> | null;
  assistantBinding?: ChannelAssistantBinding | null;
  accountSnapshot?: ChannelProfileAccountSnapshot | null;
  expectedRevision: number;
}

export type SessionRuntimeStreamEventType =
  | 'SESSION_SNAPSHOT'
  | 'SESSION_UPDATED'
  | 'SESSION_PROGRESS'
  | 'SESSION_REPLY_DRAFT'
  | 'SESSION_STREAM_ERROR';

export type StreamVisibility = 'CUSTOMER' | 'OPERATOR' | 'DEVELOPER' | 'INTERNAL';

export interface SessionRuntimeSnapshotEvent<Detail = unknown> {
  id: string;
  type: 'SESSION_SNAPSHOT' | 'SESSION_UPDATED';
  occurredAt: string;
  sessionId: string;
  detail: Detail;
}

export interface SessionProgressEvent {
  id: string;
  type: 'SESSION_PROGRESS';
  occurredAt: string;
  sessionId: string;
  turnId: string;
  visibility: Exclude<StreamVisibility, 'INTERNAL'>;
  phase: string;
  status: 'STARTED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | string;
  title: string;
  detail: Record<string, unknown>;
}

export interface SessionReplyDraftEvent {
  id: string;
  type: 'SESSION_REPLY_DRAFT';
  occurredAt: string;
  sessionId: string;
  turnId: string;
  messageId: string;
  operation: 'DELTA' | 'COMPLETED' | 'DISCARD';
  blockId: string;
  blockType: SessionMessageBlockType;
  delta: string | null;
  text: string | null;
}

export interface SessionStreamErrorEvent {
  id: string;
  type: 'SESSION_STREAM_ERROR';
  occurredAt: string;
  sessionId: string;
  turnId: string;
  code: string;
  message: string;
  retryable: boolean;
  detail: Record<string, unknown>;
}

export type SessionRuntimeStreamEvent<Detail = unknown> =
  | SessionRuntimeSnapshotEvent<Detail>
  | SessionProgressEvent
  | SessionReplyDraftEvent
  | SessionStreamErrorEvent;

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
  | 'NO_OP'
  | 'SWITCH_OWNER'
  | 'RUN_PLAYBOOK'
  | 'SESSION_HUMAN_HANDOFF'
  | 'SECURITY_BLOCK';
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
  enableThinking: boolean | null;
  reasoningEffort: string | null;
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
  hasExternalSecretRef: boolean;
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
  turnId: string;
  turnExecutionId: string;
  replyMessageId: string;
  ownershipEpoch: number;
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

export type AgentTurnStreamFrameKind =
  | 'TURN_STARTED'
  | 'MODEL_STARTED'
  | 'MODEL_COMPLETED'
  | 'ACTION_TOOL_STARTED'
  | 'ACTION_TOOL_COMPLETED'
  | 'REPLY_BLOCK_DELTA'
  | 'REPLY_BLOCK_COMPLETED'
  | 'FINAL_OUTCOME'
  | 'ERROR';

export type AgentTurnTransientFrameKind =
  | 'TURN_STARTED'
  | 'MODEL_STARTED'
  | 'MODEL_COMPLETED'
  | 'ACTION_TOOL_STARTED'
  | 'ACTION_TOOL_COMPLETED'
  | 'REPLY_BLOCK_DELTA'
  | 'REPLY_BLOCK_COMPLETED'
  | 'TURN_COMPLETED'
  | 'ERROR';

export type ModelStreamStatus = 'SUCCEEDED' | 'FAILED' | 'ABORTED';
export type TurnCompletionStatus = 'SUCCEEDED' | 'FAILED';
export type RuntimeToolKind = 'CONTEXT_TOOL' | 'STATE_TOOL' | 'MESSAGE_BLOCK_TOOL' | 'LIFECYCLE_ACTION_TOOL';
export type ToolCompletionStatus = 'ACCEPTED' | 'REJECTED' | 'FAILED';
export type StreamErrorStage =
  | 'PROVIDER_STREAM'
  | 'TOOL_ARGUMENT_PARSE'
  | 'TOOL_EXECUTION'
  | 'FINAL_OUTCOME_BUILD'
  | 'TRANSCRIPT_PERSISTENCE';

export interface AgentTurnStreamFrame<K extends AgentTurnStreamFrameKind, P> {
  protocol: 'lynxus.agent-turn-stream.v1';
  frameId: string;
  streamId: string;
  sessionId: string;
  turnId: string;
  turnExecutionId: string;
  ownerAgentId: string;
  ownershipEpoch: number;
  seq: number;
  kind: K;
  visibility: StreamVisibility;
  occurredAt: string;
  payload: P;
}

export interface AgentTurnTransientFrame<K extends AgentTurnTransientFrameKind, P> {
  protocol: 'lynxus.agent-turn-transient.v1';
  frameId: string;
  streamId: string;
  sessionId: string;
  turnId: string;
  turnExecutionId: string;
  ownerAgentId: string;
  ownershipEpoch: number;
  seq: number;
  kind: K;
  visibility: StreamVisibility;
  occurredAt: string;
  payload: P;
}

export interface TurnStartedPayload {
  messageId: string;
  triggerType: SessionTriggerType;
}

export interface ModelStartedPayload {
  modelRoundId: string;
}

export interface ModelCompletedPayload {
  modelRoundId: string;
  status: ModelStreamStatus;
}

export interface ToolStartedPayload {
  modelRoundId: string;
  toolCallId: string;
  toolName: string;
  toolKind: RuntimeToolKind;
}

export interface ToolCompletedPayload {
  toolCallId: string;
  toolName: string;
  toolKind: RuntimeToolKind;
  status: ToolCompletionStatus;
  produced?: {
    action?: string;
    messageBlockId?: string;
    sharedStateUpdated?: boolean;
  };
}

export interface ReplyBlockDeltaPayload {
  messageId: string;
  blockId: string;
  blockType: 'TEXT';
  delta: string;
}

export interface ReplyBlockCompletedPayload {
  messageId: string;
  blockId: string;
  block: SessionMessageBlock;
}

export interface FinalOutcomePayload {
  messageId: string;
  outcome: AgentTurnExecutionOutcome;
}

export interface TurnCompletedPayload {
  messageId: string;
  status: TurnCompletionStatus;
}

export interface ErrorPayload {
  code: string;
  messageId: string;
  message: string;
  stage: StreamErrorStage;
  retryable: boolean;
  details?: Record<string, unknown>;
}

export type AgentTurnFrame =
  | AgentTurnStreamFrame<'TURN_STARTED', TurnStartedPayload>
  | AgentTurnStreamFrame<'MODEL_STARTED', ModelStartedPayload>
  | AgentTurnStreamFrame<'MODEL_COMPLETED', ModelCompletedPayload>
  | AgentTurnStreamFrame<'ACTION_TOOL_STARTED', ToolStartedPayload>
  | AgentTurnStreamFrame<'ACTION_TOOL_COMPLETED', ToolCompletedPayload>
  | AgentTurnStreamFrame<'REPLY_BLOCK_DELTA', ReplyBlockDeltaPayload>
  | AgentTurnStreamFrame<'REPLY_BLOCK_COMPLETED', ReplyBlockCompletedPayload>
  | AgentTurnStreamFrame<'FINAL_OUTCOME', FinalOutcomePayload>
  | AgentTurnStreamFrame<'ERROR', ErrorPayload>;

export type AgentTurnTransientIngressFrame =
  | AgentTurnTransientFrame<'TURN_STARTED', TurnStartedPayload>
  | AgentTurnTransientFrame<'MODEL_STARTED', ModelStartedPayload>
  | AgentTurnTransientFrame<'MODEL_COMPLETED', ModelCompletedPayload>
  | AgentTurnTransientFrame<'ACTION_TOOL_STARTED', ToolStartedPayload>
  | AgentTurnTransientFrame<'ACTION_TOOL_COMPLETED', ToolCompletedPayload>
  | AgentTurnTransientFrame<'REPLY_BLOCK_DELTA', ReplyBlockDeltaPayload>
  | AgentTurnTransientFrame<'REPLY_BLOCK_COMPLETED', ReplyBlockCompletedPayload>
  | AgentTurnTransientFrame<'TURN_COMPLETED', TurnCompletedPayload>
  | AgentTurnTransientFrame<'ERROR', ErrorPayload>;

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
