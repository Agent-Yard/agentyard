import { ref } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useRuntimeActions } from './useRuntimeActions';
import type { SendSessionTurnResponse, SessionRuntimeDetail, SessionRuntimeSession } from '../types';

const { messageSuccess, messageError, routerPush, apiMock } = vi.hoisted(() => ({
  messageSuccess: vi.fn(),
  messageError: vi.fn(),
  routerPush: vi.fn(),
  apiMock: {
    sendRuntimeSessionTurn: vi.fn(),
    getRuntimeSessionDetail: vi.fn(),
  },
}));

vi.mock('ant-design-vue', () => ({
  message: {
    success: messageSuccess,
    error: messageError,
  },
}));

vi.mock('../services/api', () => ({
  api: apiMock,
}));

vi.mock('../router', () => ({
  router: {
    push: routerPush,
  },
}));

function makeState() {
  return {
    creatingSession: ref(false),
    sendingSessionId: ref<string | null>(null),
    runtimePreferredSessionId: ref<string | null>(null),
    runtimeSelectedSessionId: ref<string | null>(null),
  };
}

function runtimeSession(id = 'session-1'): SessionRuntimeSession {
  return {
    id,
    scenarioId: 'scenario-1',
    title: 'hello',
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
    createdAt: '2026-04-01T00:00:00Z',
    updatedAt: '2026-04-01T00:00:00Z',
    endedAt: null,
    latestMessageSequence: 1,
    latestEventSequence: 0,
  };
}

function runtimeDetail(id = 'session-1'): SessionRuntimeDetail {
  return {
    session: runtimeSession(id),
    messages: [],
    events: [],
    playbookRuns: [],
  };
}

function acceptedTurnResponse(sessionId = 'session-1'): SendSessionTurnResponse {
  return {
    sessionId,
    turnId: 'turn-1',
    status: 'ACCEPTED',
    acceptedMessageIds: ['message-1'],
    acceptedMessageAllocations: [{
      requestIndex: 0,
      clientMessageId: 'client-message-1',
      messageId: 'message-1',
      turnIndex: 0,
    }],
    duplicateExternalMessageIds: [],
    reason: null,
  };
}

describe('useRuntimeActions', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.clearAllMocks();
    vi.spyOn(crypto, 'randomUUID')
      .mockReturnValueOnce('00000000-0000-4000-8000-000000000001')
      .mockReturnValueOnce('00000000-0000-4000-8000-000000000101');
  });

  it('selects started sessions without waiting for background refresh', async () => {
    const state = makeState();
    const applyRuntimeSessionDetail = vi.fn();
    const addRuntimeUserDrafts = vi.fn();
    const reconcileRuntimeUserDrafts = vi.fn();
    const markRuntimeUserDraftsFailed = vi.fn();
    const refresh = vi.fn().mockReturnValue(new Promise(() => {}));
    apiMock.sendRuntimeSessionTurn.mockResolvedValue(acceptedTurnResponse('session-created'));
    apiMock.getRuntimeSessionDetail.mockResolvedValue(runtimeDetail('session-created'));
    const actions = useRuntimeActions(
      state,
      {
        applyRuntimeSessionDetail,
        addRuntimeUserDrafts,
        reconcileRuntimeUserDrafts,
        markRuntimeUserDraftsFailed,
      },
      refresh,
      (_, fallback) => fallback,
    );

    await actions.handleStartSession({
      assistantId: 'assistant-1',
      customerId: 'customer-1',
      openingMessage: 'hello',
    });

    expect(applyRuntimeSessionDetail).toHaveBeenCalledWith(runtimeDetail('session-created'));
    expect(state.runtimePreferredSessionId.value).toBe('session-created');
    expect(state.runtimeSelectedSessionId.value).toBe('session-created');
    expect(routerPush).toHaveBeenCalledWith('/console/runtime');
    expect(refresh).toHaveBeenCalledWith(false);
    expect(state.creatingSession.value).toBe(false);
    expect(messageSuccess).toHaveBeenCalledWith('Session 已启动');
    expect(addRuntimeUserDrafts).toHaveBeenCalledWith([expect.objectContaining({
      draftType: 'USER',
      clientMessageId: '00000000-0000-4000-8000-000000000101',
      turnDedupKey: 'web-turn:assistant-1:00000000-0000-4000-8000-000000000001',
      blocks: [{ type: 'TEXT', text: 'hello' }],
    })]);
    expect(apiMock.sendRuntimeSessionTurn).toHaveBeenCalledWith({
      assistantId: 'assistant-1',
      customerId: 'customer-1',
      turnDedupKey: 'web-turn:assistant-1:00000000-0000-4000-8000-000000000001',
      messages: [{
        clientMessageId: '00000000-0000-4000-8000-000000000101',
        blocks: [{ type: 'TEXT', text: 'hello' }],
        metadata: {},
        occurredAt: expect.any(String),
      }],
      metadata: {},
    });
    expect(reconcileRuntimeUserDrafts).toHaveBeenCalledWith(
      acceptedTurnResponse('session-created'),
      'web-turn:assistant-1:00000000-0000-4000-8000-000000000001',
    );
    expect(markRuntimeUserDraftsFailed).not.toHaveBeenCalled();
  });

  it('clears sending state after accepted send response without waiting for background refresh', async () => {
    const state = makeState();
    const applyRuntimeSessionDetail = vi.fn();
    const addRuntimeUserDrafts = vi.fn();
    const reconcileRuntimeUserDrafts = vi.fn();
    const markRuntimeUserDraftsFailed = vi.fn();
    const refresh = vi.fn().mockReturnValue(new Promise(() => {}));
    apiMock.sendRuntimeSessionTurn.mockResolvedValue(acceptedTurnResponse('session-1'));
    apiMock.getRuntimeSessionDetail.mockResolvedValue(runtimeDetail('session-1'));
    const actions = useRuntimeActions(
      state,
      {
        applyRuntimeSessionDetail,
        addRuntimeUserDrafts,
        reconcileRuntimeUserDrafts,
        markRuntimeUserDraftsFailed,
      },
      refresh,
      (_, fallback) => fallback,
    );

    await actions.handleSendMessage({
      sessionId: 'session-1',
      customerId: 'customer-1',
      assistantId: 'assistant-1',
      message: 'follow up',
    });

    expect(applyRuntimeSessionDetail).toHaveBeenCalledWith(runtimeDetail('session-1'));
    expect(state.runtimePreferredSessionId.value).toBe('session-1');
    expect(state.runtimeSelectedSessionId.value).toBe('session-1');
    expect(routerPush).toHaveBeenCalledWith('/console/runtime');
    expect(refresh).toHaveBeenCalledWith(false);
    expect(state.sendingSessionId.value).toBeNull();
    expect(messageError).not.toHaveBeenCalled();
    expect(apiMock.sendRuntimeSessionTurn).toHaveBeenCalledWith({
      sessionId: 'session-1',
      customerId: 'customer-1',
      assistantId: 'assistant-1',
      turnDedupKey: 'web-turn:session-1:00000000-0000-4000-8000-000000000001',
      messages: [{
        clientMessageId: '00000000-0000-4000-8000-000000000101',
        blocks: [{ type: 'TEXT', text: 'follow up' }],
        metadata: {},
        occurredAt: expect.any(String),
      }],
      metadata: {},
    });
  });

  it('can submit multiple user draft messages in one turn', async () => {
    vi.mocked(crypto.randomUUID)
      .mockReset()
      .mockReturnValueOnce('00000000-0000-4000-8000-000000000002')
      .mockReturnValueOnce('00000000-0000-4000-8000-000000000102')
      .mockReturnValueOnce('00000000-0000-4000-8000-000000000103');
    const state = makeState();
    const helpers = {
      applyRuntimeSessionDetail: vi.fn(),
      addRuntimeUserDrafts: vi.fn(),
      reconcileRuntimeUserDrafts: vi.fn(),
      markRuntimeUserDraftsFailed: vi.fn(),
    };
    const refresh = vi.fn().mockReturnValue(new Promise(() => {}));
    const response: SendSessionTurnResponse = {
      sessionId: 'session-1',
      turnId: 'turn-2',
      status: 'ACCEPTED',
      acceptedMessageIds: ['message-2', 'message-3'],
      acceptedMessageAllocations: [
        { requestIndex: 0, clientMessageId: 'client-message-2', messageId: 'message-2', turnIndex: 0 },
        { requestIndex: 1, clientMessageId: 'client-message-3', messageId: 'message-3', turnIndex: 1 },
      ],
      duplicateExternalMessageIds: [],
      reason: null,
    };
    apiMock.sendRuntimeSessionTurn.mockResolvedValue(response);
    apiMock.getRuntimeSessionDetail.mockResolvedValue(runtimeDetail('session-1'));
    const actions = useRuntimeActions(state, helpers, refresh, (_, fallback) => fallback);

    await actions.handleSendMessage({
      sessionId: 'session-1',
      customerId: 'customer-1',
      assistantId: 'assistant-1',
      messages: ['first', 'second'],
    });

    expect(apiMock.sendRuntimeSessionTurn).toHaveBeenCalledWith(expect.objectContaining({
      assistantId: 'assistant-1',
      turnDedupKey: 'web-turn:session-1:00000000-0000-4000-8000-000000000002',
      messages: [
        expect.objectContaining({ clientMessageId: '00000000-0000-4000-8000-000000000102', blocks: [{ type: 'TEXT', text: 'first' }] }),
        expect.objectContaining({ clientMessageId: '00000000-0000-4000-8000-000000000103', blocks: [{ type: 'TEXT', text: 'second' }] }),
      ],
    }));
    expect(helpers.addRuntimeUserDrafts).toHaveBeenCalledWith([
      expect.objectContaining({ clientMessageId: '00000000-0000-4000-8000-000000000102', requestIndex: 0 }),
      expect.objectContaining({ clientMessageId: '00000000-0000-4000-8000-000000000103', requestIndex: 1 }),
    ]);
    expect(helpers.reconcileRuntimeUserDrafts).toHaveBeenCalledWith(response, 'web-turn:session-1:00000000-0000-4000-8000-000000000002');
  });
});
