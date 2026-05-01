import { message } from 'ant-design-vue';
import { pagePathByKey } from '../config/navigation';
import { router } from '../router';
import { api } from '../services/api';
import type { SessionMessageInput, SessionRuntimeSession } from '../types';

interface RuntimeActionState {
  creatingSession: { value: boolean };
  sendingSessionId: { value: string | null };
  runtimePreferredSessionId: { value: string | null };
  runtimeSelectedSessionId: { value: string | null };
}

interface RuntimeActionHelpers {
  upsertRuntimeSession: (session: SessionRuntimeSession) => void;
}

export function useRuntimeActions(
  state: RuntimeActionState,
  helpers: RuntimeActionHelpers,
  refresh: (showLoading?: boolean) => Promise<void>,
  errorMessage: (error: unknown, fallback: string) => string,
) {
  function textMessageInput(text: string): SessionMessageInput {
    return {
      blocks: [{ type: 'TEXT', text }],
      metadata: {},
    };
  }

  function refreshRuntimeInBackground(fallback: string) {
    void refresh(false).catch((error) => {
      void message.error(errorMessage(error, fallback));
    });
  }

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
        openingMessage: payload.openingMessage.trim() ? textMessageInput(payload.openingMessage.trim()) : null,
      });
      helpers.upsertRuntimeSession(created);
      state.runtimePreferredSessionId.value = created.id;
      state.runtimeSelectedSessionId.value = created.id;
      void router.push(pagePathByKey.runtime);
      refreshRuntimeInBackground('刷新会话失败');
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
      const session = await api.sendRuntimeSessionMessage(payload.sessionId, {
        customerId: payload.customerId,
        message: textMessageInput(payload.message),
      });
      helpers.upsertRuntimeSession(session);
      state.runtimePreferredSessionId.value = session.id;
      state.runtimeSelectedSessionId.value = session.id;
      void router.push(pagePathByKey.runtime);
      refreshRuntimeInBackground('刷新会话失败');
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
