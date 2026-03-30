import type {
  AgentTurnLog as ContractsAgentTurnLog,
  AgentTurnState as ContractsAgentTurnState,
  ConversationMessage as ContractsConversationMessage,
  ConversationMessageRequest as ContractsConversationMessageRequest,
  ConversationSession as ContractsConversationSession,
  CreateConversationSessionRequest as ContractsCreateConversationSessionRequest,
  ExecutionCheckpoint as ContractsExecutionCheckpoint,
  HumanIntervention as ContractsHumanIntervention,
  HumanRequest as ContractsHumanRequest,
  HumanTaskSnapshot as ContractsHumanTaskSnapshot,
  NodeExecution as ContractsNodeExecution,
  PauseReasonSnapshot as ContractsPauseReasonSnapshot,
  SessionStatePatch as ContractsSessionStatePatch,
  SessionStatePatchOp as ContractsSessionStatePatchOp,
  SharedSessionState as ContractsSharedSessionState,
  StructuredAgentDecision as ContractsStructuredAgentDecision,
  TaskInstance as ContractsTaskInstance,
  ToolInvocationSnapshot as ContractsToolInvocationSnapshot,
  ToolOutcomeSummary as ContractsToolOutcomeSummary,
  ToolRequest as ContractsToolRequest,
  WorkflowInstance as ContractsWorkflowInstance,
} from '../../../../packages/contracts/src';

export type TaskStatus = 'PENDING' | 'RUNNING' | 'WAITING_HUMAN' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type WorkflowStatus = 'DRAFT' | 'RUNNING' | 'WAITING_HUMAN' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type NodeStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'WAITING_HUMAN' | 'CANCELLED';
export type HumanTaskSource = 'GRAPH_NODE' | 'AGENT_REQUEST';
export type HumanActionType = 'CONFIRM' | 'TERMINATE';

export type TaskInstance = ContractsTaskInstance;
export type ToolOutcomeSummary = ContractsToolOutcomeSummary;
export type SharedSessionState = ContractsSharedSessionState;
export type ToolInvocationSnapshot = ContractsToolInvocationSnapshot;
export type ExecutionCheckpoint = ContractsExecutionCheckpoint;
export type HumanTaskSnapshot = ContractsHumanTaskSnapshot;
export type PauseReasonSnapshot = ContractsPauseReasonSnapshot;
export type NodeExecution = ContractsNodeExecution;
export type HumanIntervention = ContractsHumanIntervention;
export type ToolRequest = ContractsToolRequest;
export type HumanRequest = ContractsHumanRequest;
export type SessionStatePatchOp = ContractsSessionStatePatchOp;
export type SessionStatePatch = ContractsSessionStatePatch;
export type StructuredAgentDecision = ContractsStructuredAgentDecision;
export type AgentTurnLog = ContractsAgentTurnLog;
export type AgentTurnState = ContractsAgentTurnState;
export type WorkflowInstance = ContractsWorkflowInstance;
export type ConversationMessage = ContractsConversationMessage;
export type ConversationSession = ContractsConversationSession;

export type CreateConversationSessionPayload = ContractsCreateConversationSessionRequest;
export type ConversationMessagePayload = ContractsConversationMessageRequest;
