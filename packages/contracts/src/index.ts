import type { PlaybookExecutionPolicy, PlaybookNode, PlaybookEdge } from './catalog';
export * from './catalog';
export * from './task';
export * from './agent';

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
export type ChannelInboundTurnStatus =
  | 'RECEIVED'
  | 'DUPLICATE'
  | 'DISPATCHED'
  | 'PARTIALLY_DISPATCHED'
  | 'REJECTED'
  | 'FAILED';
export type ChannelInboundTurnMessageStatus =
  | 'RECEIVED'
  | 'DUPLICATE'
  | 'ACCEPTED'
  | 'REJECTED';
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
  | 'MESSAGE_UPDATED'
  | 'MESSAGE_DELETED'
  | 'CONVERSATION_UPDATED'
  | 'MEMBER_JOINED'
  | 'MEMBER_LEFT'
  | 'REACTION_ADDED'
  | 'WEBHOOK_VERIFIED'
  | 'UNKNOWN';
export type NormalizedChannelMessageRole = 'USER' | 'ASSISTANT' | 'HUMAN_OPERATOR' | 'SYSTEM';
export type NormalizedChannelSenderType = 'CUSTOMER' | 'AGENT' | 'HUMAN_OPERATOR' | 'SYSTEM';
export type CredentialCapabilityMode = 'REMOTE_LIFECYCLE' | 'CORE_ENCRYPTED_REFERENCE';

export interface CredentialCapability {
  supported: boolean;
  mode: CredentialCapabilityMode | null;
  credentialSchema: Record<string, unknown> | null;
  credentialUiSchema: Record<string, unknown>[];
  supportsValidate: boolean;
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

export interface ChannelProfile {
  id: string;
  providerType: string;
  displayName: string;
  status: ChannelProfileStatus;
  inboundEnabled: boolean;
  config: Record<string, unknown>;
  entryBinding: ChannelEntryBinding | null;
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
  entryBinding: ChannelEntryBinding | null;
  accountId: string | null;
  hasExternalSecretRef: boolean;
  revision: number;
  createdAt: string;
  updatedAt: string;
}

export interface ChannelEntryBinding {
  entryId: string | null;
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

export interface NormalizedChannelMessageSender {
  senderType: NormalizedChannelSenderType;
  senderId?: string | null;
  senderName: string;
  metadata: Record<string, unknown>;
}

export interface NormalizedChannelTurnMessage {
  externalEventId?: string | null;
  externalMessageId: string;
  occurredAt?: string | null;
  role: NormalizedChannelMessageRole;
  sender?: NormalizedChannelMessageSender | null;
  type?: string | null;
  text?: string | null;
  attachments: NormalizedChannelAttachment[];
  metadata: Record<string, unknown>;
}

export interface NormalizedChannelInboundTurn {
  providerType: string;
  channelProfileId: string;
  dedupKey: string;
  externalConversationId: string;
  externalUserId?: string | null;
  conversation: NormalizedChannelConversation;
  sender?: NormalizedChannelMessageSender | null;
  messages: NormalizedChannelTurnMessage[];
  normalizedPayload: Record<string, unknown>;
  rawPayload?: Record<string, unknown> | null;
  traceContext: NormalizedChannelTraceContext;
  metadata?: Record<string, unknown> | null;
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

export interface NormalizedChannelInboundTurnResult {
  turnId: string;
  sessionId: string | null;
  status: ChannelInboundTurnStatus;
  duplicate: boolean;
  acceptedMessageIds: string[];
  duplicateExternalMessageIds: string[];
  reason: string | null;
}

export interface ChannelConversationBinding {
  id: string;
  channelProfileId: string;
  externalConversationId: string;
  externalUserId: string | null;
  entryId: string | null;
  participantId: string | null;
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
  draftMessageId?: string | null;
  senderParticipantId?: string | null;
  sessionMessageId: string;
  messageSequence: number;
  messageBlocks: Record<string, unknown>[];
  resolvedTemplate?: ChannelOutboundResolvedTemplate | null;
}

export type ChannelOutboundTransientFrame =
  | {
      protocol: 'agentyard.channel-outbound-frame.v1';
      frameId: string;
      channelProfileId: string;
      providerType: string;
      entryId?: string | null;
      externalConversationId: string;
      sessionId: string;
      sourceMessageId: string;
      activationId: string;
      sourceSeq: number;
      kind: 'TYPING_START' | 'TYPING_STOP';
      occurredAt: string;
      idempotencyKey: string;
      payload: ChannelOutboundTypingPayload;
      traceContext?: NormalizedChannelTraceContext | null;
    }
  | {
      protocol: 'agentyard.channel-outbound-frame.v1';
      frameId: string;
      channelProfileId: string;
      providerType: string;
      entryId?: string | null;
      externalConversationId: string;
      sessionId: string;
      sourceMessageId: string;
      activationId: string;
      sourceSeq: number;
      kind: 'DRAFT_UPDATE';
      occurredAt: string;
      idempotencyKey: string;
      payload: ChannelOutboundDraftUpdatePayload;
      traceContext?: NormalizedChannelTraceContext | null;
    }
  | {
      protocol: 'agentyard.channel-outbound-frame.v1';
      frameId: string;
      channelProfileId: string;
      providerType: string;
      entryId?: string | null;
      externalConversationId: string;
      sessionId: string;
      sourceMessageId: string;
      activationId: string;
      sourceSeq: number;
      kind: 'DRAFT_COMPLETE';
      occurredAt: string;
      idempotencyKey: string;
      payload: ChannelOutboundDraftCompletePayload;
      traceContext?: NormalizedChannelTraceContext | null;
    }
  | {
      protocol: 'agentyard.channel-outbound-frame.v1';
      frameId: string;
      channelProfileId: string;
      providerType: string;
      entryId?: string | null;
      externalConversationId: string;
      sessionId: string;
      sourceMessageId: string;
      activationId: string;
      sourceSeq: number;
      kind: 'DRAFT_DISCARD';
      occurredAt: string;
      idempotencyKey: string;
      payload: ChannelOutboundDraftDiscardPayload;
      traceContext?: NormalizedChannelTraceContext | null;
    };

export interface ChannelOutboundFinalDeliveryFrame {
  protocol: 'agentyard.channel-outbound-frame.v1';
  frameId: string;
  channelProfileId: string;
  providerType: string;
  entryId?: string | null;
  externalConversationId: string;
  sessionId: string;
  finalSequence: number;
  kind: 'FINAL_DELIVERY';
  occurredAt: string;
  idempotencyKey: string;
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
  protocol: 'agentyard.channel-outbound-frame-ack.v1';
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
  entryId: string;
  messageType: string;
  messageSubtype: string;
  messageVersion: string;
}

export interface ChannelTemplateBinding {
  id: string;
  channelProfileId: string;
  entryId: string;
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
  inboundTurns: NormalizedChannelInboundTurn[];
  events: NormalizedChannelInboundEvent[];
  metadata: Record<string, unknown>;
}

export interface CreateChannelProfilePayload {
  providerType: string;
  displayName: string;
  status?: ChannelProfileStatus | null;
  inboundEnabled?: boolean | null;
  config: Record<string, unknown>;
  entryBinding?: ChannelEntryBinding | null;
  integrationAccountId?: string | null;
}

export interface UpdateChannelProfilePayload {
  providerType: string;
  displayName: string;
  status?: ChannelProfileStatus | null;
  inboundEnabled?: boolean | null;
  config: Record<string, unknown>;
  entryBinding?: ChannelEntryBinding | null;
  integrationAccountId?: string | null;
  expectedRevision: number;
}

export interface CreateChannelProfileInternalPayload {
  providerType: string;
  displayName: string;
  status?: ChannelProfileStatus | null;
  inboundEnabled?: boolean | null;
  config?: Record<string, unknown> | null;
  entryBinding?: ChannelEntryBinding | null;
  accountSnapshot?: ChannelProfileAccountSnapshot | null;
}

export interface UpdateChannelProfileInternalPayload {
  providerType: string;
  displayName: string;
  status?: ChannelProfileStatus | null;
  inboundEnabled?: boolean | null;
  config?: Record<string, unknown> | null;
  entryBinding?: ChannelEntryBinding | null;
  accountSnapshot?: ChannelProfileAccountSnapshot | null;
  expectedRevision: number;
}

export type RichTextFormat = 'MARKDOWN';
export type CardActionType = 'LINK';
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

export type MessageBlock =
  | TextMessageBlock
  | ImageMessageBlock
  | RichTextMessageBlock
  | CardMessageBlock;

export interface MessageContent {
  blocks: MessageBlock[];
  metadata: Record<string, unknown>;
}


export interface ChannelReceptionMessage { externalMessageId:string; externalUserId:string; displayName:string; message:MessageContent }
export interface ChannelReceptionRequest { channelProfileId:string; externalConversationId:string; dedupKey:string; entryId:string; messages:ChannelReceptionMessage[]; metadata:Record<string,unknown> }
export interface ChannelReceptionAllocation { requestIndex:number; messageId:string; sessionId:string; participantId:string }
export interface ChannelReceptionResponse { sessionId:string; acceptedMessageAllocations:ChannelReceptionAllocation[]; duplicateExternalMessageIds:string[]; reason:string|null }
