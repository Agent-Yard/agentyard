import { computed, ref, type Ref } from 'vue';
import type {
  CatalogSummary,
  SessionRuntimeDetail,
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
  let refreshInFlight = false;

  const selectedKeys = computed(() => [currentPageKey.value]);
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
    } finally {
      refreshInFlight = false;
      if (showLoading) {
        loading.value = false;
      }
    }
  }

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
    selectedKeys,
    currentPageMeta,
    currentSectionMeta,
    canManageGovernance,
    errorMessage,
    findSessionById,
    refresh,
  };
}
