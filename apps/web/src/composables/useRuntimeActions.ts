import { message } from 'ant-design-vue';
import { pagePathByKey } from '../config/navigation';
import { router } from '../router';
import { api } from '../services/api';

interface RuntimeActionState {
  creatingSession: { value: boolean };
  sendingSessionId: { value: string | null };
  runtimePreferredSessionId: { value: string | null };
  runtimeSelectedSessionId: { value: string | null };
}

interface RuntimeActionHelpers {
  findSessionById: (sessionId: string) => { id: string } | undefined;
}

export function useRuntimeActions(
  state: RuntimeActionState,
  helpers: RuntimeActionHelpers,
  refresh: (showLoading?: boolean) => Promise<void>,
  errorMessage: (error: unknown, fallback: string) => string,
) {
  async function handleCreateSession(payload: {
    assistantId: string;
    customerId: string;
    openingMessage: string;
  }) {
    state.creatingSession.value = true;
    try {
      const created = await api.createRuntimeSession({
        assistantId: payload.assistantId,
        customerId: payload.customerId,
        openingMessage: payload.openingMessage.trim(),
      });
      state.runtimePreferredSessionId.value = created.id;
      state.runtimeSelectedSessionId.value = created.id;
      await refresh();
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
      await api.sendRuntimeSessionMessage(payload.sessionId, {
        customerId: payload.customerId,
        message: payload.message,
      });
      await refresh();
      helpers.findSessionById(payload.sessionId);
      void router.push(pagePathByKey.runtime);
    } catch (error) {
      await refresh();
      void message.error(errorMessage(error, '发送消息失败'));
    } finally {
      state.sendingSessionId.value = null;
    }
  }

  async function handleSelectRuntimeSession(sessionId: string) {
    state.runtimePreferredSessionId.value = sessionId;
    state.runtimeSelectedSessionId.value = sessionId;
    await refresh();
  }

  return {
    handleCreateSession,
    handleSendMessage,
    handleSelectRuntimeSession,
  };
}
