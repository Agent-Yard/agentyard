import { onUnmounted, watch, type Ref } from 'vue';
import type { WorkflowInstance } from '../types';

export function useWorkflowPolling(workflows: Ref<WorkflowInstance[]>, refresh: (showLoading?: boolean) => Promise<void>) {
  let workflowPollingHandle: number | null = null;

  function stopWorkflowPolling() {
    if (workflowPollingHandle === null) {
      return;
    }
    window.clearInterval(workflowPollingHandle);
    workflowPollingHandle = null;
  }

  function startWorkflowPolling() {
    if (workflowPollingHandle !== null) {
      return;
    }
    workflowPollingHandle = window.setInterval(() => {
      void refresh();
    }, 3000);
  }

  watch(
    () => workflows.value.some((item) => item.status === 'RUNNING'),
    (hasRunningWorkflow) => {
      if (hasRunningWorkflow) {
        startWorkflowPolling();
        return;
      }
      stopWorkflowPolling();
    },
    { immediate: true },
  );

  onUnmounted(() => {
    stopWorkflowPolling();
  });
}
