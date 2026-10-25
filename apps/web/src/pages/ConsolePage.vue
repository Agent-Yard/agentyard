<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { RouterView, useRoute, useRouter } from 'vue-router';
import AppLayout from '../layouts/AppLayout.vue';
import DeletionPreviewModal from '../components/DeletionPreviewModal.vue';
import { pageMeta, sectionMeta, resolvePageKeyFromPath, type PageKey } from '../config/navigation';
import type { OwnershipOption } from '../composables/ownership';
import { useCatalogActions } from '../composables/useCatalogActions';
import { api, isUnauthorizedError } from '../services/api';
import { collaborationApi } from '../services/collaboration';
import type { CatalogSummary, UserSession } from '../types';
import type { CollaborationOrganization } from '../../../../packages/contracts/src/task';
const route = useRoute(); const router = useRouter();
const session = ref<UserSession>(); const catalog = ref<CatalogSummary>(); const organizations = ref<CollaborationOrganization[]>([]);
const loading = ref(true); const error = ref(''); const catalogRevision = ref(0);
const pageKey = computed(() => resolvePageKeyFromPath(route.path)); const meta = computed(() => pageMeta[pageKey.value]);
const canManage = computed(() => session.value?.availableRoles.some(role => ['PLATFORM_ADMIN', 'DEVELOPER'].includes(role)) ?? false);
const admin = computed(() => session.value?.availableRoles.includes('PLATFORM_ADMIN') ?? false);
const ownershipOptions = computed<OwnershipOption[]>(() => [
  { ownerType: 'PERSON', ownerId: session.value?.userId ?? '', label: '我自己' },
  ...organizations.value.map(org => ({ ownerType: 'ORGANIZATION' as const, ownerId: org.id, label: org.name })),
  ...(admin.value ? [{ ownerType: 'PLATFORM' as const, ownerId: 'platform', label: '平台共享配置' }] : []),
]);
const state = { knowledgeLibraryPreferredKnowledgeBaseId: ref<string|null>(null), resourceLibraryPreferredResourceId: ref<string|null>(null), resourceLibraryPreferredVersionId: ref<string|null>(null) };
async function refresh() {
  error.value = '';
  try {
    if (!session.value) session.value = await api.getSession();
    if (canManage.value) { catalog.value = await api.getCatalogSummary(); catalogRevision.value++; }
    organizations.value = await collaborationApi.organizations();
  } catch (e) { if (!isUnauthorizedError(e)) error.value = e instanceof Error ? e.message : String(e); }
  finally { loading.value = false; }
}
const actions = useCatalogActions(state, refresh, (e, fallback) => e instanceof Error ? e.message : fallback);
const view = computed(() => {
  const common = { catalogRevision: catalogRevision.value, canManageGovernance: canManage.value, currentUserId: session.value?.userId ?? '', ownershipOptions: ownershipOptions.value };
  if (pageKey.value === 'resource-library') return { props: { ...common, resources: catalog.value?.resources ?? [], resourceBlueprints: catalog.value?.resourceBlueprints ?? [], resourceCenter: catalog.value?.resourceCenter ?? { totalResources:0, sharedResources:0, privateResources:0, references:[] }, preferredResourceId: state.resourceLibraryPreferredResourceId.value, preferredVersionId: state.resourceLibraryPreferredVersionId.value }, handlers: { createResource: actions.handleCreateResource, updateResource: actions.handleUpdateResource, deleteResource: actions.handleDeleteResource, createResourceVersion: actions.handleCreateResourceVersion, updateResourceVersion: actions.handleUpdateResourceVersion, deleteResourceVersion: actions.handleDeleteResourceVersion, publishResourceVersion: actions.handlePublishResourceVersion } };
  if (pageKey.value === 'knowledge-library') return { props: { ...common, knowledgeBases: catalog.value?.knowledgeBases ?? [], preferredKnowledgeBaseId: state.knowledgeLibraryPreferredKnowledgeBaseId.value }, handlers: { createKnowledgeBase: actions.handleCreateKnowledgeBase, updateKnowledgeBase: actions.handleUpdateKnowledgeBase, deleteKnowledgeBase: actions.handleDeleteKnowledgeBase, refreshCatalog: refresh } };
  const ownership={currentUserId:common.currentUserId,ownershipOptions:common.ownershipOptions};
  const refreshHandler={refreshCatalog:refresh};
  switch(pageKey.value) {
    case 'reception': return {props:{canManageGovernance:canManage.value},handlers:{}};
    case 'tasks': return {props:{},handlers:{}};
    case 'organizations': return {props:{},handlers:refreshHandler};
    case 'role-templates': return {props:{...ownership,catalog:catalog.value},handlers:refreshHandler};
    case 'entries': return {props:{...ownership,catalog:catalog.value,entries:catalog.value?.entries??[],platformAdmin:admin.value},handlers:refreshHandler};
    case 'playbook': return {props:{...ownership,playbooks:catalog.value?.playbooks??[]},handlers:refreshHandler};
    case 'playbook-editor': return {props:{catalogRevision:catalogRevision.value,canManageGovernance:canManage.value,playbooks:catalog.value?.playbooks??[],preferredPlaybookId:typeof route.query.playbookId==='string'?route.query.playbookId:null},handlers:refreshHandler};
    case 'channel-admin': return {props:{entries:catalog.value?.entries??[]},handlers:{}};
    default: return {props:{},handlers:{}};
  }
});
watch(pageKey, () => { if (session.value && meta.value.section === 'configuration' && !canManage.value) void router.replace(pageMeta.reception.path); });
onMounted(refresh);
async function logout() { const value = await api.logout(); window.location.assign(value.postLogoutRedirectUrl || '/login'); }
</script>
<template>
  <div v-if="loading" class="loading-screen"><a-spin size="large" /></div>
  <a-app v-else-if="session">
    <AppLayout :session="session" :current-section-label="sectionMeta[meta.section].label" :current-page-label="meta.label" :current-page-subtitle="meta.subtitle" :selected-keys="[pageKey]" :open-keys="[meta.section]" :governance-writable="canManage" @menu-click="info => router.push(pageMeta[String(info.key) as PageKey].path)" @logout="logout">
      <a-alert v-if="error" type="error" :message="error" show-icon style="margin-bottom:16px"><template #action><a-button @click="refresh">重试</a-button></template></a-alert>
      <RouterView v-if="meta.section === 'work' || canManage" v-slot="{ Component }"><component :is="Component" v-bind="view.props" v-on="view.handlers" /></RouterView>
      <a-result v-else status="403" title="此页面需要配置管理权限" />
    </AppLayout>
    <DeletionPreviewModal :open="actions.deletionPreviewOpen.value" :preview="actions.deletionPreview.value" :confirming="actions.deletionPreviewConfirming.value" @close="actions.closeDeletionPreview" @confirm="actions.confirmDeletionPreview" />
  </a-app>
  <a-result v-else status="error" title="无法读取登录状态" :sub-title="error"><template #extra><a-button @click="refresh">重试</a-button></template></a-result>
</template>
