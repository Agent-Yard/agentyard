<script setup lang="ts">
import { onMounted } from 'vue';
import AppLayout from '../layouts/AppLayout.vue';
import DeletionPreviewModal from '../components/DeletionPreviewModal.vue';
import type { PageKey, SectionKey } from '../config/navigation';
import { useAppState } from '../composables/useAppState';
import { useCatalogActions } from '../composables/useCatalogActions';
import { useRuntimeActions } from '../composables/useRuntimeActions';
import { useAppView } from '../composables/useAppView';
import { useWorkflowPolling } from '../composables/useWorkflowPolling';
import { api, isUnauthorizedError } from '../services/api';

const state = useAppState();

const catalogActions = useCatalogActions(state, state.refresh, state.errorMessage);
const runtimeActions = useRuntimeActions(
  state,
  { findSessionById: state.findSessionById, findWorkflowById: state.findWorkflowById },
  state.refresh,
  state.errorMessage,
);
const { currentView } = useAppView(state, catalogActions, runtimeActions);

useWorkflowPolling(state.workflows, state.refresh);

function handleMenuClick(info: { key: string | number }) {
  state.activeKey.value = String(info.key) as PageKey;
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
      <component :is="currentView.component" v-bind="currentView.props.value" v-on="currentView.handlers" />
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
