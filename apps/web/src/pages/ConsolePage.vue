<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { RouterView, useRoute, useRouter } from 'vue-router';
import AppLayout from '../layouts/AppLayout.vue';
import DeletionPreviewModal from '../components/DeletionPreviewModal.vue';
import { pageMeta, resolvePageKeyFromPath, type PageKey, type SectionKey } from '../config/navigation';
import { useAppState } from '../composables/useAppState';
import { useCatalogActions } from '../composables/useCatalogActions';
import { useRuntimeActions } from '../composables/useRuntimeActions';
import { api, isUnauthorizedError } from '../services/api';

const route = useRoute();
const router = useRouter();
const currentPageKey = computed(() => resolvePageKeyFromPath(route.path));
const preferredAssistantId = computed(() => typeof route.query.assistantId === 'string' ? route.query.assistantId : null);
const preferredPlaybookId = computed(() => typeof route.query.playbookId === 'string' ? route.query.playbookId : null);
const state = useAppState(currentPageKey);

const catalogActions = useCatalogActions(state, state.refresh, state.errorMessage);
const runtimeActions = useRuntimeActions(
  state,
  {
    applyRuntimeSessionDetail: state.applyRuntimeSessionDetail,
    addRuntimeUserDrafts: state.addRuntimeUserDrafts,
    reconcileRuntimeUserDrafts: state.reconcileRuntimeUserDrafts,
    markRuntimeUserDraftsFailed: state.markRuntimeUserDraftsFailed,
  },
  state.refresh,
  state.errorMessage,
);

const currentView = computed(() => {
  const pageKey = currentPageKey.value;
  const viewRegistry = {
    domain: {
      props: {
        domains: state.catalog.value!.domains,
        catalogRevision: state.catalogRevision.value,
        canManageGovernance: state.canManageGovernance.value,
      },
      handlers: {
        createDomain: catalogActions.handleCreateDomain,
        updateDomain: catalogActions.handleUpdateDomain,
        deleteDomain: catalogActions.handleDeleteDomain,
      },
    },
    scenario: {
      props: {
        domains: state.catalog.value!.domains,
        scenarios: state.catalog.value!.scenarios,
        catalogRevision: state.catalogRevision.value,
        canManageGovernance: state.canManageGovernance.value,
      },
      handlers: {
        createScenario: catalogActions.handleCreateScenario,
        updateScenario: catalogActions.handleUpdateScenario,
        deleteScenario: catalogActions.handleDeleteScenario,
      },
    },
    assistant: {
      props: {
        assistants: state.catalog.value!.assistants,
        scenarios: state.catalog.value!.scenarios,
        resources: state.catalog.value!.resources,
        knowledgeBases: state.catalog.value!.knowledgeBases,
        catalogRevision: state.catalogRevision.value,
        canManageGovernance: state.canManageGovernance.value,
      },
      handlers: {
        createAssistant: catalogActions.handleCreateAssistant,
        updateAssistant: catalogActions.handleUpdateAssistant,
        deleteAssistant: catalogActions.handleDeleteAssistant,
      },
    },
    agent: {
      props: {
        assistants: state.catalog.value!.assistants,
        agents: state.catalog.value!.agents,
        resources: state.catalog.value!.resources,
        knowledgeBases: state.catalog.value!.knowledgeBases,
        catalogRevision: state.catalogRevision.value,
        canManageGovernance: state.canManageGovernance.value,
      },
      handlers: {
        createAgent: catalogActions.handleCreateAgent,
        saveAgent: catalogActions.handleSaveAgent,
        deleteAgent: catalogActions.handleDeleteAgent,
      },
    },
    playbook: {
      props: {
        assistants: state.catalog.value!.assistants,
        catalogRevision: state.catalogRevision.value,
        canManageGovernance: state.canManageGovernance.value,
        preferredAssistantId: preferredAssistantId.value,
        preferredPlaybookId: preferredPlaybookId.value,
      },
      handlers: {
        createPlaybook: catalogActions.handleCreatePlaybook,
        savePlaybook: catalogActions.handleSavePlaybook,
        deletePlaybook: catalogActions.handleDeletePlaybook,
      },
    },
    'playbook-editor': {
      props: {
        assistants: state.catalog.value!.assistants,
        catalogRevision: state.catalogRevision.value,
        canManageGovernance: state.canManageGovernance.value,
        preferredAssistantId: preferredAssistantId.value,
        preferredPlaybookId: preferredPlaybookId.value,
      },
      handlers: {
        savePlaybook: catalogActions.handleSavePlaybook,
        deletePlaybook: catalogActions.handleDeletePlaybook,
      },
    },
    'knowledge-library': {
      props: {
        domains: state.catalog.value!.domains,
        knowledgeBases: state.catalog.value!.knowledgeBases,
        preferredKnowledgeBaseId: state.knowledgeLibraryPreferredKnowledgeBaseId.value,
        catalogRevision: state.catalogRevision.value,
        canManageGovernance: state.canManageGovernance.value,
      },
      handlers: {
        createKnowledgeBase: catalogActions.handleCreateKnowledgeBase,
        updateKnowledgeBase: catalogActions.handleUpdateKnowledgeBase,
        refreshCatalog: state.refresh,
        deleteKnowledgeBase: catalogActions.handleDeleteKnowledgeBase,
      },
    },
    'resource-library': {
      props: {
        domains: state.catalog.value!.domains,
        resourceCenter: state.catalog.value!.resourceCenter,
        resources: state.catalog.value!.resources,
        resourceBlueprints: state.catalog.value!.resourceBlueprints,
        preferredResourceId: state.resourceLibraryPreferredResourceId.value,
        preferredVersionId: state.resourceLibraryPreferredVersionId.value,
        catalogRevision: state.catalogRevision.value,
        canManageGovernance: state.canManageGovernance.value,
      },
      handlers: {
        createResource: catalogActions.handleCreateResource,
        deleteResource: catalogActions.handleDeleteResource,
        updateResource: catalogActions.handleUpdateResource,
        createResourceVersion: catalogActions.handleCreateResourceVersion,
        updateResourceVersion: catalogActions.handleUpdateResourceVersion,
        deleteResourceVersion: catalogActions.handleDeleteResourceVersion,
        publishResourceVersion: catalogActions.handlePublishResourceVersion,
      },
    },
    'integration-account': {
      props: {},
      handlers: {},
    },
    'channel-admin': {
      props: {
        assistants: state.catalog.value!.assistants,
        scenarios: state.catalog.value!.scenarios,
      },
      handlers: {},
    },
    runtime: {
      props: {
        scenarios: state.catalog.value!.scenarios,
        assistants: state.catalog.value!.assistants,
        sessions: state.conversationSessions.value,
        sessionDetail: state.runtimeSessionDetail.value,
        runtimeDrafts: state.runtimeDrafts.value,
        runtimeProgress: state.runtimeProgress.value,
        creatingSession: state.creatingSession.value,
        sendingSessionId: state.sendingSessionId.value,
        preferredSessionId: state.runtimePreferredSessionId.value,
        selectedSessionId: state.runtimeSelectedSessionId.value,
        currentCustomerId: state.session.value?.userId ?? null,
      },
      handlers: {
        selectSession: runtimeActions.handleSelectRuntimeSession,
        startSession: runtimeActions.handleStartSession,
        sendMessage: runtimeActions.handleSendMessage,
      },
    },
  } satisfies Record<PageKey, { props: Record<string, unknown>; handlers: Record<string, (...args: any[]) => any> }>;

  return viewRegistry[pageKey];
});

function handleMenuClick(info: { key: string | number }) {
  void router.push(pageMeta[String(info.key) as PageKey].path);
}

function handleOpenChange(keys: string[]) {
  state.openKeys.value = keys as SectionKey[];
}

async function handleLogout() {
  const logoutResponse = await api.logout();
  window.location.assign(logoutResponse.postLogoutRedirectUrl || '/login');
}

onMounted(() => {
  void state.refresh(true).catch((error) => {
    if (!isUnauthorizedError(error)) {
      throw error;
    }
  });
});
</script>

<template>
  <div v-if="state.loading.value || !state.session.value || !state.catalog.value" class="loading-screen">
    <a-spin size="large" />
  </div>

  <a-app v-else>
    <AppLayout
      :session="state.session.value"
      :current-section-label="state.currentSectionMeta.value.label"
      :current-page-label="state.currentPageMeta.value.label"
      :current-page-subtitle="state.currentPageMeta.value.subtitle"
      :selected-keys="state.selectedKeys.value"
      :open-keys="state.openKeys.value"
      :governance-writable="state.canManageGovernance.value"
      @menu-click="handleMenuClick"
      @open-change="handleOpenChange"
      @logout="handleLogout"
    >
      <RouterView v-slot="{ Component }">
        <component :is="Component" v-bind="currentView.props" v-on="currentView.handlers" />
      </RouterView>
    </AppLayout>
    <DeletionPreviewModal
      :open="catalogActions.deletionPreviewOpen.value"
      :preview="catalogActions.deletionPreview.value"
      :confirming="catalogActions.deletionPreviewConfirming.value"
      @close="catalogActions.closeDeletionPreview"
      @confirm="catalogActions.confirmDeletionPreview"
    />
  </a-app>
</template>
