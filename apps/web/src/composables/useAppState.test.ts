import { ref } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { reconcileRuntimeDraftsWithDetail, useAppState } from './useAppState';
import { api } from '../services/api';
import type { CatalogSummary, RuntimeDraftMessage, SessionRuntimeDetail, UserSession } from '../types';
import type { PageKey } from '../config/navigation';

function runtimeDraft(replyMessageId: string, sessionId = 'session-1'): RuntimeDraftMessage {
  return {
    sessionId,
    turnId: `turn-${replyMessageId}`,
    replyMessageId,
    text: 'streaming',
    failed: false,
    updatedAt: '2026-05-03T00:00:02Z',
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
