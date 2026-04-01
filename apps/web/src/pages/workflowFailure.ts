import type { WorkflowInstance } from '../types';

type FailureWorkflow = Pick<WorkflowInstance, 'status' | 'latestFailure'>;

export function hasActiveFailure(workflow?: FailureWorkflow | null): boolean {
  return Boolean(
    workflow?.latestFailure
    && (workflow.status === 'FAILED' || workflow.status === 'WAITING_RESUME'),
  );
}

export function failureSummary(workflow: FailureWorkflow): string {
  if (!hasActiveFailure(workflow) || !workflow.latestFailure) {
    return '';
  }
  if (workflow.status === 'WAITING_RESUME') {
    return `错误转恢复 / ${workflow.latestFailure.code}`;
  }
  return workflow.latestFailure.code;
}

export function failureAlertType(workflow: FailureWorkflow): 'error' | 'warning' {
  return workflow.status === 'FAILED' ? 'error' : 'warning';
}

export function failureAlertMessage(workflow: FailureWorkflow): string {
  return workflow.status === 'FAILED' ? 'Workflow 已失败' : 'Workflow 发生错误并进入待恢复状态';
}

export function failureAlertDescription(workflow: FailureWorkflow): string {
  if (!workflow.latestFailure) {
    return '';
  }
  return `${workflow.latestFailure.category} / ${workflow.latestFailure.code}。root cause：${workflow.latestFailure.rootCause}`;
}
