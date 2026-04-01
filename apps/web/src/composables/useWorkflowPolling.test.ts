import { effectScope, ref } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useWorkflowPolling } from './useWorkflowPolling';

describe('useWorkflowPolling', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('keeps polling while workflows are waiting for resume', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('window', globalThis);
    const workflows = ref([
      {
        id: 'wf-1',
        status: 'WAITING_RESUME',
      },
    ] as any[]);
    const refresh = vi.fn().mockResolvedValue(undefined);
    const scope = effectScope();

    scope.run(() => {
      useWorkflowPolling(workflows, refresh);
    });

    await vi.advanceTimersByTimeAsync(3000);

    expect(refresh).toHaveBeenCalledTimes(1);
    scope.stop();
  });
});
