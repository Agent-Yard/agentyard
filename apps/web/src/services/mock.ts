import type {
  Agent,
  AgentGroup,
  AgentOrchestration,
  BusinessDomain,
  CatalogSummary,
  Resource,
  ResourceCenter,
  Scenario,
  TaskInstance,
  UserSession,
  WorkflowInstance,
} from '../types';

const session: UserSession = {
  userId: 'u-demo-domain-admin',
  displayName: 'Lynxus Demo User',
  currentRole: 'DOMAIN_ADMIN',
  availableRoles: ['PLATFORM_ADMIN', 'DOMAIN_ADMIN', 'DEVELOPER', 'BUSINESS_USER'],
};

const resources: Resource[] = [
  {
    id: 'resource-kb-support',
    domainId: 'domain-support',
    name: '客服知识库',
    type: 'KNOWLEDGE_BASE',
    shareScope: 'DOMAIN_SHARED',
    ownerType: 'DOMAIN',
    ownerId: 'domain-support',
    summary: '用于常见问题检索的知识库',
  },
  {
    id: 'resource-skill-answer',
    domainId: 'domain-support',
    name: '答案生成 Skill',
    type: 'SKILL',
    shareScope: 'PRIVATE',
    ownerType: 'AGENT_GROUP',
    ownerId: 'agent-group-knowledge-escalation',
    summary: '根据检索结果生成可发送答案',
  },
];

const agents: Agent[] = [
  {
    id: 'agent-router',
    agentGroupId: 'agent-group-knowledge-escalation',
    name: '问题路由智能体',
    role: 'router',
    instructions: '识别问题类型，决定直接回答还是进入升级流程',
    bindings: [
      {
        id: 'binding-kb-router',
        resourceId: 'resource-kb-support',
        consumerType: 'AGENT',
        consumerId: 'agent-router',
        createdAt: new Date().toISOString(),
      },
    ],
  },
  {
    id: 'agent-responder',
    agentGroupId: 'agent-group-knowledge-escalation',
    name: '回答生成智能体',
    role: 'responder',
    instructions: '根据知识库结果生成结构化答案',
    bindings: [
      {
        id: 'binding-skill-responder',
        resourceId: 'resource-skill-answer',
        consumerType: 'AGENT',
        consumerId: 'agent-responder',
        createdAt: new Date().toISOString(),
      },
    ],
  },
];

const agentGroups: AgentGroup[] = [
  {
    id: 'agent-group-knowledge-escalation',
    scenarioId: 'scenario-knowledge-escalation',
    name: '问答升级智能体组',
    description: '负责知识检索、答案生成和升级判定',
    version: { version: '0.1.0', status: 'PUBLISHED', updatedAt: new Date().toISOString() },
    agents,
  },
];

const scenarios: Scenario[] = [
  {
    id: 'scenario-knowledge-escalation',
    domainId: 'domain-support',
    name: '知识问答升级处理',
    goal: '回答常见问题，复杂问题自动升级给人工坐席',
    version: { version: '0.1.0', status: 'PUBLISHED', updatedAt: new Date().toISOString() },
    agentGroups,
  },
];

const domains: BusinessDomain[] = [
  {
    id: 'domain-support',
    name: '智能客服域',
    description: '用于知识问答与升级处理的 MVP 业务域',
    scenarios,
    resources,
  },
];

const tasks: TaskInstance[] = [
  {
    id: 'task-1',
    scenarioId: 'scenario-knowledge-escalation',
    question: '怎么重置密码？',
    requester: '业务用户A',
    status: 'COMPLETED',
    createdAt: new Date().toISOString(),
    workflowInstanceId: 'wf-1',
  },
  {
    id: 'task-2',
    scenarioId: 'scenario-knowledge-escalation',
    question: '这是一个客户投诉，需要人工处理',
    requester: '业务用户B',
    status: 'WAITING_HUMAN',
    createdAt: new Date().toISOString(),
    workflowInstanceId: 'wf-2',
  },
];

const workflows: WorkflowInstance[] = [
  {
    id: 'wf-1',
    taskId: 'task-1',
    status: 'COMPLETED',
    summary: '基于知识库结果的回答已发送。',
    escalationRequired: false,
    nodes: [
      { id: 'n-1', workflowInstanceId: 'wf-1', nodeKey: 'question-received', nodeName: '问题接收', status: 'COMPLETED', detail: '收到问题', updatedAt: new Date().toISOString() },
      { id: 'n-2', workflowInstanceId: 'wf-1', nodeKey: 'knowledge-retrieval', nodeName: '知识检索', status: 'COMPLETED', detail: '召回 3 条知识', updatedAt: new Date().toISOString() },
      { id: 'n-3', workflowInstanceId: 'wf-1', nodeKey: 'answer-generation', nodeName: '回答生成', status: 'COMPLETED', detail: '生成答案成功', updatedAt: new Date().toISOString() },
      { id: 'n-4', workflowInstanceId: 'wf-1', nodeKey: 'escalation-decision', nodeName: '升级判定', status: 'COMPLETED', detail: '无需升级', updatedAt: new Date().toISOString() },
    ],
    interventions: [],
  },
  {
    id: 'wf-2',
    taskId: 'task-2',
    status: 'WAITING_HUMAN',
    summary: '命中投诉关键词，等待人工处理。',
    escalationRequired: true,
    nodes: [
      { id: 'n-5', workflowInstanceId: 'wf-2', nodeKey: 'question-received', nodeName: '问题接收', status: 'COMPLETED', detail: '收到问题', updatedAt: new Date().toISOString() },
      { id: 'n-6', workflowInstanceId: 'wf-2', nodeKey: 'knowledge-retrieval', nodeName: '知识检索', status: 'COMPLETED', detail: '召回 2 条知识', updatedAt: new Date().toISOString() },
      { id: 'n-7', workflowInstanceId: 'wf-2', nodeKey: 'answer-generation', nodeName: '回答生成', status: 'COMPLETED', detail: '生成初步答案', updatedAt: new Date().toISOString() },
      { id: 'n-8', workflowInstanceId: 'wf-2', nodeKey: 'escalation-decision', nodeName: '升级判定', status: 'WAITING_HUMAN', detail: '等待人工接管', updatedAt: new Date().toISOString() },
    ],
    interventions: [
      {
        id: 'h-1',
        workflowInstanceId: 'wf-2',
        action: 'WAIT_CONFIRM',
        operator: 'system',
        comment: '等待人工确认',
        createdAt: new Date().toISOString(),
      },
    ],
  },
];

export const mockCatalogSummary: CatalogSummary = {
  domains,
  scenarios,
  agentGroups,
  agents,
  resources,
  orchestrations: [
    {
      agentGroupId: 'agent-group-knowledge-escalation',
      agentGroupName: '问答升级智能体组',
      scenarioId: 'scenario-knowledge-escalation',
      executionMode: 'SEQUENTIAL_GRAPH',
      nodes: [
        {
          nodeId: 'node-agent-router',
          nodeName: '问题路由智能体',
          nodeType: 'AGENT',
          agentId: 'agent-router',
          description: '识别问题类型，决定路由方向',
          resourceIds: ['resource-kb-support'],
        },
        {
          nodeId: 'node-agent-responder',
          nodeName: '回答生成智能体',
          nodeType: 'AGENT',
          agentId: 'agent-responder',
          description: '根据检索结果生成答案',
          resourceIds: ['resource-skill-answer'],
        },
        {
          nodeId: 'node-agent-escalation',
          nodeName: '升级判定智能体',
          nodeType: 'AGENT',
          agentId: 'agent-escalation',
          description: '判断是否需要转人工',
          resourceIds: [],
        },
      ],
      edges: [
        {
          edgeId: 'edge-1',
          fromNodeId: 'node-agent-router',
          toNodeId: 'node-agent-responder',
          condition: '标准问答链路',
          handoffPolicy: 'direct-handoff',
        },
        {
          edgeId: 'edge-2',
          fromNodeId: 'node-agent-responder',
          toNodeId: 'node-agent-escalation',
          condition: '回答生成完成',
          handoffPolicy: 'conditional-handoff',
        },
      ],
    },
  ] satisfies AgentOrchestration[],
  resourceCenter: {
    totalResources: 2,
    domainSharedResources: 1,
    privateResources: 1,
    usages: [
      {
        resourceId: 'resource-kb-support',
        resourceName: '客服知识库',
        type: 'KNOWLEDGE_BASE',
        shareScope: 'DOMAIN_SHARED',
        ownerLabel: 'DOMAIN:domain-support',
        boundAgents: ['问题路由智能体'],
        boundAgentGroups: ['问答升级智能体组'],
      },
      {
        resourceId: 'resource-skill-answer',
        resourceName: '答案生成 Skill',
        type: 'SKILL',
        shareScope: 'PRIVATE',
        ownerLabel: 'AGENT_GROUP:agent-group-knowledge-escalation',
        boundAgents: ['回答生成智能体'],
        boundAgentGroups: ['问答升级智能体组'],
      },
    ],
  } satisfies ResourceCenter,
};

export const mockTasks = tasks;
export const mockWorkflows = workflows;
export const mockSession = session;
