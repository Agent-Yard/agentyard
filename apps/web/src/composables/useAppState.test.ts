import { ref } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  mergeRuntimeUserDrafts,
  reconcileRuntimeDraftsWithDetail,
  reconcileRuntimeDraftsWithTurnAcceptance,
  markRuntimeDraftsForStreamError,
  useAppState,
} from './useAppState';
import { api } from '../services/api';
import type { CatalogSummary, RuntimeDraftMessage, RuntimeUserDraftMessage, SessionRuntimeDetail, UserSession } from '../types';
import type { PageKey } from '../config/navigation';

function runtimeDraft(replyMessageId: string, sessionId = 'session-1'): RuntimeDraftMessage {
  return {
    draftType: 'REPLY',
    sessionId,
    turnId: `turn-${replyMessageId}`,
    replyMessageId,
    text: 'streaming',
    failed: false,
    updatedAt: '2026-05-03T00:00:02Z',
  };
}

function userDraft(
  clientMessageId: string,
  requestIndex = 0,
  sessionId: string | null = 'session-1',
): RuntimeUserDraftMessage {
  return {
    draftType: 'USER',
    sessionId,
    turnDedupKey: 'web-turn:session-1:turn-1',
    clientMessageId,
    requestIndex,
    turnId: null,
    messageId: null,
    turnIndex: null,
    blocks: [{ type: 'TEXT', text: `draft ${requestIndex}` }],
    failed: false,
    submittedAt: '2026-05-03T00:00:01Z',
    updatedAt: '2026-05-03T00:00:01Z',
  };
}

function runtimeDetail(messageIds: string[], sessionId = 'session-1'): SessionRuntimeDetail {
  return {
    session: {
      id: sessionId,
      scenarioId: 'scenario-1',
      title: 'session',
      customerId: 'customer-1',
      assistantId: 'assistant-1',
      assistantName: 'Assistant',
      assistantReleaseVersion: '1.0.0',
      status: 'ACTIVE',
      primaryAgentId: 'agent-1',
      currentOwnerAgentId: 'agent-1',
      activePlaybookRunId: null,
      agentTurnActive: true,
      sessionHumanHandoffActive: false,
      pendingOwnerReevaluation: false,
      draining: false,
      sharedState: {},
      idleDeadline: null,
      createdAt: '2026-05-03T00:00:00Z',
      updatedAt: '2026-05-03T00:00:00Z',
      endedAt: null,
      latestMessageSequence: messageIds.length,
      latestEventSequence: 0,
    },
    messages: messageIds.map((messageId, index) => ({
      messageId,
      sessionId,
      sequence: index + 1,
      turnId: `turn-${index + 1}`,
      turnIndex: 0,
      producerType: 'PLATFORM',
      externalMessageId: null,
      clientMessageId: null,
      occurredAt: '2026-05-03T00:00:03Z',
      role: 'ASSISTANT',
      sender: {
        senderType: 'AGENT',
        senderId: 'agent-1',
        senderName: 'Agent',
      },
      status: 'SENT',
      blocks: [{ type: 'TEXT', text: 'done' }],
      metadata: {},
      relatedPlaybookRunId: null,
      relatedOwnerAgentId: 'agent-1',
      sourceEventId: null,
      createdAt: '2026-05-03T00:00:03Z',
      updatedAt: '2026-05-03T00:00:03Z',
    })),
    events: [],
    playbookRuns: [],
  };
}

function userSession(): UserSession {
  return {
    userId: 'user-1',
    displayName: 'User One',
    currentRole: 'PLATFORM_ADMIN',
    availableRoles: ['PLATFORM_ADMIN'],
  };
}

function emptyCatalog(): CatalogSummary {
  return {
    domains: [],
    scenarios: [],
    assistants: [],
    agents: [],
    resources: [],
    knowledgeBases: [],
    resourceCenter: {
      totalResources: 0,
      domainSharedResources: 0,
      privateResources: 0,
      references: [],
    },
    resourceBlueprints: [],
  };
}

describe('useAppState runtime draft lifecycle', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('keeps live drafts when session snapshots do not contain the corresponding durable message', () => {
    const liveDraft = runtimeDraft('session-message-reply-1');
    const otherDraft = runtimeDraft('session-message-other', 'session-2');

    expect(reconcileRuntimeDraftsWithDetail([liveDraft, otherDraft], runtimeDetail(['session-message-existing']))).toEqual([
      liveDraft,
      otherDraft,
    ]);
  });

  it('removes only the draft whose replyMessageId has arrived in durable session detail', () => {
    const finalizedDraft = runtimeDraft('session-message-reply-1');
    const liveDraft = runtimeDraft('session-message-reply-2');
    const otherSessionDraft = runtimeDraft('session-message-reply-1', 'session-2');

    expect(reconcileRuntimeDraftsWithDetail(
      [finalizedDraft, liveDraft, otherSessionDraft],
      runtimeDetail(['session-message-reply-1']),
    )).toEqual([liveDraft, otherSessionDraft]);
  });

  it('reconciles user drafts from accepted message allocations instead of acceptedMessageIds order', () => {
    const firstDraft = userDraft('client-message-1', 0);
    const secondDraft = userDraft('client-message-2', 1);

    expect(reconcileRuntimeDraftsWithTurnAcceptance([firstDraft, secondDraft], {
      sessionId: 'session-1',
      turnId: 'turn-accepted',
      status: 'ACCEPTED',
      acceptedMessageIds: ['wrong-order-message-2', 'wrong-order-message-1'],
      acceptedMessageAllocations: [
        { requestIndex: 1, clientMessageId: 'client-message-2', messageId: 'message-2', turnIndex: 1 },
        { requestIndex: 0, clientMessageId: 'client-message-1', messageId: 'message-1', turnIndex: 0 },
      ],
      duplicateExternalMessageIds: [],
      reason: null,
    }, 'web-turn:session-1:turn-1')).toEqual([
      expect.objectContaining({
        draftType: 'USER',
        clientMessageId: 'client-message-1',
        sessionId: 'session-1',
        turnId: 'turn-accepted',
        messageId: 'message-1',
        turnIndex: 0,
        failed: false,
      }),
      expect.objectContaining({
        draftType: 'USER',
        clientMessageId: 'client-message-2',
        sessionId: 'session-1',
        turnId: 'turn-accepted',
        messageId: 'message-2',
        turnIndex: 1,
        failed: false,
      }),
    ]);
  });

  it('does not duplicate local user bubbles when the same turn is registered again', () => {
    const existing = userDraft('client-message-1', 0);
    const replayed = {
      ...existing,
      blocks: [{ type: 'TEXT' as const, text: 'same draft replayed' }],
      updatedAt: '2026-05-03T00:00:05Z',
    };

    expect(mergeRuntimeUserDrafts([existing], [replayed])).toEqual([replayed]);
  });

  it('removes accepted user drafts when their durable messages arrive', () => {
    const acceptedDraft = {
      ...userDraft('client-message-1', 0),
      messageId: 'message-1',
      turnId: 'turn-1',
      turnIndex: 0,
    };
    const liveDraft = userDraft('client-message-2', 1);

    expect(reconcileRuntimeDraftsWithDetail(
      [acceptedDraft, liveDraft],
      runtimeDetail(['message-1']),
    )).toEqual([liveDraft]);
  });

  it('marks only reply drafts failed for stream errors on an accepted turn', () => {
    const acceptedUserDraft = {
      ...userDraft('client-message-1', 0),
      sessionId: 'session-1',
      turnId: 'turn-1',
      messageId: 'message-1',
      turnIndex: 0,
    };
    const replyDraft = {
      ...runtimeDraft('reply-message-1'),
      sessionId: 'session-1',
      turnId: 'turn-1',
    };

    expect(markRuntimeDraftsForStreamError(
      [acceptedUserDraft, replyDraft],
      {
        id: 'error-1',
        type: 'SESSION_STREAM_ERROR',
        occurredAt: '2026-05-03T00:00:04Z',
        sessionId: 'session-1',
        turnId: 'turn-1',
        code: 'RUNTIME_FAILED',
        message: 'runtime failed',
        retryable: false,
        detail: {},
      },
    )).toEqual([
      acceptedUserDraft,
      expect.objectContaining({
        draftType: 'REPLY',
        failed: true,
        updatedAt: '2026-05-03T00:00:04Z',
      }),
    ]);
  });

  it('checks the session before loading catalog and runtime data', async () => {
    const calls: string[] = [];
    vi.spyOn(api, 'getSession').mockImplementation(async () => {
      calls.push('session');
      return userSession();
    });
    vi.spyOn(api, 'getCatalogSummary').mockImplementation(async () => {
      calls.push('catalog');
      return emptyCatalog();
    });
    vi.spyOn(api, 'getRuntimeSessions').mockImplementation(async () => {
      calls.push('runtime-sessions');
      return [];
    });

    const state = useAppState(ref<PageKey>('runtime'));

    await state.refresh(true);

    expect(calls[0]).toBe('session');
    expect(calls.slice(1).sort()).toEqual(['catalog', 'runtime-sessions']);
  });

  it('does not load catalog or runtime data when the session check fails', async () => {
    vi.spyOn(api, 'getSession').mockRejectedValue(new Error('Authentication is required'));
    const getCatalogSummary = vi.spyOn(api, 'getCatalogSummary').mockResolvedValue(emptyCatalog());
    const getRuntimeSessions = vi.spyOn(api, 'getRuntimeSessions').mockResolvedValue([]);

    const state = useAppState(ref<PageKey>('runtime'));

    await expect(state.refresh(true)).rejects.toThrow('Authentication is required');
    expect(getCatalogSummary).not.toHaveBeenCalled();
    expect(getRuntimeSessions).not.toHaveBeenCalled();
  });
});
