import { computed, ref } from 'vue';
import type {
  CatalogSummary,
  ConversationSession,
  TaskInstance,
  UserSession,
  WorkflowInstance,
} from '../types';
import { pageMeta, sectionMeta } from '../config/navigation';
import type { PageKey, SectionKey } from '../config/navigation';
import { api } from '../services/api';

export function useAppState() {
  const loading = ref(true);
  const creatingSession = ref(false);
  const sendingSessionId = ref<string | null>(null);
  const runtimePreferredSessionId = ref<string | null>(null);
  const runtimeSelectedSessionId = ref<string | null>(null);
  const selectedWorkflowId = ref<string | null>(null);
  const knowledgeLibraryPreferredKnowledgeBaseId = ref<string | null>(null);
  const resourceLibraryPreferredResourceId = ref<string | null>(null);
  const resourceLibraryPreferredVersionId = ref<string | null>(null);
  const catalogRevision = ref(0);
  const activeKey = ref<PageKey>('domain');
  const openKeys = ref<SectionKey[]>(['design', 'build', 'knowledge', 'resource', 'runtime-observe']);
  const session = ref<UserSession | null>(null);
  const catalog = ref<CatalogSummary | null>(null);
  const conversationSessions = ref<ConversationSession[]>([]);
  const tasks = ref<TaskInstance[]>([]);
  const workflows = ref<WorkflowInstance[]>([]);
  let workflowRefreshInFlight = false;

  const selectedKeys = computed(() => [activeKey.value]);
  const currentPageMeta = computed(() => pageMeta[activeKey.value]);
  const currentSectionMeta = computed(() => sectionMeta[currentPageMeta.value.section]);
  const canManageGovernance = computed(() => session.value?.currentRole !== 'BUSINESS_USER');
  const currentWorkflow = computed(
    () => workflows.value.find((item) => item.id === selectedWorkflowId.value)
      ?? workflows.value.find((item) => item.status === 'WAITING_RESUME')
      ?? workflows.value[0],
  );

  function preferredWorkflowId(workflowList: WorkflowInstance[]) {
    return workflowList.find((item) => item.status === 'WAITING_RESUME')?.id ?? workflowList[0]?.id ?? null;
  }

  function errorMessage(error: unknown, fallback: string) {
    if (error instanceof Error && error.message) {
      return error.message;
    }
    return fallback;
  }

  function findSessionById(sessionId: string) {
    return conversationSessions.value.find((item) => item.id === sessionId);
  }

  function findWorkflowById(workflowId: string | null | undefined) {
    return workflows.value.find((item) => item.id === workflowId);
  }

  async function refresh(showLoading = false) {
    if (!showLoading && workflowRefreshInFlight) {
      return;
    }
    workflowRefreshInFlight = true;
    if (showLoading) {
      loading.value = true;
    }
    try {
      const [sessionData, catalogData, sessionList, tasksData, workflowData] = await Promise.all([
        api.getSession(),
        api.getCatalogSummary(),
        api.getConversationSessions(),
        api.getTasks(),
        api.getWorkflows(),
      ]);
      session.value = sessionData;
      catalog.value = catalogData;
      catalogRevision.value += 1;
      conversationSessions.value = sessionList;
      tasks.value = tasksData;
      workflows.value = workflowData;
      if (!workflowData.length) {
        selectedWorkflowId.value = null;
      } else if (!selectedWorkflowId.value || !workflowData.some((item) => item.id === selectedWorkflowId.value)) {
        selectedWorkflowId.value = preferredWorkflowId(workflowData);
      }
      if (!sessionList.length) {
        runtimeSelectedSessionId.value = null;
      } else if (runtimePreferredSessionId.value && sessionList.some((item) => item.id === runtimePreferredSessionId.value)) {
        runtimeSelectedSessionId.value = runtimePreferredSessionId.value;
      } else if (!runtimeSelectedSessionId.value || !sessionList.some((item) => item.id === runtimeSelectedSessionId.value)) {
        runtimeSelectedSessionId.value = sessionList[0].id;
      }
    } finally {
      workflowRefreshInFlight = false;
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
    selectedWorkflowId,
    knowledgeLibraryPreferredKnowledgeBaseId,
    resourceLibraryPreferredResourceId,
    resourceLibraryPreferredVersionId,
    catalogRevision,
    activeKey,
    openKeys,
    session,
    catalog,
    conversationSessions,
    tasks,
    workflows,
    selectedKeys,
    currentPageMeta,
    currentSectionMeta,
    canManageGovernance,
    currentWorkflow,
    errorMessage,
    findSessionById,
    findWorkflowById,
    refresh,
  };
}
