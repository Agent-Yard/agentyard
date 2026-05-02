import type {
  PlaybookRun as ContractsPlaybookRun,
  SessionEvent as ContractsSessionEvent,
  SessionMessage as ContractsSessionMessage,
  SessionMessageInput as ContractsSessionMessageInput,
  SessionProgressEvent as ContractsSessionProgressEvent,
  SessionReplyDraftEvent as ContractsSessionReplyDraftEvent,
  SessionRuntimeStreamEvent as ContractsSessionRuntimeStreamEvent,
  SessionStreamErrorEvent as ContractsSessionStreamErrorEvent,
} from '../../../../packages/contracts/src';

export type SessionEvent = ContractsSessionEvent;
export type SessionMessage = ContractsSessionMessage;
export type SessionMessageInput = ContractsSessionMessageInput;
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

export interface RuntimeDraftMessage {
  sessionId: string;
  turnId: string;
  messageId: string;
  text: string;
  failed: boolean;
  updatedAt: string;
}

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

export interface CreateSessionPayload {
  assistantId: string;
  customerId: string;
  openingMessage: SessionMessageInput | null;
}

export interface SendSessionMessagePayload {
  customerId: string;
  message: SessionMessageInput;
}

export interface HumanOperatorReplyPayload {
  message: SessionMessageInput;
  payload?: Record<string, unknown>;
}
