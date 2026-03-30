import type {
  DecisionType as ContractsDecisionType,
} from '../../../../packages/contracts/src';

export type OrchestrationNodeType = 'START' | 'AGENT' | 'HUMAN' | 'END';
export type DecisionType = ContractsDecisionType;

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
  routeKey: string;
  label: string;
  defaultEdge: boolean;
}

export interface AssistantOrchestration {
  assistantId: string;
  assistantName: string;
  scenarioId: string;
  executionMode: string;
  nodes: OrchestrationNode[];
  edges: OrchestrationEdge[];
}

export interface UpdateOrchestrationPayload {
  executionMode: string;
  nodes: OrchestrationNode[];
  edges: OrchestrationEdge[];
}
