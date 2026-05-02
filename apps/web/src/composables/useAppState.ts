import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue';
import type {
  CatalogSummary,
  RuntimeDraftMessage,
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
  }

  function applyRuntimeStreamEvent(event: SessionRuntimeStreamEvent) {
    runtimeStreamLastEventId = event.id;
    if (event.type === 'SESSION_SNAPSHOT' || event.type === 'SESSION_UPDATED') {
      applyRuntimeSessionDetail(event.detail);
      runtimeDrafts.value = runtimeDrafts.value.filter((draft) => draft.sessionId !== event.sessionId);
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
      runtimeDrafts.value = runtimeDrafts.value.map((draft) =>
        draft.sessionId === event.sessionId && draft.turnId === event.turnId
          ? { ...draft, failed: true, updatedAt: event.occurredAt }
          : draft,
      );
    }
  }

  function applyRuntimeReplyDraft(event: Extract<SessionRuntimeStreamEvent, { type: 'SESSION_REPLY_DRAFT' }>) {
    if (event.blockType !== 'TEXT') {
      return;
    }
    const existing = runtimeDrafts.value.find((draft) => draft.sessionId === event.sessionId && draft.turnId === event.turnId);
    if (event.operation === 'DISCARD') {
      runtimeDrafts.value = runtimeDrafts.value.filter((draft) => !(draft.sessionId === event.sessionId && draft.turnId === event.turnId));
      return;
    }
    const current: RuntimeDraftMessage = existing ?? {
      sessionId: event.sessionId,
      turnId: event.turnId,
      messageId: event.messageId,
      text: '',
      failed: false,
      updatedAt: event.occurredAt,
    };
    const text = event.operation === 'SNAPSHOT'
      ? event.text ?? current.text
      : current.text + (event.delta ?? '');
    const next = { ...current, text, failed: false, updatedAt: event.occurredAt };
    runtimeDrafts.value = [
      ...runtimeDrafts.value.filter((draft) => !(draft.sessionId === event.sessionId && draft.turnId === event.turnId)),
      next,
    ];
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
      const [sessionData, catalogData, sessionList] = await Promise.all([
        api.getSession(),
        api.getCatalogSummary(),
        api.getRuntimeSessions(),
      ]);
      session.value = sessionData;
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
    refresh,
  };
}
