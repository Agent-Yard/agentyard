export type ResourceType = 'TOOL' | 'KNOWLEDGE_BASE' | 'LLM_MODEL' | 'PROMPT_TEMPLATE';
export type ToolProviderType = 'HTTP' | 'MCP';
export type ShareScope = 'PRIVATE' | 'DOMAIN_SHARED';
export type VersionStatus = 'DRAFT' | 'PUBLISHED';
export type TaskStatus = 'PENDING' | 'RUNNING' | 'WAITING_HUMAN' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type WorkflowStatus = 'DRAFT' | 'RUNNING' | 'WAITING_HUMAN' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type NodeStatus = 'PENDING' | 'RUNNING' | 'WAITING_HUMAN' | 'COMPLETED' | 'FAILED';
export type OrchestrationNodeType = 'START' | 'AGENT' | 'HUMAN' | 'END';

export interface HumanNodeConfig {
  title: string;
  instruction: string;
  expectedAction: string;
  resumeRouteKey: string;
}

export interface OrchestrationNode {
  nodeKey: string;
  nodeName: string;
  nodeType: OrchestrationNodeType;
  description: string;
  agentId: string | null;
  humanNode: HumanNodeConfig | null;
}

export interface OrchestrationEdge {
  edgeKey: string;
  sourceNodeKey: string;
  targetNodeKey: string;
  routeKey: string | null;
  label: string;
  defaultEdge: boolean;
}

export interface ExecutionCheckpoint {
  checkpointId: string;
  currentNodeKey: string;
  waitingNodeKey: string;
  statePayload: string;
  resumeCount: number;
}

export interface HumanTaskSnapshot {
  nodeKey: string;
  title: string;
  instruction: string;
  expectedAction: string;
}

export interface ToolInvocationSnapshot {
  id: string;
  providerType: string;
  resourceId: string;
  resourceName: string;
  operation: string;
  status: string;
  detail: string;
  createdAt: string;
}

export interface ToolOutcomeSummary {
  toolResourceId: string;
  toolResourceName: string;
  operation: string;
  providerType: string;
  status: string;
  externalReference: string;
  recommendedAction: string;
  detail: string;
}

export interface HumanActionRequest {
  action: string;
  comment: string;
  operatorId: string;
  attributes: Record<string, string>;
}

export interface TaskLaunchRequest {
  scenarioId: string;
  assistantId: string;
  question: string;
  requester: string;
}
