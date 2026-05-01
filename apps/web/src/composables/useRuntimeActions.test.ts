import { ref } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useRuntimeActions } from './useRuntimeActions';
import type { SessionRuntimeSession } from '../types';

const { messageSuccess, messageError, routerPush, apiMock } = vi.hoisted(() => ({
  messageSuccess: vi.fn(),
  messageError: vi.fn(),
  routerPush: vi.fn(),
  apiMock: {
    createRuntimeSession: vi.fn(),
    sendRuntimeSessionMessage: vi.fn(),
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

describe('useRuntimeActions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('selects created sessions without waiting for background refresh', async () => {
    const state = makeState();
    const upsertRuntimeSession = vi.fn();
    const refresh = vi.fn().mockReturnValue(new Promise(() => {}));
    const created = runtimeSession('session-created');
    apiMock.createRuntimeSession.mockResolvedValue(created);
    const actions = useRuntimeActions(
      state,
      { upsertRuntimeSession },
      refresh,
      (_, fallback) => fallback,
    );

    await actions.handleCreateSession({
      assistantId: 'assistant-1',
      customerId: 'customer-1',
      openingMessage: 'hello',
    });

    expect(upsertRuntimeSession).toHaveBeenCalledWith(created);
    expect(state.runtimePreferredSessionId.value).toBe('session-created');
    expect(state.runtimeSelectedSessionId.value).toBe('session-created');
    expect(routerPush).toHaveBeenCalledWith('/console/runtime');
    expect(refresh).toHaveBeenCalledWith(false);
    expect(state.creatingSession.value).toBe(false);
    expect(messageSuccess).toHaveBeenCalledWith('会话已创建');
  });

  it('clears sending state after accepted send response without waiting for background refresh', async () => {
    const state = makeState();
    const upsertRuntimeSession = vi.fn();
    const refresh = vi.fn().mockReturnValue(new Promise(() => {}));
    const updated = runtimeSession('session-1');
    apiMock.sendRuntimeSessionMessage.mockResolvedValue(updated);
    const actions = useRuntimeActions(
      state,
      { upsertRuntimeSession },
      refresh,
      (_, fallback) => fallback,
    );

    await actions.handleSendMessage({
      sessionId: 'session-1',
      customerId: 'customer-1',
      message: 'follow up',
    });

    expect(upsertRuntimeSession).toHaveBeenCalledWith(updated);
    expect(state.runtimePreferredSessionId.value).toBe('session-1');
    expect(state.runtimeSelectedSessionId.value).toBe('session-1');
    expect(routerPush).toHaveBeenCalledWith('/console/runtime');
    expect(refresh).toHaveBeenCalledWith(false);
    expect(state.sendingSessionId.value).toBeNull();
    expect(messageError).not.toHaveBeenCalled();
  });
});
