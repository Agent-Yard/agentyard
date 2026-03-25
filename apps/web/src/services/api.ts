import type {
  Agent,
  Assistant,
  BusinessDomain,
  CatalogSummary,
  ConversationMessagePayload,
  ConversationSession,
  CreateAssistantPayload,
  CreateAgentPayload,
  CreateConversationSessionPayload,
  CreateDomainPayload,
  CreateResourcePayload,
  CreateResourceVersionPayload,
  CreateScenarioPayload,
  KnowledgeBaseConfig,
  KnowledgeBaseDocument,
  ResourceType,
  ResourceVersionConfiguration,
  ToolConfig,
  ToolOperation,
  OrchestrationEdge,
  OrchestrationNode,
  Resource,
  ResourceVersion,
  Role,
  Scenario,
  TaskInstance,
  UpdateAssistantPayload,
  UpdateAgentPayload,
  UpdateDomainPayload,
  UpdateOrchestrationPayload,
  UpdateScenarioPayload,
  UserSession,
  WorkflowInstance,
} from '../types';
import { mockCatalogSummary, mockSession, mockTasks, mockWorkflows } from './mock';

const API_BASE = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? 'http://localhost:8080/api';
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;

const fallbackState = {
  session: clone(mockSession),
  catalog: clone(mockCatalogSummary),
  tasks: clone(mockTasks),
  workflows: clone(mockWorkflows),
  sessions: [] as ConversationSession[],
};

function rebuildCatalogState() {
  const catalog = fallbackState.catalog;
  const agentsByAssistant = new Map<string, Agent[]>();
  for (const agent of catalog.agents) {
    const current = agentsByAssistant.get(agent.assistantId) ?? [];
    current.push(agent);
    agentsByAssistant.set(agent.assistantId, current);
  }

  catalog.assistants = catalog.assistants.map((assistant) => ({
    ...assistant,
    agents: (agentsByAssistant.get(assistant.id) ?? []).slice(),
  }));

  catalog.scenarios = catalog.scenarios.map((scenario) => ({
    ...scenario,
    assistants: catalog.assistants.filter((assistant) => assistant.scenarioId === scenario.id),
  }));

  catalog.domains = catalog.domains.map((domain) => ({
    ...domain,
    scenarios: catalog.scenarios.filter((scenario) => scenario.domainId === domain.id),
    resources: catalog.resources.filter((resource) => resource.domainId === domain.id),
  }));

  catalog.orchestrations = catalog.orchestrations.map((orchestration) => {
    const assistant = catalog.assistants.find((item) => item.id === orchestration.assistantId);
    return {
      ...orchestration,
      assistantId: assistant?.id ?? '',
      assistantName: assistant?.name ?? orchestration.assistantName,
      scenarioId: assistant?.scenarioId ?? '',
      nodes: orchestration.nodes.map((node) => ({ ...node })),
    };
  });

  catalog.resourceCenter = {
    totalResources: catalog.resources.length,
    domainSharedResources: catalog.resources.filter((resource) => resource.shareScope === 'DOMAIN_SHARED').length,
    privateResources: catalog.resources.filter((resource) => resource.shareScope === 'PRIVATE').length,
    references: catalog.resources.flatMap((resource) => buildResourceReferenceEntries(resource)),
  };
}

function nextId(prefix: string): string {
  return `${prefix}-${Math.random().toString(16).slice(2, 10)}`;
}

function defaultKnowledgeBaseConfig(): KnowledgeBaseConfig {
  return {
    defaultTopK: 5,
    documents: [],
  };
}

function normalizeKnowledgeBaseDocuments(documents?: KnowledgeBaseDocument[] | null): KnowledgeBaseDocument[] {
  return (documents ?? [])
    .map((document, index) => {
      const content = document.content.trim();
      if (!content) {
        return null;
      }
      const title = document.title.trim() || content.slice(0, 24) || `文档 ${index + 1}`;
      return {
        id: document.id?.trim() || nextId('kb-doc'),
        title,
        content,
        sourceUri: document.sourceUri?.trim() || '',
      };
    })
    .filter((document): document is KnowledgeBaseDocument => document !== null);
}

function normalizeKnowledgeBaseConfig(configuration?: KnowledgeBaseConfig | null): KnowledgeBaseConfig {
  const defaults = defaultKnowledgeBaseConfig();
  const documents = normalizeKnowledgeBaseDocuments(configuration?.documents);
  return {
    defaultTopK: configuration?.defaultTopK && configuration.defaultTopK > 0 ? configuration.defaultTopK : defaults.defaultTopK,
    documents,
  };
}

function defaultToolOperations(): ToolOperation[] {
  return [
    {
      name: 'invoke',
      description: '执行通用工具动作',
      inputSchema: '{"input":"string"}',
      outputSchema: '{"output":"string"}',
    },
  ];
}

function defaultToolConfig(): ToolConfig {
  return {
    operations: defaultToolOperations(),
    providerType: 'HTTP',
    authType: 'SERVICE_ACCOUNT',
    timeoutSeconds: 15,
    retryPolicy: 'NONE',
    http: {
      endpoint: 'https://tool-gateway.internal/new-tool',
      method: 'POST',
    },
    mcp: null,
  };
}

function normalizeToolOperations(operations?: ToolOperation[] | null): ToolOperation[] {
  return (operations ?? [])
    .map((operation) => {
      const name = operation.name.trim();
      if (!name) {
        return null;
      }
      return {
        name,
        description: operation.description?.trim() || '',
        inputSchema: operation.inputSchema?.trim() || '',
        outputSchema: operation.outputSchema?.trim() || '',
      };
    })
    .filter((operation): operation is ToolOperation => operation !== null);
}

function normalizeToolConfig(configuration?: ToolConfig | null): ToolConfig {
  const defaults = defaultToolConfig();
  const providerType = configuration?.providerType ?? defaults.providerType;
  const operations = normalizeToolOperations(configuration?.operations);
  return {
    operations: operations.length ? operations : defaults.operations,
    providerType,
    authType: configuration?.authType ?? defaults.authType,
    timeoutSeconds: configuration?.timeoutSeconds && configuration.timeoutSeconds > 0 ? configuration.timeoutSeconds : defaults.timeoutSeconds,
    retryPolicy: configuration?.retryPolicy?.trim() || defaults.retryPolicy,
    http: providerType === 'HTTP'
      ? {
          endpoint: configuration?.http?.endpoint?.trim() || defaults.http!.endpoint,
          method: configuration?.http?.method || defaults.http!.method,
        }
      : null,
    mcp: providerType === 'MCP'
      ? {
          serverName: configuration?.mcp?.serverName?.trim() || 'new-mcp-server',
          transport: configuration?.mcp?.transport || 'STREAMABLE_HTTP',
          connectionUri: configuration?.mcp?.connectionUri?.trim() || 'https://mcp-gateway.internal/new-server',
          namespace: configuration?.mcp?.namespace?.trim() || 'default.namespace',
          heartbeatSeconds: configuration?.mcp?.heartbeatSeconds && configuration.mcp.heartbeatSeconds > 0 ? configuration.mcp.heartbeatSeconds : 30,
          operationMappings: Object.fromEntries((operations.length ? operations : defaults.operations).map((operation) => [
            operation.name,
            configuration?.mcp?.operationMappings?.[operation.name]?.trim() || operation.name,
          ])),
        }
      : null,
  };
}

function defaultConfiguration(type: ResourceType): ResourceVersionConfiguration {
  if (type === 'KNOWLEDGE_BASE') {
    return {
      type,
      knowledgeBase: defaultKnowledgeBaseConfig(),
    };
  }
  if (type === 'TOOL') {
    return {
      type,
      tool: defaultToolConfig(),
    };
  }
  if (type === 'LLM_MODEL') {
    return {
      type,
      llmModel: {
        providerType: 'OPENAI',
        modelId: 'gpt-4.1-mini',
        baseUrl: 'https://api.openai.com/v1',
        apiKeyEnvVar: 'OPENAI_API_KEY',
        organization: 'lynxus-demo',
        project: 'default-project',
        region: 'global',
        temperature: 0.2,
        maxTokens: 1200,
      },
    };
  }
  return {
    type,
    promptTemplate: {
      templateType: 'CHAT',
      systemPrompt: '你是企业级智能体，请根据上下文输出可执行答案。',
      userPromptTemplate: '用户问题：{{question}}\n知识上下文：{{knowledge_context}}',
      responseFormat: 'markdown',
    },
  };
}

function normalizeConfiguration(type: ResourceType, configuration?: ResourceVersionConfiguration): ResourceVersionConfiguration {
  if (!configuration) {
    return defaultConfiguration(type);
  }
  if (type === 'KNOWLEDGE_BASE') {
    return {
      type,
      knowledgeBase: normalizeKnowledgeBaseConfig(configuration.knowledgeBase),
    };
  }
  if (type === 'TOOL') {
    return {
      type,
      tool: normalizeToolConfig(configuration.tool),
    };
  }
  if (type === 'LLM_MODEL') {
    return {
      type,
      llmModel: configuration.llmModel ?? defaultConfiguration(type).llmModel,
    };
  }
  return {
    type,
    promptTemplate: configuration.promptTemplate ?? defaultConfiguration(type).promptTemplate,
  };
}

function findAssistant(assistantId: string): Assistant {
  const assistant = fallbackState.catalog.assistants.find((item) => item.id === assistantId);
  if (!assistant) {
    throw new Error(`Assistant ${assistantId} not found`);
  }
  return assistant;
}

function findDomain(domainId: string): BusinessDomain {
  const domain = fallbackState.catalog.domains.find((item) => item.id === domainId);
  if (!domain) {
    throw new Error(`Domain ${domainId} not found`);
  }
  return domain;
}

function buildAssistantRelease(assistant: Assistant, releaseVersion: string) {
  const orchestration = clone(findOrchestration(assistant.id));
  const agentSnapshots = assistant.agents.map((agent) => ({
    agentId: agent.id,
    name: agent.name,
    role: agent.role,
    instructions: agent.instructions,
    executionPolicy: clone(agent.executionPolicy),
    toolResourceVersionIds: agent.executionPolicy.toolResourceIds
      .map((resourceId) => resolveResourceVersion(resourceId).version.id),
  }));
  const releaseResources = collectReleaseResources(assistant);
  return {
    id: nextId('assistant-release'),
    assistantId: assistant.id,
    releaseVersion,
    status: 'PUBLISHED' as const,
    createdAt: new Date().toISOString(),
    publishedAt: new Date().toISOString(),
    resources: releaseResources,
    agents: agentSnapshots,
    orchestration,
    modelPolicy: clone(assistant.modelPolicy),
    ragPolicy: clone(assistant.ragPolicy),
    memoryPolicy: clone(assistant.memoryPolicy),
  };
}

function findAgent(agentId: string): Agent {
  const agent = fallbackState.catalog.agents.find((item) => item.id === agentId);
  if (!agent) {
    throw new Error(`Agent ${agentId} not found`);
  }
  return agent;
}

function findScenario(scenarioId: string): Scenario {
  const scenario = fallbackState.catalog.scenarios.find((item) => item.id === scenarioId);
  if (!scenario) {
    throw new Error(`Scenario ${scenarioId} not found`);
  }
  return scenario;
}

function findResource(resourceId: string): Resource {
  const resource = fallbackState.catalog.resources.find((item) => item.id === resourceId);
  if (!resource) {
    throw new Error(`Resource ${resourceId} not found`);
  }
  return resource;
}

function findResourceVersion(resourceId: string, versionId: string): ResourceVersion {
  const version = findResource(resourceId).versions.find((item) => item.id === versionId);
  if (!version) {
    throw new Error(`Resource version ${resourceId}/${versionId} not found`);
  }
  return version;
}

function findSession(sessionId: string): ConversationSession {
  const session = fallbackState.sessions.find((item) => item.id === sessionId);
  if (!session) {
    throw new Error(`Session ${sessionId} not found`);
  }
  return session;
}

function findOrchestration(assistantId: string) {
  const orchestration = fallbackState.catalog.orchestrations.find((item) => item.assistantId === assistantId);
  if (orchestration) {
    return orchestration;
  }
  const assistant = findAssistant(assistantId);
  return {
    assistantId,
    assistantName: assistant.name,
    scenarioId: assistant.scenarioId,
    executionMode: 'GRAPH',
    nodes: [
      {
        nodeKey: 'start',
        nodeName: '开始',
        nodeType: 'START' as const,
        description: '会话开始',
        agentId: null,
        humanNode: null,
      },
      {
        nodeKey: 'end',
        nodeName: '结束',
        nodeType: 'END' as const,
        description: '流程结束',
        agentId: null,
        humanNode: null,
      },
    ],
    edges: [
      {
        edgeKey: 'edge-start-end',
        sourceNodeKey: 'start',
        targetNodeKey: 'end',
        routeKey: null,
        label: '默认结束',
        defaultEdge: true,
      },
    ],
  };
}

function buildDefaultOrchestrationForAssistant(assistantId: string) {
  const assistant = findAssistant(assistantId);
  const assistantAgents = fallbackState.catalog.agents
    .filter((item) => item.assistantId === assistantId)
    .sort((left, right) => left.name.localeCompare(right.name));
  const nodes: OrchestrationNode[] = [
    {
      nodeKey: 'start',
      nodeName: '开始',
      nodeType: 'START',
      description: '会话开始',
      agentId: null,
      humanNode: null,
    },
    ...assistantAgents.map((agent) => ({
      nodeKey: `node-${agent.id}`,
      nodeName: agent.name,
      nodeType: 'AGENT' as const,
      description: agent.executionPolicy.inlinePrompt || agent.instructions,
      agentId: agent.id,
      humanNode: null,
    })),
    {
      nodeKey: 'end',
      nodeName: '结束',
      nodeType: 'END',
      description: '流程结束',
      agentId: null,
      humanNode: null,
    },
  ];
  const edges: OrchestrationEdge[] = nodes.slice(0, -1).map((node, index) => ({
    edgeKey: `edge-${node.nodeKey}-${nodes[index + 1].nodeKey}`,
    sourceNodeKey: node.nodeKey,
    targetNodeKey: nodes[index + 1].nodeKey,
    routeKey: 'default',
    label: node.nodeType === 'START' ? '开始处理' : '默认流转',
    defaultEdge: true,
  }));
  return {
    assistantId,
    assistantName: assistant.name,
    scenarioId: assistant.scenarioId,
    executionMode: 'GRAPH',
    nodes,
    edges,
  };
}

function validateResourceOwner(domainId: string, ownerType: string, ownerId: string) {
  if (ownerType === 'DOMAIN') {
    if (domainId !== ownerId) {
      throw new Error('资源归属为业务域时，归属对象必须等于当前业务域');
    }
    return;
  }
  if (ownerType !== 'ASSISTANT') {
    throw new Error('资源归属类型仅支持业务域或助手');
  }
  const assistant = findAssistant(ownerId);
  const scenario = findScenario(assistant.scenarioId);
  if (scenario.domainId !== domainId) {
    throw new Error('助手私有资源只能归属到当前业务域内的助手');
  }
}

function resolveResourceVersion(resourceId: string, preferredVersionId?: string | null) {
  const resource = findResource(resourceId);
  const version = (preferredVersionId
    ? resource.versions.find((item) => item.id === preferredVersionId)
    : null) ?? resource.effectiveVersion ?? resource.latestVersion ?? resource.versions[0];
  if (!version) {
    throw new Error(`Resource ${resourceId} has no versions`);
  }
  return { resource, version };
}

function findResourceDeletionBlocker(resourceId: string): string | null {
  const resource = findResource(resourceId);
  return buildResourceReferenceEntries(resource)
    .filter((reference) => reference.blocksDeletion)
    .map((reference) => {
      switch (reference.referenceKind) {
        case 'ASSISTANT_DEFAULT_MODEL':
          return `资源仍被助手默认模型引用：${reference.sourceName}`;
        case 'ASSISTANT_DEFAULT_PROMPT':
          return `资源仍被助手默认 Prompt 引用：${reference.sourceName}`;
        case 'ASSISTANT_DEFAULT_KNOWLEDGE_BASE':
          return `资源仍被助手默认知识库引用：${reference.sourceName}`;
        case 'AGENT_OVERRIDE_MODEL':
          return `资源仍被智能体模型覆盖引用：${reference.sourceName}`;
        case 'AGENT_OVERRIDE_PROMPT':
          return `资源仍被智能体 Prompt 覆盖引用：${reference.sourceName}`;
        case 'AGENT_OVERRIDE_KNOWLEDGE_BASE':
          return `资源仍被智能体知识库覆盖引用：${reference.sourceName}`;
        case 'AGENT_TOOL_ENABLED':
          return `资源仍被智能体 Tool 集引用：${reference.sourceName}`;
        default:
          return `资源仍被引用：${reference.sourceName}`;
      }
    })[0] ?? null;
}

function findResourceVersionDeletionBlocker(versionId: string): string | null {
  for (const resource of fallbackState.catalog.resources) {
    const blocker = buildResourceReferenceEntries(resource)
      .find((reference) => reference.blocksDeletion && reference.resourceVersionId === versionId);
    if (blocker) {
      return `资源版本仍被智能体工具固定：${blocker.sourceName}`;
    }
  }
  return null;
}

function buildResourceReferenceEntries(resource: Resource) {
  const entries = [] as CatalogSummary['resourceCenter']['references'];
  const ownerLabel = `${resource.ownerType}:${resource.ownerId}`;
  const latestVersion = resource.latestVersion?.version ?? null;
  const effectiveVersion = resource.effectiveVersion?.version ?? null;
  const pushEntry = (
    referenceKind: string,
    sourceType: string,
    sourceId: string,
    sourceName: string,
    resourceVersionId: string | null,
    resourceVersion: string | null,
    blocksDeletion: boolean,
  ) => {
    entries.push({
      resourceId: resource.id,
      resourceName: resource.name,
      type: resource.type,
      shareScope: resource.shareScope,
      ownerLabel,
      latestVersion,
      effectiveVersion,
      referenceKind,
      sourceType,
      sourceId,
      sourceName,
      resourceVersionId,
      resourceVersion,
      blocksDeletion,
    });
  };

  for (const assistant of fallbackState.catalog.assistants) {
    if (assistant.modelPolicy.providerResourceId === resource.id) {
      pushEntry('ASSISTANT_DEFAULT_MODEL', 'ASSISTANT', assistant.id, assistant.name, null, null, true);
    }
    if (assistant.modelPolicy.promptTemplateResourceId === resource.id) {
      pushEntry('ASSISTANT_DEFAULT_PROMPT', 'ASSISTANT', assistant.id, assistant.name, null, null, true);
    }
    if (assistant.ragPolicy.enabled && assistant.ragPolicy.knowledgeBaseResourceId === resource.id) {
      pushEntry('ASSISTANT_DEFAULT_KNOWLEDGE_BASE', 'ASSISTANT', assistant.id, assistant.name, null, null, true);
    }
  }

  for (const agent of fallbackState.catalog.agents) {
    if (agent.executionPolicy.modelResourceId === resource.id) {
      pushEntry('AGENT_OVERRIDE_MODEL', 'AGENT', agent.id, agent.name, null, null, true);
    }
    if (agent.executionPolicy.promptTemplateResourceId === resource.id) {
      pushEntry('AGENT_OVERRIDE_PROMPT', 'AGENT', agent.id, agent.name, null, null, true);
    }
    if (agent.executionPolicy.ragEnabled && agent.executionPolicy.knowledgeBaseResourceId === resource.id) {
      pushEntry('AGENT_OVERRIDE_KNOWLEDGE_BASE', 'AGENT', agent.id, agent.name, null, null, true);
    }
    if (agent.executionPolicy.toolResourceIds.includes(resource.id)) {
      pushEntry('AGENT_TOOL_ENABLED', 'AGENT', agent.id, agent.name, null, null, true);
    }
  }

  for (const assistant of fallbackState.catalog.assistants) {
    for (const release of assistant.releases) {
      for (const releaseResource of release.resources.filter((item) => item.resourceId === resource.id)) {
        pushEntry(
          'RELEASE_FROZEN',
          'ASSISTANT_RELEASE',
          release.id,
          `${assistant.name}@${release.releaseVersion}`,
          releaseResource.resourceVersionId,
          releaseResource.resourceVersion,
          false,
        );
      }
    }
  }

  return entries;
}

function mergeBoundAgents(current: string[], incoming: string[]) {
  return Array.from(new Set([...current, ...incoming])).filter(Boolean);
}

function collectReleaseResources(assistant: Assistant) {
  const merged = new Map<string, {
    resourceId: string;
    resourceName: string;
    resourceType: ResourceType;
    resourceVersionId: string;
    resourceVersion: string;
    boundAgents: string[];
    configuration: ResourceVersionConfiguration;
  }>();

  const addResource = (resourceId: string | null | undefined, preferredVersionId?: string | null, boundAgent?: string) => {
    if (!resourceId) {
      return;
    }
    const { resource, version } = resolveResourceVersion(resourceId, preferredVersionId);
    const key = `${resource.id}:${version.id}`;
    const current = merged.get(key);
    const next = {
      resourceId: resource.id,
      resourceName: resource.name,
      resourceType: resource.type,
      resourceVersionId: version.id,
      resourceVersion: version.version,
      boundAgents: boundAgent ? [boundAgent] : [],
      configuration: clone(version.configuration),
    };
    if (!current) {
      merged.set(key, next);
      return;
    }
    merged.set(key, {
      ...current,
      boundAgents: mergeBoundAgents(current.boundAgents, next.boundAgents),
    });
  };

  addResource(assistant.modelPolicy.providerResourceId);
  addResource(assistant.modelPolicy.promptTemplateResourceId);
  addResource(assistant.ragPolicy.enabled ? assistant.ragPolicy.knowledgeBaseResourceId : null);

  for (const agent of assistant.agents) {
    addResource(agent.executionPolicy.modelResourceId, null, agent.name);
    addResource(agent.executionPolicy.promptTemplateResourceId, null, agent.name);
    addResource(agent.executionPolicy.ragEnabled ? agent.executionPolicy.knowledgeBaseResourceId : null, null, agent.name);
    for (const resourceId of agent.executionPolicy.toolResourceIds) {
      addResource(resourceId, null, agent.name);
    }
  }

  return Array.from(merged.values());
}

function assistantReleaseVersion(assistant: Assistant) {
  return assistant.currentRelease?.releaseVersion ?? assistant.version.version;
}

function buildResourceAnchors(assistant: Assistant) {
  return collectReleaseResources(assistant).map((item) => `${item.resourceName}@${item.resourceVersion}`);
}

function buildFallbackExecution(sessionId: string, assistant: Assistant, requester: string, message: string) {
  const lower = message.toLowerCase();
  const waitingHuman = /投诉|人工|升级|escalat/.test(message);
  const afterSales = /退款|补偿|退货|售后/.test(message);
  const workflowId = nextId('wf');
  const taskId = nextId('task');
  const now = new Date().toISOString();
  const humanTask = waitingHuman
    ? {
        nodeKey: 'human-review',
        title: '人工协同待办',
        instruction: '请人工确认客户诉求、补偿方案和回复口径。',
        expectedAction: 'CONFIRM',
      }
    : null;
  const checkpoint = waitingHuman
    ? {
        checkpointId: nextId('cp'),
        currentNodeKey: 'human-review',
        waitingNodeKey: 'human-review',
        statePayload: JSON.stringify({ lastQuestion: message, sessionId }),
        resumeCount: 0,
      }
    : null;
  const reply = waitingHuman
    ? '已经进入人工协同流程，我们会由坐席继续处理。'
    : afterSales
      ? '根据当前售后规则，这个问题已进入售后策略处理，并给出自动建议。'
      : lower.includes('密码')
        ? '可以通过登录页的“忘记密码”入口完成重置，并留意短信或邮件验证码。'
        : '已根据当前编排完成自动处理。';
  const summary = waitingHuman
    ? '流程已运行到人工节点，等待人工确认。'
    : afterSales
      ? '售后策略智能体已完成处理。'
      : 'FAQ 流程已自动完成。';
  const toolCalls = afterSales || waitingHuman
    ? [
        {
          id: nextId('tool'),
          providerType: afterSales ? 'HTTP' : 'MCP',
          resourceId: afterSales ? 'resource-tool-refund' : 'resource-tool-ticket',
          resourceName: afterSales ? '售后策略 Tool' : '工单协同 Tool',
          operation: afterSales ? 'evaluate_refund' : 'create_ticket',
          status: 'COMPLETED',
          detail: afterSales ? '已生成售后策略建议。' : '已创建人工协同工单。',
          createdAt: now,
        },
      ]
    : [];
  const nodes = [
    { id: nextId('node-exec'), workflowInstanceId: workflowId, nodeKey: 'start', nodeName: '开始', status: 'COMPLETED' as const, detail: '会话消息已进入编排。', updatedAt: now },
    { id: nextId('node-exec'), workflowInstanceId: workflowId, nodeKey: 'route', nodeName: '问题路由', status: 'COMPLETED' as const, detail: waitingHuman ? '识别为投诉升级问题。' : afterSales ? '识别为售后策略问题。' : '识别为 FAQ 问答问题。', updatedAt: now },
    { id: nextId('node-exec'), workflowInstanceId: workflowId, nodeKey: waitingHuman ? 'human-review' : afterSales ? 'policy' : 'faq', nodeName: waitingHuman ? '人工复核' : afterSales ? '售后策略' : 'FAQ 回答', status: waitingHuman ? 'WAITING_HUMAN' as const : 'COMPLETED' as const, detail: waitingHuman ? '等待人工接管。' : afterSales ? '已生成售后建议。' : '已生成 FAQ 回答。', updatedAt: now },
    ...(waitingHuman ? [] : [{ id: nextId('node-exec'), workflowInstanceId: workflowId, nodeKey: 'end', nodeName: '结束', status: 'COMPLETED' as const, detail: '流程完成。', updatedAt: now }]),
  ];
  const workflow: WorkflowInstance = {
    id: workflowId,
    taskId,
    assistantId: assistant.id,
    assistantName: assistant.name,
    assistantReleaseVersion: assistantReleaseVersion(assistant),
    status: waitingHuman ? 'WAITING_HUMAN' : 'COMPLETED',
    summary,
    finalReply: waitingHuman ? null : reply,
    currentNodeKey: waitingHuman ? 'human-review' : 'end',
    escalationRequired: waitingHuman,
    checkpoint,
    humanTask,
    latestToolOutcome: waitingHuman
      ? {
          toolResourceId: 'resource-tool-ticket',
          toolResourceName: '工单协同 Tool',
          operation: 'create_ticket',
          providerType: 'MCP',
          status: 'ACCEPTED',
          externalReference: `TICKET-${Math.floor(10000 + Math.random() * 90000)}`,
          recommendedAction: 'HUMAN_HANDOFF',
          detail: '已创建人工协同工单。',
        }
      : afterSales
        ? {
            toolResourceId: 'resource-tool-refund',
            toolResourceName: '售后策略 Tool',
            operation: 'evaluate_refund',
            providerType: 'HTTP',
            status: 'RECORDED',
            externalReference: '',
            recommendedAction: 'AUTO_RESOLVE',
            detail: '售后策略已自动执行。',
          }
        : null,
    resourceAnchors: buildResourceAnchors(assistant),
    nodes,
    toolCalls,
    interventions: waitingHuman
      ? [
          {
            id: nextId('human'),
            workflowInstanceId: workflowId,
            action: 'WAIT_CONFIRM',
            operator: 'system',
            comment: '等待人工处理。',
            createdAt: now,
          },
        ]
      : [],
  };
  const task: TaskInstance = {
    id: taskId,
    scenarioId: assistant.scenarioId,
    assistantId: assistant.id,
    assistantName: assistant.name,
    assistantReleaseVersion: assistantReleaseVersion(assistant),
    question: message,
    requester,
    status: workflow.status === 'WAITING_HUMAN' ? 'WAITING_HUMAN' : 'COMPLETED',
    createdAt: now,
    workflowInstanceId: workflowId,
  };
  return { task, workflow, reply };
}

rebuildCatalogState();

async function request<T>(path: string, options?: RequestInit, fallback?: T | (() => T)): Promise<T> {
  try {
    const response = await fetch(`${API_BASE}${path}`, {
      headers: {
        'Content-Type': 'application/json',
        ...(options?.headers ?? {}),
      },
      ...options,
    });

    if (!response.ok) {
      const rawText = await response.text();
      let detail = rawText || `Request failed: ${response.status}`;
      if (rawText) {
        try {
          const errorBody = JSON.parse(rawText) as { detail?: string; message?: string; error?: string };
          detail = errorBody.detail ?? errorBody.message ?? errorBody.error ?? detail;
        } catch {
          detail = rawText;
        }
      }
      throw new Error(detail);
    }

    const body = await response.json();
    return body.data as T;
  } catch {
    if (fallback !== undefined) {
      return typeof fallback === 'function' ? (fallback as () => T)() : fallback;
    }
    throw new Error(`Failed to load ${path}`);
  }
}

function normalizeCatalogSummary(raw: CatalogSummary | Record<string, unknown>): CatalogSummary {
  const catalog = clone(raw) as CatalogSummary;
  fallbackState.catalog = clone(catalog);
  rebuildCatalogState();
  return clone(fallbackState.catalog);
}

export const api = {
  getSession: () => request<UserSession>('/auth/session', undefined, clone(fallbackState.session)),
  switchRole: (role: Role) =>
    request<UserSession>(
      '/auth/switch-role',
      { method: 'PATCH', body: JSON.stringify({ role }) },
      { ...fallbackState.session, currentRole: role },
    ),
  getCatalogSummary: async () => normalizeCatalogSummary(await request<CatalogSummary>('/catalog/summary', undefined, clone(fallbackState.catalog))),
  getConversationSessions: () => request<ConversationSession[]>('/runtime/sessions', undefined, clone(fallbackState.sessions)),
  createConversationSession: (payload: CreateConversationSessionPayload) =>
    request<ConversationSession>(
      '/runtime/sessions',
      { method: 'POST', body: JSON.stringify(payload) },
      () => {
        const created: ConversationSession = {
          id: nextId('session'),
          scenarioId: payload.scenarioId,
          title: payload.openingMessage.slice(0, 18) || '新会话',
          requester: payload.requester,
          assistantId: payload.assistantId,
          assistantName: findAssistant(payload.assistantId).name,
          assistantReleaseVersion: findAssistant(payload.assistantId).currentRelease?.releaseVersion ?? findAssistant(payload.assistantId).version.version,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
          messages: [],
          latestTaskId: null,
          latestWorkflowInstanceId: null,
          latestToolOutcome: null,
          latestHumanTask: null,
        };
        fallbackState.sessions.push(created);
        return clone(created);
      },
    ),
  sendConversationMessage: (sessionId: string, payload: ConversationMessagePayload) =>
    request<ConversationSession>(
      `/runtime/sessions/${sessionId}/messages`,
      { method: 'POST', body: JSON.stringify(payload) },
      () => {
        const current = findSession(sessionId);
        const assistant = findAssistant(current.assistantId);
        const execution = buildFallbackExecution(sessionId, assistant, payload.requester, payload.message);
        const userMessage = {
          id: nextId('msg'),
          sessionId,
          role: 'USER' as const,
          senderType: 'USER' as const,
          senderId: 'user',
          senderName: payload.requester,
          content: payload.message,
          createdAt: new Date().toISOString(),
          taskId: execution.task.id,
          workflowInstanceId: execution.workflow.id,
        };
        const assistantMessage = {
          id: nextId('msg'),
          sessionId,
          role: 'ASSISTANT' as const,
          senderType: 'ASSISTANT' as const,
          senderId: current.assistantId,
          senderName: current.assistantName,
          content: execution.workflow.status === 'WAITING_HUMAN'
            ? '已进入人工协同节点，等待处理结果。'
            : execution.reply,
          createdAt: new Date().toISOString(),
          taskId: execution.task.id,
          workflowInstanceId: execution.workflow.id,
        };
        fallbackState.tasks = [execution.task, ...fallbackState.tasks];
        fallbackState.workflows = [execution.workflow, ...fallbackState.workflows];
        const updated: ConversationSession = {
          ...current,
          updatedAt: new Date().toISOString(),
          messages: [...current.messages, userMessage, assistantMessage],
          latestTaskId: execution.task.id,
          latestWorkflowInstanceId: execution.workflow.id,
          latestToolOutcome: execution.workflow.latestToolOutcome,
          latestHumanTask: execution.workflow.humanTask,
        };
        fallbackState.sessions = fallbackState.sessions.map((item) => item.id === sessionId ? updated : item);
        return clone(updated);
      },
    ),
  createDomain: (payload: CreateDomainPayload) =>
    request<BusinessDomain>(
      '/domains',
      { method: 'POST', body: JSON.stringify(payload) },
      () => {
        const created: BusinessDomain = {
          id: nextId('domain'),
          name: payload.name.trim(),
          description: payload.description.trim(),
          scenarios: [],
          resources: [],
        };
        fallbackState.catalog.domains.push(created);
        rebuildCatalogState();
        return clone(created);
      },
    ),
  updateDomain: (domainId: string, payload: UpdateDomainPayload) =>
    request<BusinessDomain>(
      `/domains/${domainId}`,
      { method: 'PUT', body: JSON.stringify(payload) },
      () => {
        const current = findDomain(domainId);
        const updated: BusinessDomain = {
          ...current,
          name: payload.name.trim(),
          description: payload.description.trim(),
        };
        fallbackState.catalog.domains = fallbackState.catalog.domains.map((item) => item.id === domainId ? updated : item);
        rebuildCatalogState();
        return clone(updated);
      },
    ),
  deleteDomain: (domainId: string) =>
    request<BusinessDomain>(
      `/domains/${domainId}`,
      { method: 'DELETE' },
      () => {
        if (fallbackState.catalog.scenarios.some((item) => item.domainId === domainId)) {
          throw new Error('业务域下仍存在业务场景，暂时不能删除');
        }
        if (fallbackState.catalog.resources.some((item) => item.domainId === domainId)) {
          throw new Error('业务域下仍存在资源，暂时不能删除');
        }
        const current = findDomain(domainId);
        fallbackState.catalog.domains = fallbackState.catalog.domains.filter((item) => item.id !== domainId);
        rebuildCatalogState();
        return clone(current);
      },
    ),
  createScenario: (payload: CreateScenarioPayload) =>
    request<Scenario>(
      '/scenarios',
      { method: 'POST', body: JSON.stringify(payload) },
      () => {
        findDomain(payload.domainId);
        const created: Scenario = {
          id: nextId('scenario'),
          domainId: payload.domainId,
          name: payload.name.trim(),
          goal: payload.goal.trim(),
          version: {
            version: '0.1.0',
            status: 'DRAFT',
            updatedAt: new Date().toISOString(),
          },
          assistants: [],
        };
        fallbackState.catalog.scenarios.push(created);
        rebuildCatalogState();
        return clone(created);
      },
    ),
  updateScenario: (scenarioId: string, payload: UpdateScenarioPayload) =>
    request<Scenario>(
      `/scenarios/${scenarioId}`,
      { method: 'PUT', body: JSON.stringify(payload) },
      () => {
        const current = findScenario(scenarioId);
        const updated: Scenario = {
          ...current,
          name: payload.name.trim(),
          goal: payload.goal.trim(),
        };
        fallbackState.catalog.scenarios = fallbackState.catalog.scenarios.map((item) => item.id === scenarioId ? updated : item);
        rebuildCatalogState();
        return clone(updated);
      },
    ),
  deleteScenario: (scenarioId: string) =>
    request<Scenario>(
      `/scenarios/${scenarioId}`,
      { method: 'DELETE' },
      () => {
        if (fallbackState.catalog.assistants.some((item) => item.scenarioId === scenarioId)) {
          throw new Error('业务场景下仍存在助手，暂时不能删除');
        }
        const current = findScenario(scenarioId);
        fallbackState.catalog.scenarios = fallbackState.catalog.scenarios.filter((item) => item.id !== scenarioId);
        rebuildCatalogState();
        return clone(current);
      },
    ),
  createAssistant: (payload: CreateAssistantPayload) =>
    request<Assistant>(
      '/assistants',
      { method: 'POST', body: JSON.stringify(payload) },
      () => {
        const created: Assistant = {
          id: nextId('assistant'),
          scenarioId: payload.scenarioId,
          name: payload.name,
          description: payload.description,
          version: {
            version: '0.1.0',
            status: 'DRAFT',
            updatedAt: new Date().toISOString(),
          },
          agents: [],
          currentRelease: null,
          releases: [],
          modelPolicy: payload.modelPolicy,
          ragPolicy: payload.ragPolicy,
          memoryPolicy: payload.memoryPolicy,
        };
        fallbackState.catalog.assistants.push(created);
        fallbackState.catalog.orchestrations.push({
          assistantId: created.id,
          assistantName: created.name,
          scenarioId: created.scenarioId,
          executionMode: 'GRAPH',
          nodes: [
            {
              nodeKey: 'start',
              nodeName: '开始',
              nodeType: 'START',
              description: '编排入口',
              agentId: null,
              humanNode: null,
            },
            {
              nodeKey: 'end',
              nodeName: '结束',
              nodeType: 'END',
              description: '编排出口',
              agentId: null,
              humanNode: null,
            },
          ],
          edges: [
            {
              edgeKey: 'edge-start-end',
              sourceNodeKey: 'start',
              targetNodeKey: 'end',
              routeKey: null,
              label: '默认结束',
              defaultEdge: true,
            },
          ],
        });
        rebuildCatalogState();
        return clone(created);
      },
    ),
  updateAssistant: (assistantId: string, payload: UpdateAssistantPayload) =>
    request<Assistant>(
      `/assistants/${assistantId}`,
      { method: 'PUT', body: JSON.stringify(payload) },
      () => {
        const current = findAssistant(assistantId);
        const nextVersion = payload.status === 'PUBLISHED'
          ? (() => {
              const segments = current.version.version.split('.');
              return `${segments[0]}.${segments[1]}.${Number(segments[2]) + 1}`;
            })()
          : current.version.version;
        const updated: Assistant = {
          ...current,
          name: payload.name,
          description: payload.description,
          modelPolicy: payload.modelPolicy,
          ragPolicy: payload.ragPolicy,
          memoryPolicy: payload.memoryPolicy,
          version: {
            version: nextVersion,
            status: payload.status,
            updatedAt: new Date().toISOString(),
          },
        };
        if (payload.status === 'PUBLISHED') {
          const release = buildAssistantRelease(updated, nextVersion);
          updated.currentRelease = release;
          updated.releases = [release, ...current.releases];
        }
        fallbackState.catalog.assistants = fallbackState.catalog.assistants.map((item) => item.id === assistantId ? updated : item);
        rebuildCatalogState();
        return clone(updated);
      },
    ),
  deleteAssistant: (assistantId: string) =>
    request<Assistant>(
      `/assistants/${assistantId}`,
      { method: 'DELETE' },
      () => {
        if (fallbackState.catalog.agents.some((item) => item.assistantId === assistantId)) {
          throw new Error('助手下仍存在智能体，暂时不能删除');
        }
        if (fallbackState.catalog.resources.some((item) => item.ownerType === 'ASSISTANT' && item.ownerId === assistantId)) {
          throw new Error('助手下仍存在私有资源，暂时不能删除');
        }
        const current = findAssistant(assistantId);
        fallbackState.catalog.assistants = fallbackState.catalog.assistants.filter((item) => item.id !== assistantId);
        fallbackState.catalog.orchestrations = fallbackState.catalog.orchestrations.filter((item) => item.assistantId !== assistantId);
        rebuildCatalogState();
        return clone(current);
      },
    ),
  createAgent: (payload: CreateAgentPayload) =>
    request<Agent>(
      '/agents',
      { method: 'POST', body: JSON.stringify(payload) },
      () => {
        const created: Agent = {
          id: nextId('agent'),
          assistantId: payload.assistantId,
          name: payload.name,
          role: payload.role,
          instructions: payload.instructions,
          executionPolicy: payload.executionPolicy,
        };
        fallbackState.catalog.agents.push(created);
        const orchestration = fallbackState.catalog.orchestrations.find((item) => item.assistantId === payload.assistantId);
        if (orchestration) {
          const endIndex = orchestration.nodes.findIndex((node) => node.nodeType === 'END');
          const agentNode = {
            nodeKey: created.id,
            nodeName: created.name,
            nodeType: 'AGENT' as const,
            description: created.instructions,
            agentId: created.id,
            humanNode: null,
          };
          if (endIndex < 0) {
            orchestration.nodes.push(agentNode);
          } else {
            orchestration.nodes.splice(endIndex, 0, agentNode);
            orchestration.edges = orchestration.edges.filter((edge) => !(edge.sourceNodeKey === 'start' && edge.targetNodeKey === 'end'));
            orchestration.edges.push({
              edgeKey: `edge-start-${created.id}`,
              sourceNodeKey: 'start',
              targetNodeKey: created.id,
              routeKey: null,
              label: '默认主链',
              defaultEdge: true,
            });
            orchestration.edges.push({
              edgeKey: `edge-${created.id}-end`,
              sourceNodeKey: created.id,
              targetNodeKey: 'end',
              routeKey: null,
              label: '执行完成',
              defaultEdge: true,
            });
          }
        }
        rebuildCatalogState();
        return clone(created);
      },
    ),
  deleteAgent: (agentId: string) =>
    request<Agent>(
      `/agents/${agentId}`,
      { method: 'DELETE' },
      () => {
        const current = findAgent(agentId);
        fallbackState.catalog.agents = fallbackState.catalog.agents.filter((item) => item.id !== agentId);
        fallbackState.catalog.orchestrations = fallbackState.catalog.orchestrations.map((item) =>
          item.assistantId === current.assistantId ? buildDefaultOrchestrationForAssistant(current.assistantId) : item,
        );
        rebuildCatalogState();
        return clone(current);
      },
    ),
  updateAgent: (agentId: string, payload: UpdateAgentPayload) =>
    request<Agent>(
      `/agents/${agentId}`,
      { method: 'PUT', body: JSON.stringify(payload) },
      () => {
        const current = findAgent(agentId);
        const updated: Agent = {
          ...current,
          name: payload.name,
          role: payload.role,
          instructions: payload.instructions,
          executionPolicy: payload.executionPolicy,
        };
        fallbackState.catalog.agents = fallbackState.catalog.agents.map((item) => item.id === agentId ? updated : item);
        fallbackState.catalog.orchestrations = fallbackState.catalog.orchestrations.map((item) => ({
          ...item,
          nodes: item.nodes.map((node) => node.agentId === agentId
            ? { ...node, nodeName: payload.name, description: payload.instructions }
            : node),
        }));
        rebuildCatalogState();
        return clone(updated);
      },
    ),
  createResource: (payload: CreateResourcePayload) =>
    request<Resource>(
      '/resources',
      { method: 'POST', body: JSON.stringify(payload) },
      () => {
        findDomain(payload.domainId);
        validateResourceOwner(payload.domainId, payload.ownerType, payload.ownerId);
        const initialVersion: ResourceVersion = {
          id: nextId('resource-version'),
          resourceId: nextId('resource'),
          version: '0.1.0',
          status: payload.initialVersion.status,
          summary: payload.initialVersion.summary,
          configDigest: payload.initialVersion.configDigest || `digest-${Date.now()}`,
          createdAt: new Date().toISOString(),
          publishedAt: payload.initialVersion.status === 'PUBLISHED' ? new Date().toISOString() : null,
          configuration: normalizeConfiguration(payload.type, payload.initialVersion.configuration),
        };
        const created: Resource = {
          id: initialVersion.resourceId,
          domainId: payload.domainId,
          name: payload.name,
          type: payload.type,
          shareScope: payload.shareScope,
          ownerType: payload.ownerType,
          ownerId: payload.ownerId,
          summary: payload.summary,
          steward: payload.steward,
          tags: payload.tags,
          latestVersion: initialVersion,
          effectiveVersion: payload.initialVersion.status === 'PUBLISHED' ? initialVersion : null,
          versions: [initialVersion],
        };
        fallbackState.catalog.resources.push(created);
        rebuildCatalogState();
        return clone(created);
      },
    ),
  deleteResource: (resourceId: string) =>
    request<Resource>(
      `/resources/${resourceId}`,
      { method: 'DELETE' },
      () => {
        const blocker = findResourceDeletionBlocker(resourceId);
        if (blocker) {
          throw new Error(blocker);
        }
        const current = findResource(resourceId);
        fallbackState.catalog.resources = fallbackState.catalog.resources.filter((item) => item.id !== resourceId);
        rebuildCatalogState();
        return clone(current);
      },
    ),
  createResourceVersion: (resourceId: string, payload: CreateResourceVersionPayload) =>
    request<ResourceVersion>(
      `/resources/${resourceId}/versions`,
      { method: 'POST', body: JSON.stringify(payload) },
      () => {
        const resource = findResource(resourceId);
        const lastVersion = resource.versions[resource.versions.length - 1]?.version ?? '0.0.0';
        const [major, minor, patch] = lastVersion.split('.').map((item) => Number(item));
        const created: ResourceVersion = {
          id: nextId('resource-version'),
          resourceId,
          version: `${major}.${minor}.${patch + 1}`,
          status: payload.status,
          summary: payload.summary,
          configDigest: payload.configDigest || `digest-${Date.now()}`,
          createdAt: new Date().toISOString(),
          publishedAt: payload.status === 'PUBLISHED' ? new Date().toISOString() : null,
          configuration: normalizeConfiguration(resource.type, payload.configuration),
        };
        const updatedResource: Resource = {
          ...resource,
          latestVersion: created,
          effectiveVersion: payload.status === 'PUBLISHED' ? created : resource.effectiveVersion,
          versions: resource.versions
            .map((version) => payload.status === 'PUBLISHED' && version.status === 'PUBLISHED'
              ? { ...version, status: 'DRAFT' as const, publishedAt: null as string | null }
              : version)
            .concat(created),
        };
        fallbackState.catalog.resources = fallbackState.catalog.resources.map((item) => item.id === resourceId ? updatedResource : item);
        rebuildCatalogState();
        return clone(created);
      },
    ),
  deleteResourceVersion: (resourceId: string, versionId: string) =>
    request<ResourceVersion>(
      `/resources/${resourceId}/versions/${versionId}`,
      { method: 'DELETE' },
      () => {
        const resource = findResource(resourceId);
        const version = findResourceVersion(resourceId, versionId);
        if (resource.effectiveVersion?.id === versionId) {
          throw new Error('生效版本不能直接删除');
        }
        if (resource.versions.length <= 1) {
          throw new Error('资源至少需要保留一个版本，如需清理请直接删除资源');
        }
        const blocker = findResourceVersionDeletionBlocker(versionId);
        if (blocker) {
          throw new Error(blocker);
        }
        const updatedResource: Resource = {
          ...resource,
          latestVersion: resource.latestVersion?.id === versionId
            ? resource.versions.filter((item) => item.id !== versionId).slice(-1)[0] ?? null
            : resource.latestVersion,
          versions: resource.versions.filter((item) => item.id !== versionId),
        };
        fallbackState.catalog.resources = fallbackState.catalog.resources.map((item) => item.id === resourceId ? updatedResource : item);
        rebuildCatalogState();
        return clone(version);
      },
    ),
  publishResourceVersion: (resourceId: string, versionId: string) =>
    request<ResourceVersion>(
      `/resources/${resourceId}/versions/${versionId}/publish`,
      { method: 'PATCH' },
      () => {
        const resource = findResource(resourceId);
        const publishedAt = new Date().toISOString();
        let effectiveVersion: ResourceVersion | null = null;
        const versions = resource.versions.map((version) => {
          const updated = version.id === versionId
            ? { ...version, status: 'PUBLISHED' as const, publishedAt }
            : { ...version, status: 'DRAFT' as const, publishedAt: null as string | null };
          if (updated.id === versionId) {
            effectiveVersion = updated;
          }
          return updated;
        });
        const updatedResource: Resource = {
          ...resource,
          effectiveVersion,
          versions,
        };
        fallbackState.catalog.resources = fallbackState.catalog.resources.map((item) => item.id === resourceId ? updatedResource : item);
        rebuildCatalogState();
        return clone(effectiveVersion ?? versions[0]);
      },
    ),
  saveOrchestration: (assistantId: string, payload: UpdateOrchestrationPayload) =>
    request(
      `/orchestrations/${assistantId}`,
      { method: 'PUT', body: JSON.stringify(payload) },
      () => {
        const assistant = findAssistant(assistantId);
        const orchestration = {
          assistantId,
          assistantName: assistant.name,
          scenarioId: assistant.scenarioId,
          executionMode: payload.executionMode,
          nodes: payload.nodes as OrchestrationNode[],
          edges: payload.edges as OrchestrationEdge[],
        };
        fallbackState.catalog.orchestrations = fallbackState.catalog.orchestrations
          .filter((item) => item.assistantId !== assistantId)
          .concat(orchestration);
        rebuildCatalogState();
        return clone(orchestration);
      },
    ),
  getTasks: () => request<TaskInstance[]>('/tasks', undefined, clone(fallbackState.tasks)),
  getWorkflows: () => request<WorkflowInstance[]>('/workflows', undefined, clone(fallbackState.workflows)),
  getWorkflow: (workflowId: string) =>
    request<WorkflowInstance>(
      `/workflows/${workflowId}`,
      undefined,
      clone(fallbackState.workflows.find((item) => item.id === workflowId) ?? fallbackState.workflows[0]),
    ),
  launchTask: (payload: { scenarioId: string; assistantId: string; question: string; requester: string }) =>
    request<TaskInstance>(
      '/tasks',
      { method: 'POST', body: JSON.stringify(payload) },
      () => {
        const assistant = findAssistant(payload.assistantId);
        const execution = buildFallbackExecution(nextId('session-task'), assistant, payload.requester, payload.question);
        fallbackState.tasks = [execution.task, ...fallbackState.tasks];
        fallbackState.workflows = [execution.workflow, ...fallbackState.workflows];
        return clone(execution.task);
      },
    ),
  completeHumanAction: (workflowId: string, payload: { action: string; comment: string; operatorId: string; attributes: Record<string, string> }) =>
    request<WorkflowInstance>(
      `/workflows/${workflowId}/human-action`,
      { method: 'PATCH', body: JSON.stringify(payload) },
      () => {
        const current = fallbackState.workflows.find((item) => item.id === workflowId) ?? fallbackState.workflows[0];
        const nextStatus = payload.action === 'TERMINATE' ? 'CANCELLED' as const : 'COMPLETED' as const;
        const completedAt = new Date().toISOString();
        const intervention = {
          id: nextId('human'),
          workflowInstanceId: workflowId,
          action: payload.action,
          operator: payload.operatorId,
          comment: payload.comment,
          createdAt: completedAt,
        };
        const updatedWorkflow: WorkflowInstance = {
          ...current,
          status: nextStatus,
          summary: nextStatus === 'CANCELLED' ? '人工终止了当前流程。' : '人工处理完成，流程已恢复并结束。',
          finalReply: nextStatus === 'CANCELLED' ? '当前流程已终止。' : '人工处理完成，结果已同步给客户。',
          currentNodeKey: nextStatus === 'CANCELLED' ? current.currentNodeKey : 'end',
          escalationRequired: false,
          checkpoint: null,
          humanTask: null,
          nodes: current.nodes.concat(nextStatus === 'CANCELLED'
            ? []
            : [{
                id: nextId('node-exec'),
                workflowInstanceId: workflowId,
                nodeKey: 'end',
                nodeName: '结束',
                status: 'COMPLETED',
                detail: payload.comment || '人工恢复后结束流程。',
                updatedAt: completedAt,
              }]),
          interventions: [...current.interventions, intervention],
        };
        fallbackState.workflows = fallbackState.workflows.map((item) => item.id === workflowId ? updatedWorkflow : item);
        fallbackState.tasks = fallbackState.tasks.map((item) => item.workflowInstanceId === workflowId
          ? { ...item, status: nextStatus }
          : item);
        fallbackState.sessions = fallbackState.sessions.map((item) => {
          if (item.latestWorkflowInstanceId !== workflowId) {
            return item;
          }
          return {
            ...item,
            updatedAt: completedAt,
            latestHumanTask: null,
            messages: item.messages.concat({
              id: nextId('msg'),
              sessionId: item.id,
              role: 'SYSTEM',
              senderType: 'SYSTEM',
              senderId: payload.operatorId,
              senderName: '人工协同',
              content: nextStatus === 'CANCELLED'
                ? `人工已终止流程：${payload.comment || '无备注'}`
                : `人工已完成处理：${payload.comment || '无备注'}`,
              createdAt: completedAt,
              taskId: updatedWorkflow.taskId,
              workflowInstanceId: workflowId,
            }),
          };
        });
        return clone(updatedWorkflow);
      },
    ),
};
