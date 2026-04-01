import { message } from 'ant-design-vue';
import { pagePathByKey } from '../config/navigation';
import { router } from '../router';
import { api } from '../services/api';

interface RuntimeActionState {
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
    customerId: string;
    openingMessage: string;
  }) {
    state.creatingSession.value = true;
    try {
      const created = await api.createConversationSession({
        scenarioId: payload.scenarioId,
        assistantId: payload.assistantId,
        customerId: payload.customerId,
        openingMessage: payload.openingMessage.trim()
          ? {
              payloadType: 'TEXT',
              payload: {
                text: payload.openingMessage.trim(),
              },
            }
          : null,
      });
      state.runtimePreferredSessionId.value = created.id;
      state.runtimeSelectedSessionId.value = created.id;
      await refresh();
      const recoveredSession = helpers.findSessionById(created.id);
      const recoveredWorkflow = helpers.findWorkflowById(recoveredSession?.latestWorkflowInstanceId);
      if (recoveredWorkflow) {
        state.selectedWorkflowId.value = recoveredWorkflow.id;
      }
      void router.push(pagePathByKey.runtime);
      void message.success('会话已创建');
    } catch (error) {
      void message.error(errorMessage(error, '创建会话失败'));
    } finally {
      state.creatingSession.value = false;
    }
  }

  async function handleSendMessage(payload: { sessionId: string; customerId: string; message: string }) {
    state.sendingSessionId.value = payload.sessionId;
    state.runtimePreferredSessionId.value = payload.sessionId;
    state.runtimeSelectedSessionId.value = payload.sessionId;
    try {
      await api.sendConversationMessage(payload.sessionId, {
        customerId: payload.customerId,
        payloadType: 'TEXT',
        payload: {
          text: payload.message,
        },
      });
      await refresh();
      const recoveredSession = helpers.findSessionById(payload.sessionId);
      const recoveredWorkflow = helpers.findWorkflowById(recoveredSession?.latestWorkflowInstanceId ?? null);
      if (recoveredWorkflow) {
        state.selectedWorkflowId.value = recoveredWorkflow.id;
        void router.push(recoveredWorkflow.status === 'WAITING_RESUME' ? pagePathByKey.workflow : pagePathByKey.runtime);
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

  async function handleResumeAction(payload: {
    workflowId: string;
    type: string;
    comment: string;
    userId: string;
    attributes: Record<string, string>;
  }) {
    try {
      await api.completeResumeAction(payload.workflowId, {
        type: payload.type,
        comment: payload.comment,
        userId: payload.userId,
        attributes: payload.attributes,
      });
      await refresh();
      void router.push(pagePathByKey.workflow);
    } catch (error) {
      void message.error(errorMessage(error, '提交流程恢复动作失败'));
    }
  }

  async function handleInteractionReturn(payload: {
    interactionTaskId: string;
    returnToken: string;
    providerReference: string | null;
    dedupeKey: string | null;
    queryPayload: Record<string, unknown>;
  }) {
    try {
      const interaction = await api.acknowledgeExternalInteractionReturn(payload.interactionTaskId, {
        returnToken: payload.returnToken,
        providerReference: payload.providerReference,
        dedupeKey: payload.dedupeKey,
        payload: payload.queryPayload,
      });
      state.runtimePreferredSessionId.value = interaction.sessionId;
      state.runtimeSelectedSessionId.value = interaction.sessionId;
      state.selectedWorkflowId.value = interaction.workflowInstanceId;
      await refresh();
      void router.push(pagePathByKey.runtime);
      void message.success('已接收外部交互回跳，流程继续处理中');
    } catch (error) {
      void message.error(errorMessage(error, '处理外部交互回跳失败'));
    }
  }

  return {
    handleCreateSession,
    handleSendMessage,
    handleSelectRuntimeSession,
    handleSelectWorkflow,
    handleResumeAction,
    handleInteractionReturn,
  };
}
