import { describe, expect, it } from 'vitest';
import { createObjectHistoryLoader, createObjectHistoryState } from './objectHistoryLoader';

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('objectHistoryLoader', () => {
  it('ignores stale responses from older reloads', async () => {
    const state = createObjectHistoryState();
    const first = deferred<{ items: Array<{ id: string }>; nextCursor: string | null }>();
    const second = deferred<{ items: Array<{ id: string }>; nextCursor: string | null }>();
    const fetches = [first, second];
    const loader = createObjectHistoryLoader(state, () => fetches.shift()!.promise as any);

    const firstLoad = loader.load({ aggregateType: 'DOMAIN', objectId: 'domain-1' });
    const secondLoad = loader.load({ aggregateType: 'DOMAIN', objectId: 'domain-2' });

    second.resolve({ items: [{ id: 'newer-event' }], nextCursor: 'cursor-2' });
    await secondLoad;
    first.resolve({ items: [{ id: 'stale-event' }], nextCursor: 'cursor-1' });
    await firstLoad;

    expect(state.events).toEqual([{ id: 'newer-event' }]);
    expect(state.nextCursor).toBe('cursor-2');
    expect(state.loading).toBe(false);
    expect(state.loadingMore).toBe(false);
  });

  it('clears previous events before a fresh reload and keeps them cleared on failure', async () => {
    const state = createObjectHistoryState();
    state.events = [{ id: 'old-event' }] as any;
    state.nextCursor = 'cursor-old';

    const pending = deferred<{ items: Array<{ id: string }>; nextCursor: string | null }>();
    const loader = createObjectHistoryLoader(state, () => pending.promise as any);

    const loadPromise = loader.load({ aggregateType: 'DOMAIN', objectId: 'domain-1' });

    expect(state.events).toEqual([]);
    expect(state.nextCursor).toBeNull();
    expect(state.loading).toBe(true);

    pending.reject(new Error('加载失败'));
    await loadPromise;

    expect(state.events).toEqual([]);
    expect(state.nextCursor).toBeNull();
    expect(state.errorMessage).toBe('加载失败');
    expect(state.loading).toBe(false);
  });
});
