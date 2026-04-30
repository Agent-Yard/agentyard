import type { Agent, AssistantModelPolicy } from '../types';

export interface SelectOption {
  label: string;
  value: string;
}

export function primaryAgentOptions(agents: Agent[]): SelectOption[] {
  return agents
    .filter((agent) => agent.canOwnSession)
    .map((agent) => ({
      label: agent.name,
      value: agent.id,
    }));
}

export function assistantPublishBlockers(input: {
  modelPolicy: AssistantModelPolicy;
  primaryAgentId: string | null;
}): string[] {
  const blockers: string[] = [];
  if (!input.modelPolicy.defaultModelResourceId) {
    blockers.push('发布前需要配置草稿默认模型。');
  }
  if (!input.primaryAgentId) {
    blockers.push('发布前需要配置主智能体。');
  }
  return blockers;
}
