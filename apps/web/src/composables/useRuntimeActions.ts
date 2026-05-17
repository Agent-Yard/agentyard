import { message } from 'ant-design-vue';
import { pagePathByKey } from '../config/navigation';
import { router } from '../router';
import { api } from '../services/api';
import type {
  RuntimeUserDraftMessage,
  SendSessionTurnResponse,
  WebSessionTurnMessageInput,
  SessionRuntimeDetail,
} from '../types';

interface RuntimeActionState {
  creatingSession: { value: boolean };
  sendingSessionId: { value: string | null };
  runtimePreferredSessionId: { value: string | null };
  runtimeSelectedSessionId: { value: string | null };
}

interface RuntimeActionHelpers {
  applyRuntimeSessionDetail: (detail: SessionRuntimeDetail) => void;
  addRuntimeUserDrafts: (drafts: RuntimeUserDraftMessage[]) => void;
  reconcileRuntimeUserDrafts: (response: SendSessionTurnResponse, turnDedupKey: string) => void;
  markRuntimeUserDraftsFailed: (turnDedupKey: string, updatedAt?: string) => void;
}

export function useRuntimeActions(
  state: RuntimeActionState,
  helpers: RuntimeActionHelpers,
  refresh: (showLoading?: boolean) => Promise<void>,
  errorMessage: (error: unknown, fallback: string) => string,
) {
  function textMessageInput(text: string, clientMessageId: string, occurredAt: string): WebSessionTurnMessageInput {
    return {
      clientMessageId,
      occurredAt,
      blocks: [{ type: 'TEXT', text }],
      metadata: {},
    };
  }

  function randomId() {
    return crypto.randomUUID();
  }

  function buildTurnDedupKey(scopeId: string) {
    return `web-turn:${scopeId}:${randomId()}`;
  }

  function normalizeMessageTexts(payload: { message?: string; messages?: string[] }) {
    const messageTexts = payload.messages ?? (payload.message == null ? [] : [payload.message]);
    return messageTexts.map((item) => item.trim()).filter(Boolean);
  }

  async function loadAcceptedSession(response: SendSessionTurnResponse, turnDedupKey: string) {
    helpers.reconcileRuntimeUserDrafts(response, turnDedupKey);
    state.runtimePreferredSessionId.value = response.sessionId;
    state.runtimeSelectedSessionId.value = response.sessionId;
    try {
      const detail = await api.getRuntimeSessionDetail(response.sessionId);
      helpers.applyRuntimeSessionDetail(detail);
      return detail.session.id;
    } catch (error) {
      void message.error(errorMessage(error, '刷新会话失败'));
      return response.sessionId;
    }
  }

  function refreshRuntimeInBackground(fallback: string) {
    void refresh(false).catch((error) => {
      void message.error(errorMessage(error, fallback));
    });
  }

  async function handleStartSession(payload: {
    assistantId: string;
    customerId: string;
    openingMessage: string;
  }) {
    state.creatingSession.value = true;
    let turnDedupKey: string | null = null;
    try {
      const openingMessage = payload.openingMessage.trim();
      if (!openingMessage) {
        throw new Error('开场消息不能为空');
      }
      turnDedupKey = buildTurnDedupKey(payload.assistantId);
      const occurredAt = new Date().toISOString();
      const clientMessageId = randomId();
      const input = textMessageInput(openingMessage, clientMessageId, occurredAt);
      helpers.addRuntimeUserDrafts([{
        draftType: 'USER',
        sessionId: null,
        turnDedupKey,
        clientMessageId,
        requestIndex: 0,
        turnId: null,
        messageId: null,
        turnIndex: null,
        blocks: input.blocks,
        failed: false,
        submittedAt: occurredAt,
        updatedAt: occurredAt,
      }]);
      const response = await api.sendRuntimeSessionTurn({
        assistantId: payload.assistantId,
        customerId: payload.customerId,
        turnDedupKey,
        messages: [input],
        metadata: {},
      });
      const sessionId = await loadAcceptedSession(response, turnDedupKey);
      state.runtimePreferredSessionId.value = sessionId;
      state.runtimeSelectedSessionId.value = sessionId;
      void router.push(pagePathByKey.runtime);
      refreshRuntimeInBackground('刷新会话失败');
      void message.success('Session 已启动');
    } catch (error) {
      if (turnDedupKey) {
        helpers.markRuntimeUserDraftsFailed(turnDedupKey);
      }
      void message.error(errorMessage(error, '启动 Session 失败'));
    } finally {
      state.creatingSession.value = false;
    }
  }

  async function handleSendMessage(payload: {
    sessionId: string;
    customerId: string;
    assistantId: string;
    message?: string;
    messages?: string[];
    turnDedupKey?: string;
  }) {
    state.sendingSessionId.value = payload.sessionId;
    state.runtimePreferredSessionId.value = payload.sessionId;
    state.runtimeSelectedSessionId.value = payload.sessionId;
    const turnDedupKey = payload.turnDedupKey ?? buildTurnDedupKey(payload.sessionId);
    try {
      const messageTexts = normalizeMessageTexts(payload);
      if (!messageTexts.length) {
        throw new Error('消息不能为空');
      }
      const occurredAt = new Date().toISOString();
      const messages = messageTexts.map((text) => textMessageInput(text, randomId(), occurredAt));
      helpers.addRuntimeUserDrafts(messages.map((input, requestIndex) => ({
        draftType: 'USER',
        sessionId: payload.sessionId,
        turnDedupKey,
        clientMessageId: input.clientMessageId!,
        requestIndex,
        turnId: null,
        messageId: null,
        turnIndex: null,
        blocks: input.blocks,
        failed: false,
        submittedAt: occurredAt,
        updatedAt: occurredAt,
      })));
      const response = await api.sendRuntimeSessionTurn({
        sessionId: payload.sessionId,
        customerId: payload.customerId,
        assistantId: payload.assistantId,
        turnDedupKey,
        messages,
        metadata: {},
      });
      const sessionId = await loadAcceptedSession(response, turnDedupKey);
      state.runtimePreferredSessionId.value = sessionId;
      state.runtimeSelectedSessionId.value = sessionId;
      void router.push(pagePathByKey.runtime);
      refreshRuntimeInBackground('刷新会话失败');
    } catch (error) {
      helpers.markRuntimeUserDraftsFailed(turnDedupKey);
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
    handleStartSession,
    handleSendMessage,
    handleSelectRuntimeSession,
  };
}
