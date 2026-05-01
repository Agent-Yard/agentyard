import { describe, expect, it } from 'vitest';
import { assistantPublishBlockers, primaryAgentOptions } from './assistantPage';
import type { Agent } from '../types';

function agent(overrides: Partial<Agent>): Agent {
  return {
    id: 'agent-1',
    assistantId: 'assistant-1',
    name: '接待智能体',
    role: 'support',
    responsibility: 'answer customer questions',
    canOwnSession: true,
    allowedActions: ['REPLY'],
    switchableOwnerAgentIds: [],
    playbookIds: [],
    executionPolicy: {
      inheritAssistantDefaults: true,
      modelResourceId: null,
      privacyModelResourceId: null,
      privacyMappingEnabled: null,
      systemPrompt: '',
      knowledgeEnabled: false,
      inheritAssistantKnowledge: true,
      knowledgeBaseId: null,
      memoryWindowSize: 8,
      skillResourceIds: [],
      toolResourceIds: [],
    },
    ...overrides,
  };
}

describe('AssistantPage helpers', () => {
  it('only offers agents that can own a session as primary agents', () => {
    expect(primaryAgentOptions([
      agent({ id: 'agent-owner', name: 'Owner Agent' }),
      agent({ id: 'agent-tool', name: 'Tool Agent', canOwnSession: false }),
    ])).toEqual([
      { label: 'Owner Agent', value: 'agent-owner' },
    ]);
  });

  it('reports every missing publication prerequisite', () => {
    expect(assistantPublishBlockers({
      modelPolicy: { defaultModelResourceId: null, enableThinking: null, reasoningEffort: null },
      primaryAgentId: null,
    })).toEqual([
      '发布前需要配置草稿默认模型。',
      '发布前需要配置主智能体。',
    ]);
  });

  it('allows publishing when default model and primary agent are configured', () => {
    expect(assistantPublishBlockers({
      modelPolicy: { defaultModelResourceId: 'resource-model-1', enableThinking: null, reasoningEffort: null },
      primaryAgentId: 'agent-1',
    })).toEqual([]);
  });
});
