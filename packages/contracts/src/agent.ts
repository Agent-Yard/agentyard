import type { CollaborationActor } from './task';
/** Persistent Agent contexts and collaboration transport. Template references never grant authority. */
export type AgentSessionStatus = 'ACTIVE' | 'PAUSED' | 'CLOSED';
export type ActivationStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
export type MessageKind = 'MESSAGE' | 'DELEGATION' | 'RESULT' | 'QUESTION' | 'EVENT' | 'CONTROL';
export type ScopeKind = 'PLATFORM' | 'ORGANIZATION' | 'TASK' | 'SESSION' | 'TEMPLATE' | 'RESOURCE' | 'PARTICIPANT';
export interface GrantRequest { operation: string; scopeKind: ScopeKind; scopeId: string; delegable: boolean; expiresAt: string | null }
export interface AgentSession { id: string; participantId: string; templateVersionId: string; name: string; status: AgentSessionStatus; createdBy: string; replyToParticipantId: string | null; workMemory: Record<string, unknown>; revision: number; createdAt: string; updatedAt: string }
export interface CollaborationMessage { id: string; senderId: string; recipientId: string; taskId: string | null; replyToMessageId: string | null; kind: MessageKind; body: Record<string, unknown>; createdAt: string }
export interface Activation { id: string; sessionId: string; inputMessageId: string; status: ActivationStatus; failureReason: string | null; createdAt: string; updatedAt: string; completion?: 'COMPLETED' | 'WAITING' | 'BLOCKED' | null }
export interface ToolRun { id: string; sessionId: string; participantId: string; activationId: string; taskId: string | null; playbookVersionId: string; status: 'QUEUED' | 'RUNNING' | 'WAITING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'; input: Record<string, unknown>; result: Record<string, unknown>; waiting: Record<string, unknown>; failureReason: string | null; createdAt: string; updatedAt: string }
export interface AgentSchedule { id: string; sessionId: string; taskId: string | null; dueAt: string; instruction: string; intervalSeconds: number | null; active: boolean; createdBy: string; createdAt: string }
export interface Reception { session: AgentSession; humanParticipantId: string; messages: CollaborationMessage[]; latestActivation: Activation | null; participants: CollaborationActor[]; previousCursor: string | null }
export interface AgentSubscription { id:string; sessionId:string; scopeKind:ScopeKind; scopeId:string; eventKinds:string[]; active:boolean; createdBy:string; createdAt:string }
export interface AgentSessionDetail { session: AgentSession; messages: CollaborationMessage[]; activations: Activation[]; toolRuns: ToolRun[]; schedules: AgentSchedule[]; subscriptions:AgentSubscription[] }
export interface AgentExecutionStatus { sessionId: string; lifecycle: AgentSessionStatus; execution: 'IDLE' | 'QUEUED' | 'RUNNING' | 'WAITING' | 'BLOCKED' | 'FAILED' | 'CANCELLED'; updatedAt: string; historyReadable: boolean }
export interface SendMessage { requestId: string; recipientId: string; taskId?: string | null; replyToMessageId?: string | null; kind: MessageKind; body: Record<string, unknown> }
