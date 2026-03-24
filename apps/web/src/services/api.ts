import type {
  Agent,
  Assistant,
  CatalogSummary,
  ConversationMessagePayload,
  ConversationSession,
  CreateAssistantPayload,
  CreateAgentPayload,
  CreateConversationSessionPayload,
  CreateResourcePayload,
  CreateResourceVersionPayload,
  ResourceType,
  ResourceVersionConfiguration,
  OrchestrationEdge,
  OrchestrationNode,
  Resource,
  ResourceVersion,
  Role,
  TaskInstance,
  UpdateAgentBindingsPayload,
  UpdateAssistantPayload,
  UpdateAgentPayload,
  UpdateOrchestrationPayload,
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
    usages: catalog.resources.map((resource) => ({
      resourceId: resource.id,
      resourceName: resource.name,
      type: resource.type,
      shareScope: resource.shareScope,
      ownerLabel: `${resource.ownerType}:${resource.ownerId}`,
      latestVersion: resource.latestVersion?.version ?? null,
      effectiveVersion: resource.effectiveVersion?.version ?? null,
      boundAgents: catalog.agents
        .filter((agent) => agent.bindings.some((binding) => binding.resourceId === resource.id))
        .map((agent) => agent.name),
      boundAssistants: catalog.assistants
        .filter((assistant) => assistant.agents.some((agent) => agent.bindings.some((binding) => binding.resourceId === resource.id)))
        .map((assistant) => assistant.name),
      bindingAnchors: catalog.agents
        .flatMap((agent) => agent.bindings
          .filter((binding) => binding.resourceId === resource.id)
          .map((binding) => `${agent.name} -> ${binding.resourceVersion}`)),
    })),
  };
}

function nextId(prefix: string): string {
  return `${prefix}-${Math.random().toString(16).slice(2, 10)}`;
}

function defaultConfiguration(type: ResourceType): ResourceVersionConfiguration {
  if (type === 'KNOWLEDGE_BASE') {
    return {
      type,
      knowledgeBase: {
        sourceType: 'OBJECT_STORAGE',
        sourceLocation: 'minio://knowledge/new-resource',
        syncMode: 'MANUAL',
        retrievalMode: 'HYBRID',
        embeddingModel: 'text-embedding-3-large',
        chunkStrategy: 'markdown-512-overlap-80',
        defaultTopK: 5,
        documentCount: 0,
      },
    };
  }
  if (type === 'SKILL') {
    return {
      type,
      skill: {
        runtime: 'HTTP',
        endpoint: 'https://skill-gateway.internal/new-skill',
        method: 'POST',
        authType: 'SERVICE_ACCOUNT',
        timeoutSeconds: 15,
        retryPolicy: 'EXPONENTIAL_BACKOFF',
        inputSchema: '{input}',
        outputSchema: '{output}',
      },
    };
  }
  if (type === 'MCP') {
    return {
      type,
      mcp: {
        serverName: 'new-mcp-server',
        transport: 'STREAMABLE_HTTP',
        connectionUri: 'https://mcp-gateway.internal/new-server',
        namespace: 'default.namespace',
        authType: 'API_KEY',
        heartbeatSeconds: 30,
        exposedTools: ['tool_a', 'tool_b'],
      },
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
      knowledgeBase: configuration.knowledgeBase ?? defaultConfiguration(type).knowledgeBase,
    };
  }
  if (type === 'SKILL') {
    return {
      type,
      skill: configuration.skill ?? defaultConfiguration(type).skill,
    };
  }
  if (type === 'MCP') {
    return {
      type,
      mcp: configuration.mcp ?? defaultConfiguration(type).mcp,
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

function buildAssistantRelease(assistant: Assistant, releaseVersion: string) {
  const orchestration = clone(findOrchestration(assistant.id));
  const agentSnapshots = assistant.agents.map((agent) => ({
    agentId: agent.id,
    name: agent.name,
    role: agent.role,
    instructions: agent.instructions,
    executionPolicy: clone(agent.executionPolicy),
    bindingResourceVersionIds: agent.bindings.map((binding) => binding.resourceVersionId),
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

function findResource(resourceId: string): Resource {
  const resource = fallbackState.catalog.resources.find((item) => item.id === resourceId);
  if (!resource) {
    throw new Error(`Resource ${resourceId} not found`);
  }
  return resource;
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
    for (const binding of agent.bindings) {
      addResource(binding.resourceId, binding.resourceVersionId, agent.name);
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
          toolType: afterSales ? 'SKILL' : 'MCP',
          resourceId: afterSales ? 'resource-skill-refund' : 'resource-mcp-ticket',
          resourceName: afterSales ? '退款策略 Skill' : '工单协同 MCP',
          operation: afterSales ? 'refund-policy' : 'create-ticket',
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
    mcpSummary: waitingHuman
      ? {
          capabilityName: '创建协同工单',
          externalTicketId: `TICKET-${Math.floor(10000 + Math.random() * 90000)}`,
          status: 'ACCEPTED',
          recommendedAction: 'HUMAN_HANDOFF',
          detail: '已创建人工协同工单。',
        }
      : afterSales
        ? {
            capabilityName: '售后策略执行',
            externalTicketId: '',
            status: 'RECORDED',
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
      throw new Error(`Request failed: ${response.status}`);
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
          latestMcpSummary: null,
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
          latestMcpSummary: execution.workflow.mcpSummary,
          latestHumanTask: execution.workflow.humanTask,
        };
        fallbackState.sessions = fallbackState.sessions.map((item) => item.id === sessionId ? updated : item);
        return clone(updated);
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
          bindings: [],
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
  updateAgentBindings: (agentId: string, payload: UpdateAgentBindingsPayload) =>
    request<Agent>(
      `/agents/${agentId}/bindings`,
      { method: 'PUT', body: JSON.stringify(payload) },
      () => {
        const current = findAgent(agentId);
        const updated: Agent = {
          ...current,
          bindings: payload.bindings.map((binding) => {
            const resource = fallbackState.catalog.resources.find((item) => item.id === binding.resourceId);
            const version = resource?.versions.find((item) => item.id === binding.resourceVersionId);
            return {
            id: nextId('binding'),
            resourceId: binding.resourceId,
            resourceVersionId: binding.resourceVersionId,
            resourceVersion: version?.version ?? 'unknown',
            consumerType: 'AGENT',
            consumerId: agentId,
            createdAt: new Date().toISOString(),
            };
          }),
        };
        fallbackState.catalog.agents = fallbackState.catalog.agents.map((item) => item.id === agentId ? updated : item);
        rebuildCatalogState();
        return clone(updated);
      },
    ),
  createResource: (payload: CreateResourcePayload) =>
    request<Resource>(
      '/resources',
      { method: 'POST', body: JSON.stringify(payload) },
      () => {
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
