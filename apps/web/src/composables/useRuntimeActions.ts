import { message } from 'ant-design-vue';
import { api } from '../services/api';
import type { PageKey } from '../config/navigation';

interface RuntimeActionState {
  activeKey: { value: PageKey };
  creatingSession: { value: boolean };
  sendingSessionId: { value: string | null };
  runtimePreferredSessionId: { value: string | null };
  runtimeSelectedSessionId: { value: string | null };
  selectedWorkflowId: { value: string | null };
}

interface RuntimeActionHelpers {
  findSessionById: (sessionId: string) => { latestWorkflowInstanceId?: string | null } | undefined;
  findWorkflowById: (workflowId: string | null | undefined) => { id: string; status: string } | undefined;
}

export function useRuntimeActions(
  state: RuntimeActionState,
  helpers: RuntimeActionHelpers,
  refresh: (showLoading?: boolean) => Promise<void>,
  errorMessage: (error: unknown, fallback: string) => string,
) {
  async function handleCreateSession(payload: {
    scenarioId: string;
    assistantId: string;
    requester: string;
    openingMessage: string;
  }) {
    state.creatingSession.value = true;
    try {
      const created = await api.createConversationSession(payload);
      state.runtimePreferredSessionId.value = created.id;
      state.runtimeSelectedSessionId.value = created.id;
      await refresh();
      const recoveredSession = helpers.findSessionById(created.id);
      const recoveredWorkflow = helpers.findWorkflowById(recoveredSession?.latestWorkflowInstanceId);
      if (recoveredWorkflow) {
        state.selectedWorkflowId.value = recoveredWorkflow.id;
      }
      state.activeKey.value = 'runtime';
      void message.success('会话已创建');
    } catch (error) {
      void message.error(errorMessage(error, '创建会话失败'));
    } finally {
      state.creatingSession.value = false;
    }
  }

  async function handleSendMessage(payload: { sessionId: string; requester: string; message: string }) {
    state.sendingSessionId.value = payload.sessionId;
    state.runtimePreferredSessionId.value = payload.sessionId;
    state.runtimeSelectedSessionId.value = payload.sessionId;
    try {
      await api.sendConversationMessage(payload.sessionId, {
        requester: payload.requester,
        message: payload.message,
      });
      await refresh();
      const recoveredSession = helpers.findSessionById(payload.sessionId);
      const recoveredWorkflow = helpers.findWorkflowById(recoveredSession?.latestWorkflowInstanceId ?? null);
      if (recoveredWorkflow) {
        state.selectedWorkflowId.value = recoveredWorkflow.id;
        state.activeKey.value = recoveredWorkflow.status === 'WAITING_HUMAN' ? 'workflow' : 'runtime';
      }
    } catch (error) {
      await refresh();
      void message.error(errorMessage(error, '发送消息失败'));
    } finally {
      state.sendingSessionId.value = null;
    }
  }

  function handleSelectRuntimeSession(sessionId: string) {
    state.runtimeSelectedSessionId.value = sessionId;
  }

  function handleSelectWorkflow(workflowId: string) {
    state.selectedWorkflowId.value = workflowId;
  }

  async function handleHumanAction(payload: {
    workflowId: string;
    action: string;
    comment: string;
    operatorId: string;
    attributes: Record<string, string>;
  }) {
    try {
      await api.completeHumanAction(payload.workflowId, {
        action: payload.action,
        comment: payload.comment,
        operatorId: payload.operatorId,
        attributes: payload.attributes,
      });
      await refresh();
      state.activeKey.value = 'workflow';
    } catch (error) {
      void message.error(errorMessage(error, '提交流程人工处理失败'));
    }
  }

  return {
    handleCreateSession,
    handleSendMessage,
    handleSelectRuntimeSession,
    handleSelectWorkflow,
    handleHumanAction,
  };
}
