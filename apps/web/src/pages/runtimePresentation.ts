import type {
  ConversationMessage,
  ExternalInteractionMessagePayload,
  ExternalInteractionStatus,
  PauseSource,
  TextMessagePayload,
  WorkflowInstance,
} from '../types';

export const pauseSourceLabel: Record<PauseSource, string> = {
  GRAPH_NODE: '编排人工节点',
  AGENT_REQUEST: '智能体主动求助',
  EXTERNAL_INTERACTION: '外部交互',
  TIMEOUT_POLICY: '超时策略',
};

export function textPayload(message: ConversationMessage): TextMessagePayload | null {
  return message.payloadType === 'TEXT' ? (message.payload as TextMessagePayload) : null;
}

export function interactionPayload(message: ConversationMessage): ExternalInteractionMessagePayload | null {
  return message.payloadType === 'EXTERNAL_INTERACTION' ? (message.payload as ExternalInteractionMessagePayload) : null;
}

export function interactionSpec(message: ConversationMessage) {
  return interactionPayload(message)?.spec ?? null;
}

export function interactionProjection(message: ConversationMessage) {
  return interactionPayload(message)?.projection ?? null;
}

export function interactionStatusColor(status?: ExternalInteractionStatus | string | null) {
  switch (status) {
    case 'AWAITING_USER_ACTION':
      return 'gold';
    case 'RETURNED':
      return 'cyan';
    case 'PROCESSING':
      return 'processing';
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
    case 'CANCELLED':
    case 'EXPIRED':
      return 'error';
    default:
      return 'default';
  }
}

export function interactionPrimaryAction(message: ConversationMessage) {
  return interactionProjection(message)?.primaryAction ?? null;
}

export function interactionSecondaryActions(message: ConversationMessage) {
  return interactionProjection(message)?.secondaryActions ?? [];
}

export function conversationMessageText(message: ConversationMessage) {
  const text = textPayload(message)?.text?.trim();
  if (text) {
    return text;
  }
  const spec = interactionSpec(message);
  const projection = interactionProjection(message);
  if (!spec) {
    return '';
  }
  const title = spec.title?.trim();
  const description = spec.instruction?.trim();
  const status = projection?.status?.trim();
  return [title, description, status].filter(Boolean).join(' · ');
}

export function workflowDecisionSummary(value?: WorkflowInstance['agentTurnState'] | null) {
  if (!value?.latestDecision) {
    return '暂无';
  }
  const decision = value.latestDecision;
  const parts: string[] = [decision.decisionType];
  if (decision.routeDecision) {
    parts.push(`route=${decision.routeDecision}`);
  }
  if (decision.toolRequests.length) {
    parts.push(`tools=${decision.toolRequests.map((item) => item.toolId).join(', ')}`);
  }
  if (decision.skillReads.length) {
    parts.push(`skills=${decision.skillReads.length}`);
  }
  if (decision.outputMessages.length) {
    parts.push(`outputs=${decision.outputMessages.map((item) => item.payloadType).join(', ')}`);
  }
  return parts.join(' / ');
}

export function toolCallTitle(value: WorkflowInstance['toolCalls'][number]) {
  if (value.resourceName?.trim()) {
    return `${value.resourceName} · ${value.operation}`;
  }
  return `${value.toolName} · ${value.operation} · ${value.toolKind}`;
}
