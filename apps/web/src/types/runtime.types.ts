import type {
  AgentTurnLog as ContractsAgentTurnLog,
  AgentTurnState as ContractsAgentTurnState,
  ConversationMessage as ContractsConversationMessage,
  ConversationMessageRequest as ContractsConversationMessageRequest,
  ConversationPayloadType as ContractsConversationPayloadType,
  ConversationSession as ContractsConversationSession,
  CreateConversationSessionRequest as ContractsCreateConversationSessionRequest,
  ExecutionCheckpoint as ContractsExecutionCheckpoint,
  ExternalInteractionCallbackRequest as ContractsExternalInteractionCallbackRequest,
  ExternalInteractionEvent as ContractsExternalInteractionEvent,
  ExternalInteractionMessagePayload as ContractsExternalInteractionMessagePayload,
  ExternalInteractionResult as ContractsExternalInteractionResult,
  ExternalInteractionReturnRequest as ContractsExternalInteractionReturnRequest,
  ExternalInteractionStatus as ContractsExternalInteractionStatus,
  ExternalInteractionTask as ContractsExternalInteractionTask,
  ExternalInteractionType as ContractsExternalInteractionType,
  ResumeActionRequest as ContractsResumeActionRequest,
  ResumeIntervention as ContractsResumeIntervention,
  ResumeContextSnapshot as ContractsResumeContextSnapshot,
  HumanRequest as ContractsHumanRequest,
  ModelHitSnapshot as ContractsModelHitSnapshot,
  ResumeTaskSnapshot as ContractsResumeTaskSnapshot,
  NodeExecution as ContractsNodeExecution,
  PauseReasonSnapshot as ContractsPauseReasonSnapshot,
  PauseSource as ContractsPauseSource,
  ResumeSource as ContractsResumeSource,
  SessionStatePatch as ContractsSessionStatePatch,
  SessionStatePatchOp as ContractsSessionStatePatchOp,
  SharedSessionState as ContractsSharedSessionState,
  StructuredAgentDecision as ContractsStructuredAgentDecision,
  TaskInstance as ContractsTaskInstance,
  TextMessagePayload as ContractsTextMessagePayload,
  ToolInvocationSnapshot as ContractsToolInvocationSnapshot,
  ToolOutcomeSummary as ContractsToolOutcomeSummary,
  ToolRequest as ContractsToolRequest,
  WorkflowInstance as ContractsWorkflowInstance,
} from '../../../../packages/contracts/src';

export type TaskStatus = 'PENDING' | 'RUNNING' | 'WAITING_RESUME' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type WorkflowStatus = 'DRAFT' | 'RUNNING' | 'WAITING_RESUME' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type NodeStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'WAITING_RESUME' | 'CANCELLED';
export type PauseSource = ContractsPauseSource;
export type ResumeSource = ContractsResumeSource;
export type ResumeActionType = 'CONTINUE' | 'TERMINATE';
export type ExternalInteractionType = ContractsExternalInteractionType;
export type ExternalInteractionStatus = ContractsExternalInteractionStatus;

export type TaskInstance = ContractsTaskInstance;
export type ToolOutcomeSummary = ContractsToolOutcomeSummary;
export type SharedSessionState = ContractsSharedSessionState;
export type ToolInvocationSnapshot = ContractsToolInvocationSnapshot;
export type ExecutionCheckpoint = ContractsExecutionCheckpoint;
export type ResumeContextSnapshot = ContractsResumeContextSnapshot;
export type ResumeTaskSnapshot = ContractsResumeTaskSnapshot;
export type PauseReasonSnapshot = ContractsPauseReasonSnapshot;
export type NodeExecution = ContractsNodeExecution;
export type ResumeIntervention = ContractsResumeIntervention;
export type ToolRequest = ContractsToolRequest;
export type HumanRequest = ContractsHumanRequest;
export type ModelHitSnapshot = ContractsModelHitSnapshot;
export type SessionStatePatchOp = ContractsSessionStatePatchOp;
export type SessionStatePatch = ContractsSessionStatePatch;
export type StructuredAgentDecision = ContractsStructuredAgentDecision;
export type AgentTurnLog = ContractsAgentTurnLog;
export type AgentTurnState = ContractsAgentTurnState;
export type WorkflowInstance = ContractsWorkflowInstance;
export type ConversationPayloadType = ContractsConversationPayloadType;
export type TextMessagePayload = ContractsTextMessagePayload;
export type ExternalInteractionResult = ContractsExternalInteractionResult;
export type ExternalInteractionEvent = ContractsExternalInteractionEvent;
export type ExternalInteractionTask = ContractsExternalInteractionTask;
export type ExternalInteractionMessagePayload = ContractsExternalInteractionMessagePayload;
export type ConversationMessage = ContractsConversationMessage;
export type ConversationSession = ContractsConversationSession;

export type CreateConversationSessionPayload = ContractsCreateConversationSessionRequest;
export type ConversationMessagePayload = ContractsConversationMessageRequest;
export type ExternalInteractionReturnPayload = ContractsExternalInteractionReturnRequest;
export type ExternalInteractionCallbackPayload = ContractsExternalInteractionCallbackRequest;
export type ResumeActionPayload = ContractsResumeActionRequest;
