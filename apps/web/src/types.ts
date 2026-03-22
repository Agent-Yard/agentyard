export type Role = 'PLATFORM_ADMIN' | 'DOMAIN_ADMIN' | 'DEVELOPER' | 'BUSINESS_USER';
export type ResourceType = 'SKILL' | 'MCP' | 'KNOWLEDGE_BASE';
export type ShareScope = 'PRIVATE' | 'DOMAIN_SHARED';
export type VersionStatus = 'DRAFT' | 'PUBLISHED';
export type TaskStatus = 'PENDING' | 'RUNNING' | 'WAITING_HUMAN' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type WorkflowStatus = 'DRAFT' | 'RUNNING' | 'WAITING_HUMAN' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type NodeStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'WAITING_HUMAN';

export interface UserSession {
  userId: string;
  displayName: string;
  currentRole: Role;
  availableRoles: Role[];
}

export interface Version {
  version: string;
  status: VersionStatus;
  updatedAt: string;
}

export interface ResourceBinding {
  id: string;
  resourceId: string;
  consumerType: string;
  consumerId: string;
  createdAt: string;
}

export interface Agent {
  id: string;
  agentGroupId: string;
  name: string;
  role: string;
  instructions: string;
  bindings: ResourceBinding[];
}

export interface AgentGroup {
  id: string;
  scenarioId: string;
  name: string;
  description: string;
  version: Version;
  agents: Agent[];
}

export interface Resource {
  id: string;
  domainId: string;
  name: string;
  type: ResourceType;
  shareScope: ShareScope;
  ownerType: string;
  ownerId: string;
  summary: string;
}

export interface Scenario {
  id: string;
  domainId: string;
  name: string;
  goal: string;
  version: Version;
  agentGroups: AgentGroup[];
}

export interface BusinessDomain {
  id: string;
  name: string;
  description: string;
  scenarios: Scenario[];
  resources: Resource[];
}

export interface CatalogSummary {
  domains: BusinessDomain[];
  scenarios: Scenario[];
  agentGroups: AgentGroup[];
  agents: Agent[];
  resources: Resource[];
  orchestrations: AgentOrchestration[];
  resourceCenter: ResourceCenter;
}

export interface AgentOrchestration {
  agentGroupId: string;
  agentGroupName: string;
  scenarioId: string;
  executionMode: string;
  nodes: OrchestrationNode[];
  edges: OrchestrationEdge[];
}

export interface OrchestrationNode {
  nodeId: string;
  nodeName: string;
  nodeType: string;
  agentId: string;
  description: string;
  resourceIds: string[];
}

export interface OrchestrationEdge {
  edgeId: string;
  fromNodeId: string;
  toNodeId: string;
  condition: string;
  handoffPolicy: string;
}

export interface ResourceUsage {
  resourceId: string;
  resourceName: string;
  type: ResourceType;
  shareScope: ShareScope;
  ownerLabel: string;
  boundAgents: string[];
  boundAgentGroups: string[];
}

export interface ResourceCenter {
  totalResources: number;
  domainSharedResources: number;
  privateResources: number;
  usages: ResourceUsage[];
}

export interface TaskInstance {
  id: string;
  scenarioId: string;
  question: string;
  requester: string;
  status: TaskStatus;
  createdAt: string;
  workflowInstanceId: string;
}

export interface NodeExecution {
  id: string;
  workflowInstanceId: string;
  nodeKey: string;
  nodeName: string;
  status: NodeStatus;
  detail: string;
  updatedAt: string;
}

export interface HumanIntervention {
  id: string;
  workflowInstanceId: string;
  action: string;
  operator: string;
  comment: string;
  createdAt: string;
}

export interface WorkflowInstance {
  id: string;
  taskId: string;
  status: WorkflowStatus;
  summary: string;
  escalationRequired: boolean;
  nodes: NodeExecution[];
  interventions: HumanIntervention[];
}

export interface CreateAgentGroupPayload {
  scenarioId: string;
  name: string;
  description: string;
}

export interface UpdateAgentGroupPayload {
  name: string;
  description: string;
  status: VersionStatus;
}

export interface CreateAgentPayload {
  agentGroupId: string;
  name: string;
  role: string;
  instructions: string;
}

export interface UpdateAgentPayload {
  name: string;
  role: string;
  instructions: string;
}

export interface UpdateAgentBindingsPayload {
  resourceIds: string[];
}

export interface UpdateOrchestrationPayload {
  executionMode: string;
  nodes: OrchestrationNode[];
  edges: OrchestrationEdge[];
}
