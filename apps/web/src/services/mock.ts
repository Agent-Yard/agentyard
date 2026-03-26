import type {
  Agent,
  Assistant,
  AssistantOrchestration,
  AssistantRelease,
  BusinessDomain,
  CatalogSummary,
  ConversationSession,
  HumanTaskSnapshot,
  KnowledgeBase,
  KnowledgeBindingSnapshot,
  KnowledgeRelease,
  Resource,
  ResourceBlueprint,
  ResourceCenter,
  Scenario,
  TaskInstance,
  ToolOutcomeSummary,
  UserSession,
  WorkflowInstance,
} from '../types';

function now() {
  return new Date().toISOString();
}

function version(status: 'DRAFT' | 'PUBLISHED', value: string) {
  return {
    version: value,
    status,
    updatedAt: now(),
  } as const;
}

function resource(def: Resource): Resource {
  return def;
}

const knowledgeRelease: KnowledgeRelease = {
  id: 'knowledge-release-support-v1',
  knowledgeBaseId: 'knowledge-base-support',
  version: '1.0.0',
  status: 'PUBLISHED',
  summary: '客服知识库演示版',
  snapshotId: 'snapshot-kb-support-v1',
  retrievalProfile: {
    defaultTopK: 5,
    retrievalMode: 'HYBRID',
    minScore: 0.1,
  },
  createdAt: now(),
  publishedAt: now(),
};

const knowledgeBinding: KnowledgeBindingSnapshot = {
  knowledgeBaseId: 'knowledge-base-support',
  knowledgeBaseName: '客服知识库',
  knowledgeReleaseId: knowledgeRelease.id,
  knowledgeReleaseVersion: knowledgeRelease.version,
  snapshotId: knowledgeRelease.snapshotId,
  defaultTopK: knowledgeRelease.retrievalProfile.defaultTopK,
  retrievalMode: knowledgeRelease.retrievalProfile.retrievalMode,
  minScore: knowledgeRelease.retrievalProfile.minScore,
};

const knowledgeBase: KnowledgeBase = {
  id: 'knowledge-base-support',
  domainId: 'domain-support',
  name: '客服知识库',
  shareScope: 'DOMAIN_SHARED',
  ownerType: 'DOMAIN',
  ownerId: 'domain-support',
  summary: '包含 FAQ、售后规则和人工协同说明的演示知识库',
  steward: '客服知识运营',
  tags: ['FAQ', '售后', '协同'],
  latestRelease: knowledgeRelease,
  effectiveRelease: knowledgeRelease,
  releases: [knowledgeRelease],
};

const resources: Resource[] = [
  resource({
    id: 'resource-llm-compatible',
    domainId: 'domain-support',
    name: '自定义兼容模型',
    type: 'LLM_MODEL',
    shareScope: 'DOMAIN_SHARED',
    ownerType: 'DOMAIN',
    ownerId: 'domain-support',
    summary: '支持 OpenAI Compatible 网关',
    steward: '平台 AI 团队',
    tags: ['LLM', '兼容网关'],
    latestVersion: {
      id: 'resource-version-llm-compatible-v1',
      resourceId: 'resource-llm-compatible',
      version: '1.0.0',
      status: 'PUBLISHED',
      summary: '兼容网关模型基线版',
      createdAt: now(),
      publishedAt: now(),
      configuration: {
        type: 'LLM_MODEL',
        llmModel: {
          providerType: 'OPENAI_COMPATIBLE',
          modelId: 'demo-compatible-model',
          baseUrl: 'http://localhost:11434/v1',
          apiKeyEnvVar: 'OPENAI_COMPATIBLE_API_KEY',
          organization: 'compatible-lab',
          project: 'customer-ops',
          region: 'local',
          temperature: 0.2,
          maxTokens: 1200,
        },
      },
    },
    effectiveVersion: null,
    versions: [],
  }),
  resource({
    id: 'resource-skill-router',
    domainId: 'domain-support',
    name: '路由 Skill',
    type: 'SKILL',
    shareScope: 'PRIVATE',
    ownerType: 'ASSISTANT',
    ownerId: 'assistant-customer-ops',
    summary: '用于问题分诊和路由决策的技能',
    steward: '客服协同助手团队',
    tags: ['Skill', 'Router'],
    latestVersion: {
      id: 'resource-version-skill-router-v1',
      resourceId: 'resource-skill-router',
      version: '1.0.0',
      status: 'PUBLISHED',
      summary: '路由 Skill',
      createdAt: now(),
      publishedAt: now(),
      configuration: {
        type: 'SKILL',
        skill: {
          skillName: '路由技能',
          skillDesc: '根据用户问题、知识和上下文判断路由方向。',
          skillPrompt: '优先判断问题属于 FAQ、售后策略或人工协同，并输出明确路由依据。',
        },
      },
    },
    effectiveVersion: null,
    versions: [],
  }),
  resource({
    id: 'resource-skill-faq',
    domainId: 'domain-support',
    name: 'FAQ Skill',
    type: 'SKILL',
    shareScope: 'PRIVATE',
    ownerType: 'ASSISTANT',
    ownerId: 'assistant-customer-ops',
    summary: '用于知识问答回复的技能',
    steward: '客服协同助手团队',
    tags: ['Skill', 'FAQ'],
    latestVersion: {
      id: 'resource-version-skill-faq-v1',
      resourceId: 'resource-skill-faq',
      version: '1.0.0',
      status: 'PUBLISHED',
      summary: 'FAQ Skill',
      createdAt: now(),
      publishedAt: now(),
      configuration: {
        type: 'SKILL',
        skill: {
          skillName: 'FAQ 技能',
          skillDesc: '基于知识召回内容提供常规问答回复。',
          skillPrompt: '优先基于召回到的知识内容直接回答，保持简洁、准确、可执行。',
        },
      },
    },
    effectiveVersion: null,
    versions: [],
  }),
  resource({
    id: 'resource-skill-policy',
    domainId: 'domain-support',
    name: '售后策略 Skill',
    type: 'SKILL',
    shareScope: 'PRIVATE',
    ownerType: 'ASSISTANT',
    ownerId: 'assistant-customer-ops',
    summary: '用于售后策略判定的技能',
    steward: '客服协同助手团队',
    tags: ['Skill', '售后'],
    latestVersion: {
      id: 'resource-version-skill-policy-v1',
      resourceId: 'resource-skill-policy',
      version: '1.0.0',
      status: 'PUBLISHED',
      summary: '售后策略 Skill',
      createdAt: now(),
      publishedAt: now(),
      configuration: {
        type: 'SKILL',
        skill: {
          skillName: '售后策略技能',
          skillDesc: '结合知识和工具输出判断退款或补偿策略。',
          skillPrompt: '结合规则与工具结果给出处理建议，并说明是否需要人工复核。',
        },
      },
    },
    effectiveVersion: null,
    versions: [],
  }),
  resource({
    id: 'resource-skill-handoff',
    domainId: 'domain-support',
    name: '人工协同 Skill',
    type: 'SKILL',
    shareScope: 'PRIVATE',
    ownerType: 'ASSISTANT',
    ownerId: 'assistant-customer-ops',
    summary: '用于人工交接后的总结与闭环技能',
    steward: '客服协同助手团队',
    tags: ['Skill', '人工协同'],
    latestVersion: {
      id: 'resource-version-skill-handoff-v1',
      resourceId: 'resource-skill-handoff',
      version: '1.0.0',
      status: 'PUBLISHED',
      summary: '人工协同 Skill',
      createdAt: now(),
      publishedAt: now(),
      configuration: {
        type: 'SKILL',
        skill: {
          skillName: '人工协同闭环技能',
          skillDesc: '根据人工动作、工具结果与上下文生成闭环说明。',
          skillPrompt: '整合人工处理说明、工单结果和上下文，生成最终回复。',
        },
      },
    },
    effectiveVersion: null,
    versions: [],
  }),
  resource({
    id: 'resource-tool-refund',
    domainId: 'domain-support',
    name: '售后策略 Tool',
    type: 'TOOL',
    shareScope: 'PRIVATE',
    ownerType: 'ASSISTANT',
    ownerId: 'assistant-customer-ops',
    summary: '通过 HTTP provider 返回退款与补偿策略',
    steward: '售后策略团队',
    tags: ['Tool', '退款'],
    latestVersion: {
      id: 'resource-version-tool-refund-v1',
      resourceId: 'resource-tool-refund',
      version: '1.0.0',
      status: 'PUBLISHED',
      summary: '售后策略 Tool',
      createdAt: now(),
      publishedAt: now(),
      configuration: {
        type: 'TOOL',
        tool: {
          operations: [
            {
              name: 'evaluate_refund',
              description: '根据问题和知识上下文判断退款/补偿策略',
              inputSchema: '{"question":"string","knowledgeHits":["string"]}',
              outputSchema: '{"eligibility":"string","routeKey":"string","actionPlan":"string"}',
            },
          ],
          providerType: 'HTTP',
          authType: 'SERVICE_ACCOUNT',
          timeoutSeconds: 15,
          retryPolicy: 'NONE',
          http: {
            endpoint: 'http://demo.local/skills/refund-policy',
            method: 'POST',
          },
          mcp: null,
        },
      },
    },
    effectiveVersion: null,
    versions: [],
  }),
  resource({
    id: 'resource-tool-ticket',
    domainId: 'domain-support',
    name: '工单协同 Tool',
    type: 'TOOL',
    shareScope: 'DOMAIN_SHARED',
    ownerType: 'DOMAIN',
    ownerId: 'domain-support',
    summary: '通过 MCP provider 创建和同步人工协同工单',
    steward: '客服平台集成',
    tags: ['Tool', '工单'],
    latestVersion: {
      id: 'resource-version-tool-ticket-v1',
      resourceId: 'resource-tool-ticket',
      version: '1.0.0',
      status: 'PUBLISHED',
      summary: '工单协同 Tool',
      createdAt: now(),
      publishedAt: now(),
      configuration: {
        type: 'TOOL',
        tool: {
          operations: [
            {
              name: 'create_ticket',
              description: '创建人工协同工单',
              inputSchema: '{"question":"string","operator":"string","comment":"string"}',
              outputSchema: '{"ticketId":"string","status":"string","detail":"string"}',
            },
          ],
          providerType: 'MCP',
          authType: 'NONE',
          timeoutSeconds: 30,
          retryPolicy: 'NONE',
          http: null,
          mcp: {
            serverName: 'ticketing-server',
            transport: 'STREAMABLE_HTTP',
            connectionUri: 'http://demo.local/mcp/ticketing',
            namespace: 'support.ticket',
            heartbeatSeconds: 30,
            operationMappings: {
              create_ticket: 'create_ticket',
            },
          },
        },
      },
    },
    effectiveVersion: null,
    versions: [],
  }),
];

for (const resourceItem of resources) {
  resourceItem.effectiveVersion = resourceItem.latestVersion;
  resourceItem.versions = [resourceItem.latestVersion!];
}

const agents: Agent[] = [
  {
    id: 'agent-router',
    assistantId: 'assistant-customer-ops',
    name: '问题分诊智能体',
    role: 'router',
    instructions: '识别问题类型，决定 FAQ、售后策略或人工协同分支。',
    executionPolicy: {
      inheritAssistantDefaults: true,
      modelResourceId: null,
      systemPrompt: '你是问题分诊智能体，负责判断当前问题应进入 FAQ、售后或人工协同路径。',
      ragEnabled: true,
      inheritAssistantKnowledge: true,
      knowledgeBaseId: null,
      memoryWindowSize: 8,
      skillResourceIds: ['resource-skill-router'],
      toolResourceIds: [],
    },
  },
  {
    id: 'agent-faq',
    assistantId: 'assistant-customer-ops',
    name: 'FAQ 回答智能体',
    role: 'faq',
    instructions: '基于知识检索结果输出最终 FAQ 回复。',
    executionPolicy: {
      inheritAssistantDefaults: true,
      modelResourceId: 'resource-llm-compatible',
      systemPrompt: '你是 FAQ 回答智能体，负责基于知识库给出直接回复。',
      ragEnabled: true,
      inheritAssistantKnowledge: true,
      knowledgeBaseId: null,
      memoryWindowSize: 8,
      skillResourceIds: ['resource-skill-faq'],
      toolResourceIds: [],
    },
  },
  {
    id: 'agent-policy',
    assistantId: 'assistant-customer-ops',
    name: '售后策略智能体',
    role: 'policy',
    instructions: '调用售后策略 Tool，给出退款或补偿结论。',
    executionPolicy: {
      inheritAssistantDefaults: true,
      modelResourceId: 'resource-llm-compatible',
      systemPrompt: '你是售后策略智能体，负责结合规则与工具结果给出处理建议。',
      ragEnabled: true,
      inheritAssistantKnowledge: false,
      knowledgeBaseId: 'knowledge-base-support',
      memoryWindowSize: 8,
      skillResourceIds: ['resource-skill-policy'],
      toolResourceIds: ['resource-tool-refund'],
    },
  },
  {
    id: 'agent-coordinator',
    assistantId: 'assistant-customer-ops',
    name: '人工协同闭环智能体',
    role: 'handoff',
    instructions: '在人工处理后整理摘要、调用工单 Tool，并生成闭环答复。',
    executionPolicy: {
      inheritAssistantDefaults: true,
      modelResourceId: 'resource-llm-compatible',
      systemPrompt: '你是人工协同闭环智能体，负责整理人工动作并生成最终回复。',
      ragEnabled: false,
      inheritAssistantKnowledge: true,
      knowledgeBaseId: null,
      memoryWindowSize: 12,
      skillResourceIds: ['resource-skill-handoff'],
      toolResourceIds: ['resource-tool-ticket'],
    },
  },
];

const orchestration: AssistantOrchestration = {
  assistantId: 'assistant-customer-ops',
  assistantName: '客服协同助手',
  scenarioId: 'scenario-customer-ops',
  executionMode: 'GRAPH',
  nodes: [
    { nodeKey: 'start', nodeName: '开始', nodeType: 'START', description: '接收用户消息。', agentId: null, humanNode: null },
    { nodeKey: 'route', nodeName: '问题分诊', nodeType: 'AGENT', description: '判断路由分支。', agentId: 'agent-router', humanNode: null },
    { nodeKey: 'faq', nodeName: 'FAQ 回答', nodeType: 'AGENT', description: '处理常规 FAQ。', agentId: 'agent-faq', humanNode: null },
    { nodeKey: 'policy', nodeName: '售后策略', nodeType: 'AGENT', description: '处理退款与补偿策略。', agentId: 'agent-policy', humanNode: null },
    {
      nodeKey: 'human-review',
      nodeName: '人工介入',
      nodeType: 'HUMAN',
      description: '等待人工确认或补充处理意见。',
      agentId: null,
      humanNode: {
        title: '人工介入待办',
        instruction: '请确认是否接管，并补充处理说明。',
        expectedAction: 'CONFIRM',
        resumeRouteKey: 'human-confirmed',
      },
    },
    { nodeKey: 'handoff-close', nodeName: '闭环总结', nodeType: 'AGENT', description: '人工处理后生成闭环答复。', agentId: 'agent-coordinator', humanNode: null },
    { nodeKey: 'end', nodeName: '结束', nodeType: 'END', description: '流程结束。', agentId: null, humanNode: null },
  ],
  edges: [
    { edgeKey: 'edge-start-route', sourceNodeKey: 'start', targetNodeKey: 'route', routeKey: 'default', label: '开始处理', defaultEdge: true },
    { edgeKey: 'edge-route-faq', sourceNodeKey: 'route', targetNodeKey: 'faq', routeKey: 'faq', label: '进入 FAQ 分支', defaultEdge: false },
    { edgeKey: 'edge-route-policy', sourceNodeKey: 'route', targetNodeKey: 'policy', routeKey: 'after_sales', label: '进入售后分支', defaultEdge: false },
    { edgeKey: 'edge-route-human', sourceNodeKey: 'route', targetNodeKey: 'human-review', routeKey: 'human_handoff', label: '直接人工介入', defaultEdge: false },
    { edgeKey: 'edge-route-fallback', sourceNodeKey: 'route', targetNodeKey: 'faq', routeKey: 'default', label: '默认走 FAQ', defaultEdge: true },
    { edgeKey: 'edge-faq-end', sourceNodeKey: 'faq', targetNodeKey: 'end', routeKey: 'default', label: 'FAQ 结束', defaultEdge: true },
    { edgeKey: 'edge-policy-end', sourceNodeKey: 'policy', targetNodeKey: 'end', routeKey: 'resolved', label: '售后自动完成', defaultEdge: false },
    { edgeKey: 'edge-policy-human', sourceNodeKey: 'policy', targetNodeKey: 'human-review', routeKey: 'manual_review', label: '售后转人工', defaultEdge: false },
    { edgeKey: 'edge-policy-default', sourceNodeKey: 'policy', targetNodeKey: 'end', routeKey: 'default', label: '默认完成', defaultEdge: true },
    { edgeKey: 'edge-human-handoff', sourceNodeKey: 'human-review', targetNodeKey: 'handoff-close', routeKey: 'human-confirmed', label: '人工确认后闭环', defaultEdge: true },
    { edgeKey: 'edge-close-end', sourceNodeKey: 'handoff-close', targetNodeKey: 'end', routeKey: 'default', label: '闭环完成', defaultEdge: true },
  ],
};

const currentRelease: AssistantRelease = {
  id: 'assistant-release-customer-ops-v1',
  assistantId: 'assistant-customer-ops',
  releaseVersion: '1.0.0',
  status: 'PUBLISHED',
  createdAt: now(),
  publishedAt: now(),
  assistantKnowledge: knowledgeBinding,
  resources: resources.map((item) => ({
    resourceId: item.id,
    resourceName: item.name,
    resourceType: item.type,
    resourceVersionId: item.effectiveVersion!.id,
    resourceVersion: item.effectiveVersion!.version,
    boundAgents: item.id === 'resource-tool-refund'
      ? ['售后策略智能体']
      : item.id === 'resource-tool-ticket'
        ? ['人工协同闭环智能体']
        : item.type === 'SKILL'
          ? ['问题分诊智能体', 'FAQ 回答智能体', '售后策略智能体', '人工协同闭环智能体']
          : [],
    configuration: item.effectiveVersion!.configuration,
  })),
  agents: agents.map((agent) => ({
    agentId: agent.id,
    name: agent.name,
    role: agent.role,
    instructions: agent.instructions,
    executionPolicy: agent.executionPolicy,
    knowledge: agent.id === 'agent-policy' ? knowledgeBinding : agent.executionPolicy.ragEnabled ? knowledgeBinding : null,
    skillResourceVersionIds: agent.executionPolicy.skillResourceIds.map((resourceId) => resources.find((item) => item.id === resourceId)!.effectiveVersion!.id),
    toolResourceVersionIds: agent.executionPolicy.toolResourceIds.map((resourceId) => resources.find((item) => item.id === resourceId)!.effectiveVersion!.id),
  })),
  orchestration,
  modelPolicy: {
    providerResourceId: 'resource-llm-compatible',
  },
  ragPolicy: {
    enabled: true,
    knowledgeBaseId: knowledgeBase.id,
  },
  memoryPolicy: {
    enabled: true,
    windowSize: 10,
  },
};

const assistant: Assistant = {
  id: 'assistant-customer-ops',
  scenarioId: 'scenario-customer-ops',
  name: '客服协同助手',
  description: '负责问题分诊、知识回答、售后策略和人工协同闭环。',
  version: version('PUBLISHED', '1.0.0'),
  agents,
  currentRelease,
  releases: [currentRelease],
  modelPolicy: currentRelease.modelPolicy,
  ragPolicy: currentRelease.ragPolicy,
  memoryPolicy: currentRelease.memoryPolicy,
};

const scenario: Scenario = {
  id: 'scenario-customer-ops',
  domainId: 'domain-support',
  name: '智能客服协同处理',
  goal: '在单助手内完成 FAQ、售后策略和人工协同闭环',
  version: version('PUBLISHED', '1.0.0'),
  assistants: [assistant],
};

const domain: BusinessDomain = {
  id: 'domain-support',
  name: '智能客服域',
  description: '用于多智能体客服编排的演示业务域',
  scenarios: [scenario],
  resources,
  knowledgeBases: [knowledgeBase],
};

const resourceBlueprints: ResourceBlueprint[] = [
  {
    type: 'TOOL',
    label: 'Tool',
    description: '管理 agent 可调用能力，并为其配置 HTTP 或 MCP provider。',
    maintainedFields: ['操作定义', 'Provider 类型', '鉴权方式', '超时设置', '重试策略', 'Provider 配置'],
    defaultConfiguration: resources.find((item) => item.type === 'TOOL')!.effectiveVersion!.configuration,
  },
  {
    type: 'LLM_MODEL',
    label: 'LLM 模型',
    description: '管理模型供应商、连接与默认参数。',
    maintainedFields: ['供应商类型', '模型 ID', 'Base URL', 'API Key 环境变量'],
    defaultConfiguration: resources.find((item) => item.type === 'LLM_MODEL')!.effectiveVersion!.configuration,
  },
  {
    type: 'SKILL',
    label: 'Skill',
    description: '管理供智能体按需读取的行为模式说明。',
    maintainedFields: ['技能名称', '技能描述', '技能提示'],
    defaultConfiguration: resources.find((item) => item.type === 'SKILL')!.effectiveVersion!.configuration,
  },
];

const resourceCenter: ResourceCenter = {
  totalResources: resources.length,
  domainSharedResources: resources.filter((item) => item.shareScope === 'DOMAIN_SHARED').length,
  privateResources: resources.filter((item) => item.shareScope === 'PRIVATE').length,
  references: [
    {
      resourceId: 'resource-llm-compatible',
      resourceName: '自定义兼容模型',
      type: 'LLM_MODEL',
      shareScope: 'DOMAIN_SHARED',
      ownerLabel: 'DOMAIN:domain-support',
      latestVersion: '1.0.0',
      effectiveVersion: '1.0.0',
      referenceKind: 'ASSISTANT_DEFAULT_MODEL',
      sourceType: 'ASSISTANT',
      sourceId: 'assistant-customer-ops',
      sourceName: '客服协同助手',
      resourceVersionId: null,
      resourceVersion: null,
      blocksDeletion: true,
    },
  ],
};

export const mockCatalogSummary: CatalogSummary = {
  domains: [domain],
  scenarios: [scenario],
  assistants: [assistant],
  agents,
  resources,
  knowledgeBases: [knowledgeBase],
  orchestrations: [orchestration],
  resourceCenter,
  resourceBlueprints,
};

const waitingToolOutcome: ToolOutcomeSummary = {
  toolResourceId: 'resource-tool-ticket',
  toolResourceName: '工单协同 Tool',
  operation: 'create_ticket',
  providerType: 'MCP',
  status: 'ACCEPTED',
  externalReference: 'TICKET-10001',
  recommendedAction: 'HUMAN_HANDOFF',
  detail: '已创建人工协同工单。',
};

const waitingHumanTask: HumanTaskSnapshot = {
  nodeKey: 'human-review',
  title: '人工介入待办',
  instruction: '请人工确认客户诉求、补偿方案和回复口径。',
  expectedAction: '补充处理意见并确认后续动作',
  source: 'GRAPH_NODE',
  allowedActions: ['CONFIRM', 'TERMINATE'],
};

const waitingPauseReason = {
  code: 'GRAPH_HUMAN_NODE',
  detail: '流程已运行到人工节点，等待人工确认。',
  source: 'GRAPH_NODE' as const,
};

export const mockWorkflows: WorkflowInstance[] = [
  {
    id: 'wf-10001',
    taskId: 'task-10001',
    assistantId: assistant.id,
    assistantName: assistant.name,
    assistantReleaseVersion: assistant.currentRelease!.releaseVersion,
    createdAt: now(),
    updatedAt: now(),
    status: 'WAITING_HUMAN',
    summary: '流程已运行到人工节点，等待人工确认。',
    finalReply: null,
    currentNodeKey: 'human-review',
    escalationRequired: true,
    checkpoint: {
      checkpointId: 'cp-10001',
      currentNodeKey: 'handoff-close',
      waitingNodeKey: 'human-review',
      statePayload: JSON.stringify({ question: '客户投诉并要求退款，需要人工处理' }),
      resumeCount: 0,
    },
    humanTask: waitingHumanTask,
    pauseReason: waitingPauseReason,
    latestToolOutcome: waitingToolOutcome,
    resourceAnchors: ['客服知识库@1.0.0', '售后策略 Tool@1.0.0', '工单协同 Tool@1.0.0'],
    nodes: [
      { id: 'node-1', workflowInstanceId: 'wf-10001', nodeKey: 'start', nodeName: '开始', status: 'COMPLETED', detail: '会话消息已进入编排。', updatedAt: now() },
      { id: 'node-2', workflowInstanceId: 'wf-10001', nodeKey: 'route', nodeName: '问题分诊', status: 'COMPLETED', detail: '识别为投诉升级问题。', updatedAt: now() },
      { id: 'node-3', workflowInstanceId: 'wf-10001', nodeKey: 'human-review', nodeName: '人工介入', status: 'WAITING_HUMAN', detail: '等待人工接管。', updatedAt: now() },
    ],
    toolCalls: [],
    interventions: [],
    loadedSkillResourceVersionIds: [],
  },
];

export const mockTasks: TaskInstance[] = [
  {
    id: 'task-10001',
    scenarioId: scenario.id,
    assistantId: assistant.id,
    assistantName: assistant.name,
    assistantReleaseVersion: assistant.currentRelease!.releaseVersion,
    question: '客户投诉并要求退款，需要人工处理',
    requester: 'tester',
    status: 'WAITING_HUMAN',
    createdAt: now(),
    workflowInstanceId: 'wf-10001',
  },
];

export const mockSession: UserSession = {
  userId: 'user-demo',
  displayName: '演示用户',
  currentRole: 'PLATFORM_ADMIN',
  availableRoles: ['PLATFORM_ADMIN', 'DOMAIN_ADMIN', 'DEVELOPER', 'BUSINESS_USER'],
};

export const mockConversationSessions: ConversationSession[] = [];
