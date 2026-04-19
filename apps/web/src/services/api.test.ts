import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, setUnauthorizedHandler, UnauthorizedError } from './api';

describe('api client', () => {
  afterEach(() => {
    setUnauthorizedHandler(null);
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('surfaces network failures instead of falling back to mock data', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error('network down'));
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.getSession()).rejects.toThrow('network down');
  });

  it('redirects unauthorized responses through the registered handler', async () => {
    const onUnauthorized = vi.fn();
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ detail: 'Authentication is required' }), {
        status: 401,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    );
    setUnauthorizedHandler(onUnauthorized);
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.getSession()).rejects.toBeInstanceOf(UnauthorizedError);
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it('surfaces conflict details for concurrent session turns', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ detail: 'session has an active workflow' }), {
        status: 409,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      api.sendRuntimeSessionMessage('session-1', {
        customerId: 'customer-1',
        message: '第二条消息',
      }),
    ).rejects.toThrow('session has an active workflow');
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it('sends browser credentials for authenticated API requests', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({
        data: {
          objectType: 'DOMAIN',
          objectId: 'domain-1',
          objectName: '客服域',
          canDelete: false,
          blockers: [],
          advisories: [],
          cascadeDeletes: [],
        },
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await api.getDeletionImpactPreview('DOMAIN', 'domain-1');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/catalog/deletion-preview/DOMAIN/domain-1',
      expect.objectContaining({
        credentials: 'include',
        headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
      }),
    );
  });

  it('posts retrieval preview requests to the dedicated knowledge endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({
        data: {
          hits: [],
          lowConfidence: true,
        },
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await api.previewKnowledgeRetrieval('knowledge-1', {
      snapshotId: 'snapshot-1',
      query: '支付失败怎么办',
      topK: 5,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/knowledge-bases/knowledge-1/retrieval-preview',
      expect.objectContaining({
        credentials: 'include',
        method: 'POST',
        body: JSON.stringify({
          snapshotId: 'snapshot-1',
          query: '支付失败怎么办',
          topK: 5,
        }),
      }),
    );
  });

  it('posts runtime session creation to the session-runtime endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({
        data: {
          id: 'session-1',
          scenarioId: 'scenario-1',
          title: '默认会话',
          sessionId: 'session-1',
          customerId: 'customer-1',
          assistantId: 'assistant-1',
          assistantName: '助手',
          assistantReleaseVersion: '1.0.0',
          status: 'ACTIVE',
          primaryAgentId: 'agent-1',
          currentOwnerAgentId: 'agent-1',
          activePlaybookRunId: null,
          agentTurnActive: false,
          sessionHumanHandoffActive: false,
          pendingOwnerReevaluation: false,
          draining: false,
          sharedState: {},
          idleDeadline: null,
          createdAt: '2026-04-01T00:00:00Z',
          updatedAt: '2026-04-01T00:00:00Z',
          endedAt: null,
          latestEventSequence: 0,
        },
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await api.createRuntimeSession({
      assistantId: 'assistant-1',
      customerId: 'customer-1',
      openingMessage: '你好',
    });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/session-runtime/sessions',
      expect.objectContaining({
        credentials: 'include',
        method: 'POST',
        body: JSON.stringify({
          assistantId: 'assistant-1',
          customerId: 'customer-1',
          openingMessage: '你好',
        }),
      }),
    );
  });

  it('posts logout through the authenticated session endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({
        data: {
          postLogoutRedirectUrl: '/login',
        },
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await api.logout();

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/logout',
      expect.objectContaining({
        credentials: 'include',
        method: 'POST',
      }),
    );
  });
});
