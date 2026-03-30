<script setup lang="ts">
import { onMounted } from 'vue';
import AppLayout from './layouts/AppLayout.vue';
import type { PageKey, SectionKey } from './config/navigation';
import { useAppState } from './composables/useAppState';
import { useCatalogActions } from './composables/useCatalogActions';
import { useRuntimeActions } from './composables/useRuntimeActions';
import { useAppView } from './composables/useAppView';
import { useWorkflowPolling } from './composables/useWorkflowPolling';

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

onMounted(() => {
  void state.refresh(true);
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
      @menu-click="handleMenuClick"
      @open-change="handleOpenChange"
    >
      <component :is="currentView.component" v-bind="currentView.props.value" v-on="currentView.handlers" />
    </AppLayout>
  </a-app>
</template>
