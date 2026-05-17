import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue';
import type {
  CatalogSummary,
  RuntimeReplyDraftMessage,
  RuntimeDraftMessage,
  RuntimeUserDraftMessage,
  SendSessionTurnResponse,
  SessionProgressEvent,
  SessionRuntimeDetail,
  SessionRuntimeStreamEvent,
  SessionRuntimeSession,
  UserSession,
} from '../types';
import { pageMeta, sectionMeta } from '../config/navigation';
import type { PageKey, SectionKey } from '../config/navigation';
import { api } from '../services/api';

export function useAppState(currentPageKey: Ref<PageKey>) {
  const loading = ref(true);
  const creatingSession = ref(false);
  const sendingSessionId = ref<string | null>(null);
  const runtimePreferredSessionId = ref<string | null>(null);
  const runtimeSelectedSessionId = ref<string | null>(null);
  const knowledgeLibraryPreferredKnowledgeBaseId = ref<string | null>(null);
  const resourceLibraryPreferredResourceId = ref<string | null>(null);
  const resourceLibraryPreferredVersionId = ref<string | null>(null);
  const catalogRevision = ref(0);
  const openKeys = ref<SectionKey[]>(['design', 'build', 'knowledge', 'resource', 'runtime-observe']);
  const session = ref<UserSession | null>(null);
  const catalog = ref<CatalogSummary | null>(null);
  const conversationSessions = ref<SessionRuntimeSession[]>([]);
  const runtimeSessionDetail = ref<SessionRuntimeDetail | null>(null);
  const runtimeDrafts = ref<RuntimeDraftMessage[]>([]);
  const runtimeProgress = ref<SessionProgressEvent[]>([]);
  const runtimeStreamConnected = ref(false);
  let refreshInFlight = false;
  let runtimeStream: EventSource | null = null;
  let runtimeStreamSessionId: string | null = null;
  let runtimeStreamLastEventId: string | null = null;
  let runtimePollingTimer: number | null = null;

  const selectedKeys = computed(() => [currentPageKey.value === 'playbook-editor' ? 'playbook' : currentPageKey.value]);
  const currentPageMeta = computed(() => pageMeta[currentPageKey.value]);
  const currentSectionMeta = computed(() => sectionMeta[currentPageMeta.value.section]);
  const canManageGovernance = computed(() => session.value?.currentRole !== 'BUSINESS_USER');

  function errorMessage(error: unknown, fallback: string) {
    if (error instanceof Error && error.message) {
      return error.message;
    }
    return fallback;
  }

  function findSessionById(sessionId: string) {
    return conversationSessions.value.find((item) => item.id === sessionId);
  }

  function upsertRuntimeSession(session: SessionRuntimeSession) {
    const existingIndex = conversationSessions.value.findIndex((item) => item.id === session.id);
    if (existingIndex >= 0) {
      conversationSessions.value.splice(existingIndex, 1, session);
      return;
    }
    conversationSessions.value.unshift(session);
  }

  function applyRuntimeSessionDetail(detail: SessionRuntimeDetail) {
    upsertRuntimeSession(detail.session);
    if (runtimeSelectedSessionId.value === detail.session.id) {
      runtimeSessionDetail.value = detail;
    }
    runtimeDrafts.value = reconcileRuntimeDraftsWithDetail(runtimeDrafts.value, detail);
  }

  function applyRuntimeStreamEvent(event: SessionRuntimeStreamEvent) {
    runtimeStreamLastEventId = event.id;
    if (event.type === 'SESSION_SNAPSHOT' || event.type === 'SESSION_UPDATED') {
      applyRuntimeSessionDetail(event.detail);
      return;
    }
    if (event.type === 'SESSION_PROGRESS') {
      runtimeProgress.value = [...runtimeProgress.value.filter((item) => item.id !== event.id), event].slice(-80);
      return;
    }
    if (event.type === 'SESSION_REPLY_DRAFT') {
      applyRuntimeReplyDraft(event);
      return;
    }
    if (event.type === 'SESSION_STREAM_ERROR') {
      const progress: SessionProgressEvent = {
        id: event.id,
        type: 'SESSION_PROGRESS',
        occurredAt: event.occurredAt,
        sessionId: event.sessionId,
        turnId: event.turnId,
        visibility: 'OPERATOR',
        phase: 'ERROR',
        status: 'FAILED',
        title: event.message,
        detail: event.detail,
      };
      runtimeProgress.value = [
        ...runtimeProgress.value,
        progress,
      ].slice(-80);
      runtimeDrafts.value = markRuntimeDraftsForStreamError(runtimeDrafts.value, event);
    }
  }

  function applyRuntimeReplyDraft(event: Extract<SessionRuntimeStreamEvent, { type: 'SESSION_REPLY_DRAFT' }>) {
    if (event.blockType !== 'TEXT') {
      return;
    }
    const existing = runtimeDrafts.value.find((draft): draft is RuntimeReplyDraftMessage => sameRuntimeDraft(draft, event));
    if (event.operation === 'DISCARD') {
      runtimeDrafts.value = runtimeDrafts.value.filter((draft) => !sameRuntimeDraft(draft, event));
      return;
    }
    const current: RuntimeDraftMessage = existing ?? {
      draftType: 'REPLY',
      sessionId: event.sessionId,
      turnId: event.turnId,
      replyMessageId: event.replyMessageId,
      text: '',
      failed: false,
      updatedAt: event.occurredAt,
    };
    const text = current.text + (event.delta ?? '');
    const next = { ...current, text, failed: false, updatedAt: event.occurredAt };
    runtimeDrafts.value = [
      ...runtimeDrafts.value.filter((draft) => !sameRuntimeDraft(draft, event)),
      next,
    ];
  }

  function addRuntimeUserDrafts(drafts: RuntimeUserDraftMessage[]) {
    runtimeDrafts.value = mergeRuntimeUserDrafts(runtimeDrafts.value, drafts);
  }

  function reconcileRuntimeUserDrafts(response: SendSessionTurnResponse, turnDedupKey: string) {
    runtimeDrafts.value = reconcileRuntimeDraftsWithTurnAcceptance(runtimeDrafts.value, response, turnDedupKey);
  }

  function markRuntimeUserDraftsFailed(turnDedupKey: string, updatedAt = new Date().toISOString()) {
    runtimeDrafts.value = runtimeDrafts.value.map((draft) => {
      if (draft.draftType !== 'USER' || draft.turnDedupKey !== turnDedupKey) {
        return draft;
      }
      return { ...draft, failed: true, updatedAt };
    });
  }

  function stopRuntimePolling() {
    if (runtimePollingTimer != null) {
      window.clearInterval(runtimePollingTimer);
      runtimePollingTimer = null;
    }
  }

  function startRuntimePolling(sessionId: string) {
    if (runtimePollingTimer != null) {
      return;
    }
    runtimePollingTimer = window.setInterval(() => {
      void api.getRuntimeSessionDetail(sessionId)
        .then(applyRuntimeSessionDetail)
        .catch(() => {
          runtimeStreamConnected.value = false;
        });
    }, 5000);
  }

  function closeRuntimeStream() {
    runtimeStream?.close();
    runtimeStream = null;
    runtimeStreamSessionId = null;
    runtimeStreamConnected.value = false;
    stopRuntimePolling();
  }

  function ensureRuntimeStream(sessionId: string) {
    if (runtimeStream && runtimeStreamSessionId === sessionId) {
      return;
    }
    closeRuntimeStream();
    runtimeStreamSessionId = sessionId;
    startRuntimePolling(sessionId);
    runtimeStream = api.openRuntimeSessionStream(sessionId, {
      lastEventId: runtimeStreamLastEventId,
      onEvent: applyRuntimeStreamEvent,
      onOpen: () => {
        runtimeStreamConnected.value = true;
        stopRuntimePolling();
      },
      onError: () => {
        runtimeStreamConnected.value = false;
        startRuntimePolling(sessionId);
      },
    });
  }

  async function refresh(showLoading = false) {
    if (!showLoading && refreshInFlight) {
      return;
    }
    refreshInFlight = true;
    if (showLoading) {
      loading.value = true;
    }
    try {
      const sessionData = await api.getSession();
      session.value = sessionData;
      const [catalogData, sessionList] = await Promise.all([
        api.getCatalogSummary(),
        api.getRuntimeSessions(),
      ]);
      catalog.value = catalogData;
      catalogRevision.value += 1;
      conversationSessions.value = sessionList;
      if (!sessionList.length) {
        runtimeSelectedSessionId.value = null;
        runtimeSessionDetail.value = null;
        runtimeDrafts.value = [];
        runtimeProgress.value = [];
      } else if (runtimePreferredSessionId.value && sessionList.some((item) => item.id === runtimePreferredSessionId.value)) {
        runtimeSelectedSessionId.value = runtimePreferredSessionId.value;
      } else if (!runtimeSelectedSessionId.value || !sessionList.some((item) => item.id === runtimeSelectedSessionId.value)) {
        runtimeSelectedSessionId.value = sessionList[0].id;
      }
      runtimeSessionDetail.value = runtimeSelectedSessionId.value
        ? await api.getRuntimeSessionDetail(runtimeSelectedSessionId.value)
        : null;
      if (runtimeSessionDetail.value) {
        runtimeStreamLastEventId = `session-snapshot:${runtimeSessionDetail.value.session.id}:${runtimeSessionDetail.value.session.latestMessageSequence}:${runtimeSessionDetail.value.session.latestEventSequence}`;
      }
    } finally {
      refreshInFlight = false;
      if (showLoading) {
        loading.value = false;
      }
    }
  }

  watch(
    () => [currentPageKey.value, runtimeSelectedSessionId.value] as const,
    ([pageKey, sessionId]) => {
      if (pageKey !== 'runtime' || !sessionId) {
        closeRuntimeStream();
        return;
      }
      ensureRuntimeStream(sessionId);
    },
    { immediate: true },
  );

  onBeforeUnmount(() => {
    closeRuntimeStream();
  });

  return {
    loading,
    creatingSession,
    sendingSessionId,
    runtimePreferredSessionId,
    runtimeSelectedSessionId,
    knowledgeLibraryPreferredKnowledgeBaseId,
    resourceLibraryPreferredResourceId,
    resourceLibraryPreferredVersionId,
    catalogRevision,
    openKeys,
    session,
    catalog,
    conversationSessions,
    runtimeSessionDetail,
    runtimeDrafts,
    runtimeProgress,
    runtimeStreamConnected,
    selectedKeys,
    currentPageMeta,
    currentSectionMeta,
    canManageGovernance,
    errorMessage,
    findSessionById,
    upsertRuntimeSession,
    applyRuntimeSessionDetail,
    addRuntimeUserDrafts,
    reconcileRuntimeUserDrafts,
    markRuntimeUserDraftsFailed,
    refresh,
  };
}

export function mergeRuntimeUserDrafts(
  currentDrafts: RuntimeDraftMessage[],
  incomingDrafts: RuntimeUserDraftMessage[],
): RuntimeDraftMessage[] {
  if (!incomingDrafts.length) {
    return currentDrafts;
  }
  const incomingByKey = new Map(incomingDrafts.map((draft) => [userDraftKey(draft), draft]));
  const merged = currentDrafts.map((draft) => {
    if (draft.draftType !== 'USER') {
      return draft;
    }
    return incomingByKey.get(userDraftKey(draft)) ?? draft;
  });
  const existingKeys = new Set(merged.filter(isUserDraft).map(userDraftKey));
  for (const draft of incomingDrafts) {
    if (!existingKeys.has(userDraftKey(draft))) {
      merged.push(draft);
      existingKeys.add(userDraftKey(draft));
    }
  }
  return merged;
}

export function reconcileRuntimeDraftsWithTurnAcceptance(
  drafts: RuntimeDraftMessage[],
  response: SendSessionTurnResponse,
  turnDedupKey: string,
): RuntimeDraftMessage[] {
  const allocationsByClientMessageId = new Map(
    response.acceptedMessageAllocations
      .filter((allocation) => allocation.clientMessageId)
      .map((allocation) => [allocation.clientMessageId!, allocation]),
  );
  const allocationsByRequestIndex = new Map(
    response.acceptedMessageAllocations.map((allocation) => [allocation.requestIndex, allocation]),
  );
  return drafts.map((draft) => {
    if (draft.draftType !== 'USER' || draft.turnDedupKey !== turnDedupKey) {
      return draft;
    }
    const allocation = allocationsByClientMessageId.get(draft.clientMessageId)
      ?? allocationsByRequestIndex.get(draft.requestIndex);
    if (!allocation) {
      return { ...draft, sessionId: response.sessionId, turnId: response.turnId, failed: false };
    }
    return {
      ...draft,
      sessionId: response.sessionId,
      turnId: response.turnId,
      messageId: allocation.messageId,
      turnIndex: allocation.turnIndex,
      failed: false,
    };
  });
}

export function reconcileRuntimeDraftsWithDetail(
  drafts: RuntimeDraftMessage[],
  detail: SessionRuntimeDetail,
): RuntimeDraftMessage[] {
  const durableMessageIds = new Set(detail.messages.map((message) => message.messageId));
  const durableClientMessageIds = new Set(
    detail.messages
      .map((message) => message.clientMessageId)
      .filter((clientMessageId): clientMessageId is string => !!clientMessageId),
  );
  if (!durableMessageIds.size && !durableClientMessageIds.size) {
    return drafts;
  }
  return drafts.filter((draft) => {
    if (draft.sessionId !== detail.session.id) {
      return true;
    }
    if (draft.draftType === 'REPLY') {
      return !durableMessageIds.has(draft.replyMessageId);
    }
    return !(
      (draft.messageId && durableMessageIds.has(draft.messageId))
      || durableClientMessageIds.has(draft.clientMessageId)
    );
  });
}

export function markRuntimeDraftsForStreamError(
  drafts: RuntimeDraftMessage[],
  event: Extract<SessionRuntimeStreamEvent, { type: 'SESSION_STREAM_ERROR' }>,
): RuntimeDraftMessage[] {
  return drafts.map((draft) =>
    draft.draftType === 'REPLY'
      && draft.sessionId === event.sessionId
      && draft.turnId === event.turnId
      ? { ...draft, failed: true, updatedAt: event.occurredAt }
      : draft,
  );
}

function sameRuntimeDraft(
  draft: RuntimeDraftMessage,
  event: Extract<SessionRuntimeStreamEvent, { type: 'SESSION_REPLY_DRAFT' }>,
): draft is RuntimeReplyDraftMessage {
  return draft.draftType === 'REPLY'
    && draft.sessionId === event.sessionId
    && draft.turnId === event.turnId
    && draft.replyMessageId === event.replyMessageId;
}

function isUserDraft(draft: RuntimeDraftMessage): draft is RuntimeUserDraftMessage {
  return draft.draftType === 'USER';
}

function userDraftKey(draft: RuntimeUserDraftMessage) {
  return `${draft.turnDedupKey}:${draft.clientMessageId}`;
}
