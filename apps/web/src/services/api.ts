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
      nodes: orchestration.nodes.map((node) => {
        const agent = catalog.agents.find((item) => item.id === node.agentId);
        return {
          ...node,
          resourceIds: agent?.bindings.map((binding) => binding.resourceId) ?? node.resourceIds,
        };
      }),
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
  const resources = assistant.agents.flatMap((agent) =>
    agent.bindings.map((binding) => {
      const resource = fallbackState.catalog.resources.find((item) => item.id === binding.resourceId);
      return {
        resourceId: binding.resourceId,
        resourceName: resource?.name ?? binding.resourceId,
        resourceType: resource?.type ?? 'UNKNOWN',
        resourceVersionId: binding.resourceVersionId,
        resourceVersion: binding.resourceVersion,
        boundAgents: [agent.name],
      };
    }),
  );

  const merged = new Map<string, typeof resources[number]>();
  for (const item of resources) {
    const current = merged.get(item.resourceVersionId);
    if (!current) {
      merged.set(item.resourceVersionId, item);
      continue;
    }
    merged.set(item.resourceVersionId, {
      ...current,
      boundAgents: [...current.boundAgents, ...item.boundAgents],
    });
  }

  return {
    id: nextId('assistant-release'),
    assistantId: assistant.id,
    releaseVersion,
    status: 'PUBLISHED' as const,
    createdAt: new Date().toISOString(),
    publishedAt: new Date().toISOString(),
    resources: Array.from(merged.values()),
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
        const userMessage = {
          id: nextId('msg'),
          sessionId,
          role: 'USER' as const,
          senderType: 'USER' as const,
          senderId: 'user',
          senderName: payload.requester,
          content: payload.message,
          createdAt: new Date().toISOString(),
          taskId: null,
          workflowInstanceId: null,
        };
        const assistantMessage = {
          id: nextId('msg'),
          sessionId,
          role: 'ASSISTANT' as const,
          senderType: 'ASSISTANT' as const,
          senderId: current.assistantId,
          senderName: current.assistantName,
          content: `${current.assistantName} 已收到你的消息，当前为本地 fallback 对话模式。`,
          createdAt: new Date().toISOString(),
          taskId: null,
          workflowInstanceId: null,
        };
        const updated: ConversationSession = {
          ...current,
          updatedAt: new Date().toISOString(),
          messages: [...current.messages, userMessage, assistantMessage],
          latestMcpSummary: current.latestMcpSummary,
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
          executionMode: 'SEQUENTIAL_GRAPH',
          nodes: [],
          edges: [],
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
        orchestration?.nodes.push({
          nodeId: `node-${created.id}`,
          nodeName: created.name,
          nodeType: 'AGENT',
          agentId: created.id,
          description: created.instructions,
          resourceIds: [],
        });
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
  launchTask: (payload: { scenarioId: string; question: string; requester: string }) =>
    request<TaskInstance>('/tasks', { method: 'POST', body: JSON.stringify(payload) }, clone(fallbackState.tasks[0])),
  completeHumanAction: (workflowId: string, action: string, comment: string) =>
    request<WorkflowInstance>(
      `/workflows/${workflowId}/human-action`,
      { method: 'PATCH', body: JSON.stringify({ action, comment }) },
      {
        ...(fallbackState.workflows.find((item) => item.id === workflowId) ?? fallbackState.workflows[1]),
        status: action === 'TERMINATE' ? 'CANCELLED' : 'COMPLETED',
      },
    ),
};
