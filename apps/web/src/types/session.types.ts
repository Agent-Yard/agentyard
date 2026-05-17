import type {
  PlaybookRun as ContractsPlaybookRun,
  SessionEvent as ContractsSessionEvent,
  SessionMessage as ContractsSessionMessage,
  SessionMessageInput as ContractsSessionMessageInput,
  SessionProgressEvent as ContractsSessionProgressEvent,
  SessionReplyDraftEvent as ContractsSessionReplyDraftEvent,
  SessionRuntimeStreamEvent as ContractsSessionRuntimeStreamEvent,
  SessionStreamErrorEvent as ContractsSessionStreamErrorEvent,
  SendSessionTurnRequest as ContractsSendSessionTurnRequest,
  SendSessionTurnResponse as ContractsSendSessionTurnResponse,
  WebSessionTurnMessageInput as ContractsWebSessionTurnMessageInput,
} from '../../../../packages/contracts/src';

export type SessionEvent = ContractsSessionEvent;
export type SessionMessage = ContractsSessionMessage;
export type SessionMessageInput = ContractsSessionMessageInput;
export type WebSessionTurnMessageInput = ContractsWebSessionTurnMessageInput;
export type SendSessionTurnPayload = ContractsSendSessionTurnRequest;
export type SendSessionTurnResponse = ContractsSendSessionTurnResponse;
export type PlaybookRun = ContractsPlaybookRun;

export interface SessionRuntimeSession {
  id: string;
  scenarioId: string;
  title: string;
  customerId: string;
  assistantId: string;
  assistantName: string;
  assistantReleaseVersion: string;
  status: string;
  primaryAgentId: string;
  currentOwnerAgentId: string;
  activePlaybookRunId: string | null;
  agentTurnActive: boolean;
  sessionHumanHandoffActive: boolean;
  pendingOwnerReevaluation: boolean;
  draining: boolean;
  sharedState: Record<string, unknown>;
  idleDeadline: string | null;
  createdAt: string;
  updatedAt: string;
  endedAt: string | null;
  latestMessageSequence: number;
  latestEventSequence: number;
}

export interface SessionRuntimeDetail {
  session: SessionRuntimeSession;
  messages: SessionMessage[];
  events: SessionEvent[];
  playbookRuns: PlaybookRun[];
}

export type SessionRuntimeStreamEvent = ContractsSessionRuntimeStreamEvent<SessionRuntimeDetail>;
export type SessionProgressEvent = ContractsSessionProgressEvent;
export type SessionReplyDraftEvent = ContractsSessionReplyDraftEvent;
export type SessionStreamErrorEvent = ContractsSessionStreamErrorEvent;

export interface RuntimeReplyDraftMessage {
  draftType: 'REPLY';
  sessionId: string;
  turnId: string;
  replyMessageId: string;
  text: string;
  failed: boolean;
  updatedAt: string;
}

export interface RuntimeUserDraftMessage {
  draftType: 'USER';
  sessionId: string | null;
  turnDedupKey: string;
  clientMessageId: string;
  requestIndex: number;
  turnId: string | null;
  messageId: string | null;
  turnIndex: number | null;
  blocks: SessionMessage['blocks'];
  failed: boolean;
  submittedAt: string;
  updatedAt: string;
}

export type RuntimeDraftMessage = RuntimeReplyDraftMessage | RuntimeUserDraftMessage;

export interface PrivacyMappingSummary {
  enabled: boolean;
  privacyModelResourceId: string | null;
  privacyModelName: string | null;
  sanitizeCountByChannel: Record<string, number>;
  restoreCountByChannel: Record<string, number>;
  entityTypeBreakdown: Record<string, number>;
  placeholderCount: number;
  unresolvedPlaceholderCount: number;
  blockedEventCount: number;
  lastProcessedAt: string | null;
}

export interface HumanOperatorReplyPayload {
  message: SessionMessageInput;
  payload?: Record<string, unknown>;
}
