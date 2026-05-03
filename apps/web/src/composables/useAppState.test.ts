import { describe, expect, it } from 'vitest';
import { reconcileRuntimeDraftsWithDetail } from './useAppState';
import type { RuntimeDraftMessage, SessionRuntimeDetail } from '../types';

function runtimeDraft(messageId: string, sessionId = 'session-1'): RuntimeDraftMessage {
  return {
    sessionId,
    turnId: `turn-${messageId}`,
    messageId,
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

describe('useAppState runtime draft lifecycle', () => {
  it('keeps live drafts when session snapshots do not contain the corresponding durable message', () => {
    const liveDraft = runtimeDraft('session-message-reply-1');
    const otherDraft = runtimeDraft('session-message-other', 'session-2');

    expect(reconcileRuntimeDraftsWithDetail([liveDraft, otherDraft], runtimeDetail(['session-message-existing']))).toEqual([
      liveDraft,
      otherDraft,
    ]);
  });

  it('removes only the draft whose messageId has arrived in durable session detail', () => {
    const finalizedDraft = runtimeDraft('session-message-reply-1');
    const liveDraft = runtimeDraft('session-message-reply-2');
    const otherSessionDraft = runtimeDraft('session-message-reply-1', 'session-2');

    expect(reconcileRuntimeDraftsWithDetail(
      [finalizedDraft, liveDraft, otherSessionDraft],
      runtimeDetail(['session-message-reply-1']),
    )).toEqual([liveDraft, otherSessionDraft]);
  });
});
