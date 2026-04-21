import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue';
import type {
  CatalogSummary,
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
    applyRuntimeSessionDetail(event.detail);
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
      } else if (runtimePreferredSessionId.value && sessionList.some((item) => item.id === runtimePreferredSessionId.value)) {
        runtimeSelectedSessionId.value = runtimePreferredSessionId.value;
      } else if (!runtimeSelectedSessionId.value || !sessionList.some((item) => item.id === runtimeSelectedSessionId.value)) {
        runtimeSelectedSessionId.value = sessionList[0].id;
      }
      runtimeSessionDetail.value = runtimeSelectedSessionId.value
        ? await api.getRuntimeSessionDetail(runtimeSelectedSessionId.value)
        : null;
      if (runtimeSessionDetail.value) {
        runtimeStreamLastEventId = `session-snapshot:${runtimeSessionDetail.value.session.id}:${runtimeSessionDetail.value.session.latestEventSequence}`;
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
    runtimeStreamConnected,
    selectedKeys,
    currentPageMeta,
    currentSectionMeta,
    canManageGovernance,
    errorMessage,
    findSessionById,
    applyRuntimeSessionDetail,
    refresh,
  };
}
