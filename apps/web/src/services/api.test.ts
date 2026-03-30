import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './api';

describe('api client', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('surfaces network failures instead of falling back to mock data', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error('network down'));
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.getSession()).rejects.toThrow('network down');
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
      api.sendConversationMessage('session-1', {
        requester: 'tester',
        message: '第二条消息',
      }),
    ).rejects.toThrow('session has an active workflow');
    expect(fetchMock).toHaveBeenCalledOnce();
  });
});
