import type {
  Agent,
  AgentGroup,
  CatalogSummary,
  CreateAgentGroupPayload,
  CreateAgentPayload,
  OrchestrationEdge,
  OrchestrationNode,
  Resource,
  Role,
  TaskInstance,
  UpdateAgentBindingsPayload,
  UpdateAgentGroupPayload,
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
};

function rebuildCatalogState() {
  const catalog = fallbackState.catalog;
  const agentsByGroup = new Map<string, Agent[]>();
  for (const agent of catalog.agents) {
    const current = agentsByGroup.get(agent.agentGroupId) ?? [];
    current.push(agent);
    agentsByGroup.set(agent.agentGroupId, current);
  }

  catalog.agentGroups = catalog.agentGroups.map((group) => ({
    ...group,
    agents: (agentsByGroup.get(group.id) ?? []).slice(),
  }));

  catalog.scenarios = catalog.scenarios.map((scenario) => ({
    ...scenario,
    agentGroups: catalog.agentGroups.filter((group) => group.scenarioId === scenario.id),
  }));

  catalog.domains = catalog.domains.map((domain) => ({
    ...domain,
    scenarios: catalog.scenarios.filter((scenario) => scenario.domainId === domain.id),
    resources: catalog.resources.filter((resource) => resource.domainId === domain.id),
  }));

  catalog.orchestrations = catalog.orchestrations.map((orchestration) => {
    const group = catalog.agentGroups.find((item) => item.id === orchestration.agentGroupId);
    return {
      ...orchestration,
      agentGroupName: group?.name ?? orchestration.agentGroupName,
      scenarioId: group?.scenarioId ?? orchestration.scenarioId,
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
      boundAgents: catalog.agents
        .filter((agent) => agent.bindings.some((binding) => binding.resourceId === resource.id))
        .map((agent) => agent.name),
      boundAgentGroups: catalog.agentGroups
        .filter((group) => group.agents.some((agent) => agent.bindings.some((binding) => binding.resourceId === resource.id)))
        .map((group) => group.name),
    })),
  };
}

function nextId(prefix: string): string {
  return `${prefix}-${Math.random().toString(16).slice(2, 10)}`;
}

function findAgentGroup(agentGroupId: string): AgentGroup {
  const agentGroup = fallbackState.catalog.agentGroups.find((item) => item.id === agentGroupId);
  if (!agentGroup) {
    throw new Error(`Agent group ${agentGroupId} not found`);
  }
  return agentGroup;
}

function findAgent(agentId: string): Agent {
  const agent = fallbackState.catalog.agents.find((item) => item.id === agentId);
  if (!agent) {
    throw new Error(`Agent ${agentId} not found`);
  }
  return agent;
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

export const api = {
  getSession: () => request<UserSession>('/auth/session', undefined, clone(fallbackState.session)),
  switchRole: (role: Role) =>
    request<UserSession>(
      '/auth/switch-role',
      { method: 'PATCH', body: JSON.stringify({ role }) },
      { ...fallbackState.session, currentRole: role },
    ),
  getCatalogSummary: () => request<CatalogSummary>('/catalog/summary', undefined, clone(fallbackState.catalog)),
  createAgentGroup: (payload: CreateAgentGroupPayload) =>
    request<AgentGroup>(
      '/agent-groups',
      { method: 'POST', body: JSON.stringify(payload) },
      () => {
        const created: AgentGroup = {
          id: nextId('agent-group'),
          scenarioId: payload.scenarioId,
          name: payload.name,
          description: payload.description,
          version: {
            version: '0.1.0',
            status: 'DRAFT',
            updatedAt: new Date().toISOString(),
          },
          agents: [],
        };
        fallbackState.catalog.agentGroups.push(created);
        fallbackState.catalog.orchestrations.push({
          agentGroupId: created.id,
          agentGroupName: created.name,
          scenarioId: created.scenarioId,
          executionMode: 'SEQUENTIAL_GRAPH',
          nodes: [],
          edges: [],
        });
        rebuildCatalogState();
        return clone(created);
      },
    ),
  updateAgentGroup: (agentGroupId: string, payload: UpdateAgentGroupPayload) =>
    request<AgentGroup>(
      `/agent-groups/${agentGroupId}`,
      { method: 'PUT', body: JSON.stringify(payload) },
      () => {
        const current = findAgentGroup(agentGroupId);
        const updated: AgentGroup = {
          ...current,
          name: payload.name,
          description: payload.description,
          version: {
            ...current.version,
            status: payload.status,
            updatedAt: new Date().toISOString(),
          },
        };
        fallbackState.catalog.agentGroups = fallbackState.catalog.agentGroups.map((item) => item.id === agentGroupId ? updated : item);
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
          agentGroupId: payload.agentGroupId,
          name: payload.name,
          role: payload.role,
          instructions: payload.instructions,
          bindings: [],
        };
        fallbackState.catalog.agents.push(created);
        const orchestration = fallbackState.catalog.orchestrations.find((item) => item.agentGroupId === payload.agentGroupId);
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
          bindings: payload.resourceIds.map((resourceId) => ({
            id: nextId('binding'),
            resourceId,
            consumerType: 'AGENT',
            consumerId: agentId,
            createdAt: new Date().toISOString(),
          })),
        };
        fallbackState.catalog.agents = fallbackState.catalog.agents.map((item) => item.id === agentId ? updated : item);
        rebuildCatalogState();
        return clone(updated);
      },
    ),
  saveOrchestration: (agentGroupId: string, payload: UpdateOrchestrationPayload) =>
    request(
      `/orchestrations/${agentGroupId}`,
      { method: 'PUT', body: JSON.stringify(payload) },
      () => {
        const group = findAgentGroup(agentGroupId);
        const orchestration = {
          agentGroupId,
          agentGroupName: group.name,
          scenarioId: group.scenarioId,
          executionMode: payload.executionMode,
          nodes: payload.nodes as OrchestrationNode[],
          edges: payload.edges as OrchestrationEdge[],
        };
        fallbackState.catalog.orchestrations = fallbackState.catalog.orchestrations
          .filter((item) => item.agentGroupId !== agentGroupId)
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
