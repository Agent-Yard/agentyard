import { describe, expect, it } from 'vitest';
import type { WorkflowInstance, WorkflowStatus } from '../types';
import { failureAlertDescription, failureAlertMessage, failureSummary, hasActiveFailure } from './workflowFailure';

function makeWorkflow(status: WorkflowStatus, latestFailure: WorkflowInstance['latestFailure']): WorkflowInstance {
  return {
    status,
    latestFailure,
  } as WorkflowInstance;
}

function makeFailure(code = 'LLM_PROVIDER_HTTP_ERROR'): NonNullable<WorkflowInstance['latestFailure']> {
  return {
    category: 'PROVIDER_FAILURE',
    code,
    rootCause: 'upstream 502',
    detail: 'provider returned 502',
    failedNodeKey: 'agent-node',
    failedNodeName: '售后节点',
    failedResourceId: 'resource-model',
    failedResourceName: '默认模型',
    occurredAt: '2026-03-30T00:00:00Z',
  };
}

describe('workflowFailure helpers', () => {
  it('treats FAILED workflows with latestFailure as active failures', () => {
    const workflow = makeWorkflow('FAILED', makeFailure('WORKFLOW_RUNTIME_FAILURE'));

    expect(hasActiveFailure(workflow)).toBe(true);
    expect(failureSummary(workflow)).toBe('WORKFLOW_RUNTIME_FAILURE');
    expect(failureAlertMessage(workflow)).toBe('Workflow 已失败');
    expect(failureAlertDescription(workflow)).toContain('WORKFLOW_RUNTIME_FAILURE');
  });

  it('treats WAITING_HUMAN workflows with latestFailure as active failures', () => {
    const workflow = makeWorkflow('WAITING_HUMAN', makeFailure('TOOL_HTTP_ERROR'));

    expect(hasActiveFailure(workflow)).toBe(true);
    expect(failureSummary(workflow)).toBe('错误转人工 / TOOL_HTTP_ERROR');
    expect(failureAlertMessage(workflow)).toBe('Workflow 发生错误并转人工处理');
  });

  it('does not treat COMPLETED workflows with retained latestFailure as active failures', () => {
    const workflow = makeWorkflow('COMPLETED', makeFailure('TOOL_HTTP_ERROR'));

    expect(hasActiveFailure(workflow)).toBe(false);
    expect(failureSummary(workflow)).toBe('');
  });

  it('does not treat CANCELLED workflows with retained latestFailure as active failures', () => {
    const workflow = makeWorkflow('CANCELLED', makeFailure('MODEL_RESOURCE_MISSING'));

    expect(hasActiveFailure(workflow)).toBe(false);
    expect(failureSummary(workflow)).toBe('');
  });
});
