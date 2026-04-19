import type {
  PlaybookRun as ContractsPlaybookRun,
  SessionEvent as ContractsSessionEvent,
} from '../../../../packages/contracts/src';

export type SessionEvent = ContractsSessionEvent;
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
  latestEventSequence: number;
}

export interface SessionRuntimeDetail {
  session: SessionRuntimeSession;
  events: SessionEvent[];
  playbookRuns: PlaybookRun[];
}

export interface CreateSessionPayload {
  assistantId: string;
  customerId: string;
  openingMessage: string;
}

export interface SendSessionMessagePayload {
  customerId: string;
  message: string;
}

export interface HumanOperatorReplyPayload {
  operatorId: string;
  message: string;
  payload?: Record<string, unknown>;
}
