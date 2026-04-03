import { describe, expect, it } from 'vitest';
import type { ConversationMessage, WorkflowInstance } from '../types';
import { conversationMessageText, toolCallTitle, visibleConversationMessages, workflowDecisionSummary } from './runtimePresentation';

function makeMessage(overrides: Partial<ConversationMessage>): ConversationMessage {
  return {
    id: 'msg-1',
    messageKey: 'agent-node:1:1',
    sessionId: 'session-1',
    role: 'ASSISTANT',
    senderType: 'ASSISTANT',
    senderId: 'assistant-1',
    senderName: '助手',
    payloadType: 'TEXT',
    payload: { text: '默认文本' },
    createdAt: '2026-04-02T00:00:00Z',
    taskId: 'task-1',
    workflowInstanceId: 'wf-1',
    ...overrides,
  };
}

describe('runtimePresentation helpers', () => {
  it('prefers text payloads when rendering conversation messages', () => {
    expect(conversationMessageText(makeMessage({ payload: { text: '处理完成' } }))).toBe('处理完成');
  });

  it('renders interaction messages from spec and projection status', () => {
    expect(conversationMessageText(makeMessage({
      payloadType: 'EXTERNAL_INTERACTION',
      payload: {
        spec: {
          interactionType: 'OAUTH_REDIRECT',
          title: '完成授权',
          instruction: '请前往外部页面完成授权。',
          provider: 'oauth-demo',
          providerReference: 'ref-1',
          launchUrl: 'https://example.com/oauth',
          returnPath: '/console/runtime',
          expiresAt: null,
          primaryActionLabel: '去授权',
          secondaryActions: [],
          displayHints: {},
        },
        projection: {
          interactionTaskId: 'interaction-1',
          status: 'AWAITING_USER_ACTION',
          primaryAction: null,
          secondaryActions: [],
          displayHints: {},
        },
      },
    }))).toBe('完成授权 · 请前往外部页面完成授权。 · AWAITING_USER_ACTION');
  });

  it('summarizes the latest workflow decision with output message types', () => {
    const agentTurnState: NonNullable<WorkflowInstance['agentTurnState']> = {
      phase: 'FINALIZE',
      turnIndex: 1,
      latestDecision: {
        decisionType: 'FINAL',
        routeDecision: 'default',
        outputMessages: [
          { payloadType: 'TEXT', payload: { text: '说明' } },
          {
            payloadType: 'EXTERNAL_INTERACTION',
            payload: {
              spec: {
                interactionType: 'GENERIC_REDIRECT',
                title: '外部处理',
                instruction: '请处理后返回',
                provider: null,
                providerReference: null,
                launchUrl: null,
                returnPath: null,
                expiresAt: null,
                primaryActionLabel: null,
                secondaryActions: [],
                displayHints: {},
              },
            },
          },
        ],
        skillReads: ['skill-v1'],
        toolRequests: [{ toolId: 'resource:tool-v1:lookup', arguments: {} }],
        humanRequest: null,
        sessionStatePatch: null,
      },
      turnLogs: [],
    };

    expect(workflowDecisionSummary(agentTurnState)).toBe('FINAL / route=default / tools=resource:tool-v1:lookup / skills=1 / outputs=TEXT, EXTERNAL_INTERACTION');
  });

  it('formats builtin tool calls without resource names', () => {
    expect(toolCallTitle({
      id: 'tool-1',
      toolId: 'builtin:knowledge_search',
      toolName: 'knowledge_search',
      toolKind: 'BUILTIN',
      providerType: 'BUILTIN',
      resourceId: null,
      resourceName: null,
      operation: 'knowledge_search',
      status: 'COMPLETED',
      detail: 'ok',
      createdAt: '2026-04-02T00:00:00Z',
    })).toBe('knowledge_search · knowledge_search · BUILTIN');
  });

  it('keeps only the last assistant message for each workflow', () => {
    const visible = visibleConversationMessages([
      makeMessage({
        id: 'msg-user-1',
        role: 'USER',
        senderType: 'USER',
        senderId: 'user',
        senderName: '用户',
        workflowInstanceId: null,
        payload: { text: '你好' },
      }),
      makeMessage({
        id: 'msg-a-1',
        messageKey: 'node-a:1:1',
        workflowInstanceId: 'wf-1',
        payload: { text: '中间输出' },
      }),
      makeMessage({
        id: 'msg-a-2',
        messageKey: 'node-b:1:1',
        workflowInstanceId: 'wf-1',
        payload: { text: '最终输出' },
      }),
      makeMessage({
        id: 'msg-user-2',
        role: 'USER',
        senderType: 'USER',
        senderId: 'user',
        senderName: '用户',
        workflowInstanceId: null,
        payload: { text: '继续' },
      }),
      makeMessage({
        id: 'msg-b-1',
        messageKey: 'node-c:1:1',
        workflowInstanceId: 'wf-2',
        payload: { text: '第二轮最终输出' },
      }),
    ]);

    expect(visible.map((item) => item.id)).toEqual(['msg-user-1', 'msg-a-2', 'msg-user-2', 'msg-b-1']);
  });
});
