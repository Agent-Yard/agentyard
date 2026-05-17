<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import CatalogFormDrawer from '../components/CatalogFormDrawer.vue';
import ObjectHistoryPanel from '../components/ObjectHistoryPanel.vue';
import ObjectReferencePanel from '../components/ObjectReferencePanel.vue';
import PageHeadActions from '../components/PageHeadActions.vue';
import ResourceVersionConfigEditor from '../components/ResourceVersionConfigEditor.vue';
import ResourceVersionConfigSummary from '../components/ResourceVersionConfigSummary.vue';
import type {
  BusinessDomain,
  CreateResourcePayload,
  CreateResourceVersionPayload,
  Resource,
  ResourceBlueprint,
  ResourceCenter,
  ResourceType,
  ResourceVersion,
  UpdateResourcePayload,
  UpdateResourceVersionPayload,
} from '../types';

const props = defineProps<{
  domains: BusinessDomain[];
  resourceCenter: ResourceCenter;
  resources: Resource[];
  resourceBlueprints: ResourceBlueprint[];
  preferredResourceId?: string | null;
  preferredVersionId?: string | null;
  catalogRevision: number;
  canManageGovernance: boolean;
}>();

const emit = defineEmits<{
  createResource: [payload: CreateResourcePayload];
  deleteResource: [resourceId: string];
  updateResource: [payload: { resourceId: string; resource: UpdateResourcePayload }];
  createResourceVersion: [payload: { resourceId: string; data: CreateResourceVersionPayload }];
  updateResourceVersion: [payload: { resourceId: string; versionId: string; version: UpdateResourceVersionPayload }];
  deleteResourceVersion: [payload: { resourceId: string; versionId: string }];
  publishResourceVersion: [payload: { resourceId: string; versionId: string }];
}>();

const selectedResourceId = ref('');
const selectedVersionId = ref('');
const activeDetailTab = ref('versions');
const createDrawerOpen = ref(false);
const editDrawerOpen = ref(false);
const createVersionDrawerOpen = ref(false);
const editVersionDrawerOpen = ref(false);
const resourceForm = reactive<UpdateResourcePayload>({
  name: '',
  shareScope: 'DOMAIN_SHARED',
  ownerType: 'DOMAIN',
  ownerId: '',
  summary: '',
  steward: '',
  tags: [],
});
const createVersionForm = reactive<CreateResourceVersionPayload>({
  summary: '新版本',
  status: 'DRAFT',
  configuration: {
    type: 'TOOL',
  },
});
function defaultConfiguration(type: ResourceType) {
  const blueprint = props.resourceBlueprints.find((item) => item.type === type);
  return blueprint ? JSON.parse(JSON.stringify(blueprint.defaultConfiguration)) : { type };
}

const createResourceForm = reactive<CreateResourcePayload>({
  domainId: '',
  name: '',
  type: 'TOOL',
  shareScope: 'DOMAIN_SHARED',
  ownerType: 'DOMAIN',
  ownerId: '',
  summary: '',
  steward: '',
  tags: [],
  initialVersion: {
    summary: '初始版本',
    status: 'DRAFT',
    configuration: defaultConfiguration('TOOL'),
  },
});
const draftVersionForm = reactive<UpdateResourceVersionPayload>({
  summary: '',
  status: 'DRAFT',
  configuration: {
    type: 'TOOL',
  },
});

const selectedResource = computed(() =>
  props.resources.find((item) => item.id === selectedResourceId.value) ?? props.resources[0] ?? null,
);
const selectedVersion = computed(() =>
  selectedResource.value?.versions.find((item) => item.id === selectedVersionId.value)
  ?? selectedResource.value?.effectiveVersion
  ?? selectedResource.value?.latestVersion
  ?? null,
);
const createCurrentBlueprint = computed(() =>
  props.resourceBlueprints.find((item) => item.type === createResourceForm.type) ?? props.resourceBlueprints[0],
);
const assistantOwnerOptions = computed(() => {
  return props.domains
    .find((item) => item.id === selectedResource.value?.domainId)
    ?.scenarios.flatMap((scenario) => scenario.assistants.map((assistant) => ({ label: assistant.name, value: assistant.id })))
    ?? [];
});

const effectiveOwnerOptions = computed(() => {
  if (!selectedResource.value) {
    return [];
  }
  if (resourceForm.ownerType === 'ASSISTANT') {
    return assistantOwnerOptions.value;
  }
  return [{ label: selectedResource.value.domainId, value: selectedResource.value.domainId }];
});
const createOwnerOptions = computed(() => {
  if (createResourceForm.ownerType === 'ASSISTANT') {
    return props.domains
      .find((domain) => domain.id === createResourceForm.domainId)
      ?.scenarios.flatMap((scenario) => scenario.assistants.map((assistant) => ({ label: assistant.name, value: assistant.id })))
      ?? [];
  }
  return props.domains.map((domain) => ({ label: domain.name, value: domain.id }));
});

function syncResourceForm(resource: Resource) {
  resourceForm.name = resource.name;
  resourceForm.shareScope = resource.shareScope;
  resourceForm.ownerType = resource.ownerType;
  resourceForm.ownerId = resource.ownerId;
  resourceForm.summary = resource.summary;
  resourceForm.steward = resource.steward;
  resourceForm.tags = [...resource.tags];
}

function cloneVersionConfiguration(version: ResourceVersion | null) {
  if (!version) {
    return { type: selectedResource.value?.type ?? 'TOOL' } as CreateResourceVersionPayload['configuration'];
  }
  return JSON.parse(JSON.stringify(version.configuration));
}

function resetCreateResourceForm() {
  createResourceForm.domainId = selectedResource.value?.domainId ?? props.domains[0]?.id ?? '';
  createResourceForm.name = '';
  createResourceForm.type = 'TOOL';
  createResourceForm.shareScope = 'DOMAIN_SHARED';
  createResourceForm.ownerType = 'DOMAIN';
  createResourceForm.ownerId = createResourceForm.domainId;
  createResourceForm.summary = '';
  createResourceForm.steward = '';
  createResourceForm.tags = [];
  createResourceForm.initialVersion.summary = '初始版本';
  createResourceForm.initialVersion.status = 'DRAFT';
  createResourceForm.initialVersion.configuration = defaultConfiguration('TOOL');
}

function openCreateDrawer() {
  if (!createDrawerOpen.value) {
    resetCreateResourceForm();
  }
  createDrawerOpen.value = true;
}

function applyBlueprint(type: ResourceBlueprint['type']) {
  createResourceForm.type = type;
}

function submitCreateResource() {
  emit('createResource', JSON.parse(JSON.stringify(createResourceForm)));
  createDrawerOpen.value = false;
  resetCreateResourceForm();
}

watch(
  () => [props.resources, props.preferredResourceId] as const,
  ([resources, preferredResourceId]) => {
    if (!resources.length) {
      selectedResourceId.value = '';
      return;
    }
    if (preferredResourceId && resources.some((item) => item.id === preferredResourceId)) {
      selectedResourceId.value = preferredResourceId;
      return;
    }
    if (!resources.some((item) => item.id === selectedResourceId.value)) {
      selectedResourceId.value = resources[0].id;
    }
  },
  { immediate: true },
);

watch(
  () => [selectedResource.value, props.preferredVersionId] as const,
  ([resource, preferredVersionId]) => {
    if (!resource) {
      selectedVersionId.value = '';
      return;
    }
    if (preferredVersionId && resource.versions.some((item) => item.id === preferredVersionId)) {
      selectedVersionId.value = preferredVersionId;
    } else if (!resource.versions.some((item) => item.id === selectedVersionId.value)) {
      selectedVersionId.value = resource.effectiveVersion?.id ?? resource.latestVersion?.id ?? resource.versions[0]?.id ?? '';
    }
    syncResourceForm(resource);
    createVersionForm.summary = `基于 ${resource.latestVersion?.version ?? '当前配置'} 的新版本`;
    createVersionForm.status = 'DRAFT';
    createVersionForm.configuration = cloneVersionConfiguration(resource.latestVersion ?? resource.effectiveVersion ?? resource.versions[0] ?? null);
  },
  { immediate: true },
);

watch(
  () => selectedVersion.value,
  (version) => {
    draftVersionForm.summary = version?.summary ?? '';
    draftVersionForm.status = version?.status ?? 'DRAFT';
    draftVersionForm.configuration = cloneVersionConfiguration(version ?? null);
  },
  { immediate: true },
);

watch(
  () => resourceForm.ownerType,
  (ownerType) => {
    if (!selectedResource.value) {
      return;
    }
    if (ownerType === 'DOMAIN') {
      resourceForm.ownerId = selectedResource.value.domainId;
      return;
    }
    if (!effectiveOwnerOptions.value.some((item) => item.value === resourceForm.ownerId)) {
      resourceForm.ownerId = effectiveOwnerOptions.value[0]?.value ?? '';
    }
  },
);

watch(
  () => props.domains,
  (domains) => {
    if (!createResourceForm.domainId && domains.length) {
      createResourceForm.domainId = selectedResource.value?.domainId ?? domains[0].id;
    }
    if (!createResourceForm.ownerId && domains.length && createResourceForm.ownerType === 'DOMAIN') {
      createResourceForm.ownerId = createResourceForm.domainId || domains[0].id;
    }
  },
  { immediate: true },
);

watch(
  () => createResourceForm.ownerType,
  (ownerType) => {
    createResourceForm.ownerId = ownerType === 'ASSISTANT'
      ? createOwnerOptions.value[0]?.value ?? ''
      : (createResourceForm.domainId || props.domains[0]?.id || '');
  },
  { immediate: true },
);

watch(
  () => createResourceForm.domainId,
  (domainId) => {
    if (!domainId) {
      return;
    }
    if (createResourceForm.ownerType === 'DOMAIN') {
      createResourceForm.ownerId = domainId;
      return;
    }
    if (!createOwnerOptions.value.some((option) => option.value === createResourceForm.ownerId)) {
      createResourceForm.ownerId = createOwnerOptions.value[0]?.value ?? '';
    }
  },
  { immediate: true },
);

watch(
  () => createResourceForm.type,
  (type) => {
    createResourceForm.initialVersion.configuration = defaultConfiguration(type);
    createResourceForm.initialVersion.status = 'DRAFT';
  },
  { immediate: true },
);


function submitCreateVersion() {
  if (!selectedResource.value) {
    return;
  }
  emit('createResourceVersion', {
    resourceId: selectedResource.value.id,
    data: JSON.parse(JSON.stringify(createVersionForm)),
  });
  createVersionDrawerOpen.value = false;
}

function openEditDrawer(resourceId: string) {
  selectedResourceId.value = resourceId;
  const resource = props.resources.find((item) => item.id === resourceId);
  if (resource) {
    syncResourceForm(resource);
  }
  editDrawerOpen.value = true;
}

function submitUpdateResource() {
  if (!selectedResource.value) {
    return;
  }
  emit('updateResource', {
    resourceId: selectedResource.value.id,
    resource: JSON.parse(JSON.stringify(resourceForm)),
  });
}

function submitUpdateDraftVersion() {
  if (!selectedResource.value || !selectedVersion.value || selectedVersion.value.status !== 'DRAFT') {
    return;
  }
  emit('updateResourceVersion', {
    resourceId: selectedResource.value.id,
    versionId: selectedVersion.value.id,
    version: JSON.parse(JSON.stringify(draftVersionForm)),
  });
  editVersionDrawerOpen.value = false;
}

function openCreateVersionDrawer() {
  if (!selectedResource.value) {
    return;
  }
  createVersionForm.summary = `基于 ${selectedResource.value.latestVersion?.version ?? '当前配置'} 的新版本`;
  createVersionForm.status = 'DRAFT';
  createVersionForm.configuration = cloneVersionConfiguration(
    selectedResource.value.latestVersion ?? selectedResource.value.effectiveVersion ?? selectedResource.value.versions[0] ?? null,
  );
  createVersionDrawerOpen.value = true;
}

function openEditVersionDrawer(version: ResourceVersion) {
  selectedVersionId.value = version.id;
  draftVersionForm.summary = version.summary;
  draftVersionForm.status = version.status;
  draftVersionForm.configuration = cloneVersionConfiguration(version);
  editVersionDrawerOpen.value = true;
}
</script>

<template>
  <PageHeadActions v-if="canManageGovernance">
    <a-button type="primary" @click="openCreateDrawer">新建资源</a-button>
  </PageHeadActions>

  <a-row :gutter="[16, 16]">
    <a-col :xs="24" :xl="7">
      <a-card title="能力资源目录">
        <template #extra>
          <a-space>
            <a-tag class="console-accent-tag">{{ resourceCenter.totalResources }} 个资源</a-tag>
            <a-tag>{{ resourceCenter.domainSharedResources }} 个域共享</a-tag>
          </a-space>
        </template>
        <a-list :data-source="resources" :locale="{ emptyText: '暂无资源' }">
          <template #renderItem="{ item }">
            <a-list-item
              class="clickable-item"
              :class="{ 'graph-list-item--active': selectedResource?.id === item.id }"
              @click="selectedResourceId = item.id"
            >
              <a-list-item-meta :title="item.name" :description="item.summary || item.type" />
              <a-space>
                <a-tag v-if="selectedResourceId === item.id" class="console-accent-tag">当前</a-tag>
                <a-tag :color="item.type === 'TOOL' ? 'geekblue' : item.type === 'LLM_MODEL' ? 'green' : 'gold'">
                  {{ item.type }}
                </a-tag>
              </a-space>
            </a-list-item>
          </template>
        </a-list>
      </a-card>
    </a-col>

    <a-col :xs="24" :xl="17">
      <section v-if="selectedResource" class="resource-workspace">
        <div class="resource-workspace__header">
          <div class="resource-workspace__title-block">
            <a-space wrap>
              <a-typography-title :level="4" style="margin: 0">{{ selectedResource.name }}</a-typography-title>
              <a-tag :color="selectedResource.type === 'TOOL' ? 'geekblue' : selectedResource.type === 'LLM_MODEL' ? 'green' : 'gold'">
                {{ selectedResource.type }}
              </a-tag>
              <a-tag>{{ selectedResource.shareScope }}</a-tag>
              <a-tag>{{ selectedResource.ownerType }} / {{ selectedResource.ownerId }}</a-tag>
            </a-space>
            <a-typography-paragraph class="resource-workspace__summary" :ellipsis="{ rows: 2 }">
              {{ selectedResource.summary || '暂无摘要' }}
            </a-typography-paragraph>
            <a-space wrap>
              <a-typography-text type="secondary">负责人：{{ selectedResource.steward || '-' }}</a-typography-text>
              <a-tag v-for="tag in selectedResource.tags" :key="tag">{{ tag }}</a-tag>
            </a-space>
          </div>
          <a-space v-if="canManageGovernance" class="resource-workspace__actions">
            <a-button @click="openEditDrawer(selectedResource.id)">编辑资源</a-button>
            <a-button danger ghost @click="emit('deleteResource', selectedResource.id)">删除资源</a-button>
          </a-space>
        </div>

        <a-tabs v-model:activeKey="activeDetailTab">
          <a-tab-pane key="overview" tab="概览">
            <a-descriptions :column="2" size="small">
              <a-descriptions-item label="资源 ID">{{ selectedResource.id }}</a-descriptions-item>
              <a-descriptions-item label="资源类型">{{ selectedResource.type }}</a-descriptions-item>
              <a-descriptions-item label="共享范围">{{ selectedResource.shareScope }}</a-descriptions-item>
              <a-descriptions-item label="业务域">{{ selectedResource.domainId }}</a-descriptions-item>
              <a-descriptions-item label="归属">{{ selectedResource.ownerType }} / {{ selectedResource.ownerId }}</a-descriptions-item>
              <a-descriptions-item label="负责人">{{ selectedResource.steward || '-' }}</a-descriptions-item>
              <a-descriptions-item label="标签" :span="2">{{ selectedResource.tags.join(' / ') || '-' }}</a-descriptions-item>
              <a-descriptions-item label="摘要" :span="2">{{ selectedResource.summary || '-' }}</a-descriptions-item>
            </a-descriptions>
          </a-tab-pane>

          <a-tab-pane key="versions" tab="版本">
            <div class="console-stack">
              <a-card size="small">
                <template #title>版本列表</template>
                <template #extra>
                  <a-button v-if="canManageGovernance" type="primary" size="small" @click="openCreateVersionDrawer">
                    创建版本
                  </a-button>
                </template>
                <a-list :data-source="selectedResource.versions">
                  <template #renderItem="{ item }">
                    <a-list-item
                      class="clickable-item"
                      :class="{ 'graph-list-item--active': selectedVersionId === item.id }"
                      @click="selectedVersionId = item.id"
                    >
                      <a-list-item-meta :title="`v${item.version}`" :description="item.summary || '暂无版本摘要'" />
                      <a-space>
                        <a-tag v-if="selectedVersionId === item.id" class="console-accent-tag">当前</a-tag>
                        <a-tag :color="item.status === 'PUBLISHED' ? 'green' : 'gold'">{{ item.status }}</a-tag>
                        <a-button
                          v-if="canManageGovernance && item.status !== 'PUBLISHED'"
                          type="link"
                          size="small"
                          @click.stop="emit('publishResourceVersion', { resourceId: selectedResource.id, versionId: item.id })"
                        >
                          发布
                        </a-button>
                        <a-button
                          v-if="canManageGovernance && item.status === 'DRAFT'"
                          type="link"
                          size="small"
                          @click.stop="openEditVersionDrawer(item)"
                        >
                          编辑
                        </a-button>
                        <a-button
                          v-if="canManageGovernance"
                          type="link"
                          size="small"
                          danger
                          @click.stop="emit('deleteResourceVersion', { resourceId: selectedResource.id, versionId: item.id })"
                        >
                          删除
                        </a-button>
                      </a-space>
                    </a-list-item>
                  </template>
                </a-list>
              </a-card>

              <a-card v-if="selectedVersion" size="small" title="版本详情">
                <template #extra>
                  <a-space>
                    <a-button
                      v-if="canManageGovernance && selectedVersion.status === 'DRAFT'"
                      type="primary"
                      ghost
                      @click="openEditVersionDrawer(selectedVersion)"
                    >
                      编辑草稿
                    </a-button>
                    <a-button
                      v-if="canManageGovernance && selectedVersion.status !== 'PUBLISHED'"
                      type="primary"
                      ghost
                      @click="emit('publishResourceVersion', { resourceId: selectedResource.id, versionId: selectedVersion.id })"
                    >
                      发布
                    </a-button>
                    <a-button
                      v-if="canManageGovernance"
                      danger
                      ghost
                      @click="emit('deleteResourceVersion', { resourceId: selectedResource.id, versionId: selectedVersion.id })"
                    >
                      删除版本
                    </a-button>
                  </a-space>
                </template>
                <div class="console-stack">
                  <a-descriptions :column="2" size="small" style="margin-bottom: 16px">
                    <a-descriptions-item label="版本号">{{ selectedVersion.version }}</a-descriptions-item>
                    <a-descriptions-item label="状态">{{ selectedVersion.status }}</a-descriptions-item>
                    <a-descriptions-item label="创建时间">{{ selectedVersion.createdAt }}</a-descriptions-item>
                    <a-descriptions-item label="发布时间">{{ selectedVersion.publishedAt || '-' }}</a-descriptions-item>
                    <a-descriptions-item label="版本摘要" :span="2">{{ selectedVersion.summary || '-' }}</a-descriptions-item>
                  </a-descriptions>

                  <ResourceVersionConfigSummary
                    :resource-type="selectedResource.type"
                    :configuration="selectedVersion.configuration"
                  />
                </div>
              </a-card>
            </div>
          </a-tab-pane>

          <a-tab-pane key="references" tab="引用分析">
            <ObjectReferencePanel
              object-type="RESOURCE"
              :object-id="selectedResource.id"
              :reload-key="catalogRevision"
            />
          </a-tab-pane>

          <a-tab-pane key="history" tab="操作历史">
            <ObjectHistoryPanel
              aggregate-type="RESOURCE"
              :object-id="selectedResource.id"
              :reload-key="catalogRevision"
            />
          </a-tab-pane>
        </a-tabs>
      </section>
      <a-empty v-else description="暂无资源" />
    </a-col>
  </a-row>

  <a-drawer
    :open="createDrawerOpen"
    title="新建能力资源"
    :width="980"
    destroy-on-close
    @close="createDrawerOpen = false"
  >
    <div class="create-drawer">
      <div class="create-drawer__kicker">03.01 / 能力资源 / 资源目录</div>
      <div class="create-drawer__body">
        <a-alert
          type="info"
          show-icon
          message="资源创建会同时落下一个初始版本"
          description="请选择资源蓝图并补齐基础信息、归属策略和初始版本配置。"
        />

        <a-row :gutter="[16, 16]">
          <a-col :span="8">
            <div class="console-stack">
              <a-card size="small" title="能力资源蓝图">
                <a-space direction="vertical" style="width: 100%">
                  <a-card
                    v-for="blueprint in resourceBlueprints"
                    :key="blueprint.type"
                    size="small"
                    class="clickable-item selectable-card"
                    :class="{ 'selectable-card--active': createResourceForm.type === blueprint.type }"
                    @click="applyBlueprint(blueprint.type)"
                  >
                    <a-typography-title :level="5" style="margin: 0 0 8px 0">{{ blueprint.label }}</a-typography-title>
                    <a-typography-text type="secondary">{{ blueprint.description }}</a-typography-text>
                  </a-card>
                </a-space>
              </a-card>

              <a-card v-if="createCurrentBlueprint" size="small" title="当前类型需维护">
                <a-list :data-source="createCurrentBlueprint.maintainedFields" size="small">
                  <template #renderItem="{ item }">
                    <a-list-item>{{ item }}</a-list-item>
                  </template>
                </a-list>
              </a-card>
            </div>
          </a-col>

          <a-col :span="16">
            <a-form layout="vertical" :model="createResourceForm" @finish="submitCreateResource">
              <a-row :gutter="[16, 16]">
                <a-col :span="12">
                  <a-form-item label="资源名称">
                    <a-input v-model:value="createResourceForm.name" placeholder="例如：售后策略 Tool" />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item label="资源类型">
                    <a-select
                      v-model:value="createResourceForm.type"
                      :options="resourceBlueprints.map((item) => ({ label: item.label, value: item.type }))"
                    />
                  </a-form-item>
                </a-col>
              </a-row>

              <a-row :gutter="[16, 16]">
                <a-col :span="12">
                  <a-form-item label="所属业务域">
                    <a-select
                      v-model:value="createResourceForm.domainId"
                      :options="domains.map((item) => ({ label: item.name, value: item.id }))"
                    />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item label="共享范围">
                    <a-select
                      v-model:value="createResourceForm.shareScope"
                      :options="[
                        { label: '域内共享', value: 'DOMAIN_SHARED' },
                        { label: '私有', value: 'PRIVATE' },
                      ]"
                    />
                  </a-form-item>
                </a-col>
              </a-row>

              <a-row :gutter="[16, 16]">
                <a-col :span="12">
                  <a-form-item label="归属类型">
                    <a-segmented
                      v-model:value="createResourceForm.ownerType"
                      :options="[
                        { label: '业务域', value: 'DOMAIN' },
                        { label: '助手私有', value: 'ASSISTANT' },
                      ]"
                      block
                    />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item label="归属对象">
                    <a-select v-model:value="createResourceForm.ownerId" :options="createOwnerOptions" />
                  </a-form-item>
                </a-col>
              </a-row>

              <a-form-item label="摘要">
                <a-textarea v-model:value="createResourceForm.summary" :rows="3" />
              </a-form-item>

              <a-row :gutter="[16, 16]">
                <a-col :span="12">
                  <a-form-item label="负责人">
                    <a-input v-model:value="createResourceForm.steward" />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item label="标签">
                    <a-select v-model:value="createResourceForm.tags" mode="tags" />
                  </a-form-item>
                </a-col>
              </a-row>

              <a-divider>初始版本</a-divider>

              <a-row :gutter="[16, 16]">
                <a-col :span="12">
                  <a-form-item label="版本摘要">
                    <a-input v-model:value="createResourceForm.initialVersion.summary" />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item label="状态">
                    <a-select
                      v-model:value="createResourceForm.initialVersion.status"
                      :options="[
                        { label: '草稿', value: 'DRAFT' },
                        { label: '已发布', value: 'PUBLISHED' },
                      ]"
                    />
                  </a-form-item>
                </a-col>
              </a-row>

              <ResourceVersionConfigEditor
                :resource-type="createResourceForm.type"
                :configuration="createResourceForm.initialVersion.configuration"
                snapshot-binding-mode="hidden"
              />

              <div class="create-drawer__actions">
                <a-button @click="createDrawerOpen = false">取消</a-button>
                <a-button type="primary" html-type="submit">创建资源</a-button>
              </div>
            </a-form>
          </a-col>
        </a-row>
      </div>
    </div>
  </a-drawer>

  <CatalogFormDrawer
    :open="createVersionDrawerOpen"
    title="创建资源版本"
    :width="1040"
    kicker="03.01 / 能力资源 / 版本维护"
    @close="createVersionDrawerOpen = false"
  >
    <a-alert
      v-if="selectedResource"
      type="info"
      show-icon
      style="margin-bottom: 16px"
      :message="`基于 ${selectedResource.latestVersion?.version ?? '当前配置'} 创建新版本`"
      description="新版本会继承当前版本配置，可在提交前调整摘要、状态和能力配置。"
    />
    <a-form
      v-if="selectedResource"
      layout="vertical"
      :model="createVersionForm"
      @finish="submitCreateVersion"
    >
      <a-row :gutter="[16, 16]">
        <a-col :span="12">
          <a-form-item label="版本摘要">
            <a-input v-model:value="createVersionForm.summary" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="状态">
            <a-select
              v-model:value="createVersionForm.status"
              :options="[
                { label: '草稿', value: 'DRAFT' },
                { label: '已发布', value: 'PUBLISHED' },
              ]"
            />
          </a-form-item>
        </a-col>
      </a-row>

      <ResourceVersionConfigEditor
        :resource-type="selectedResource.type"
        :configuration="createVersionForm.configuration"
        snapshot-binding-mode="hidden"
      />

      <div class="create-drawer__actions">
        <a-button @click="createVersionDrawerOpen = false">取消</a-button>
        <a-button type="primary" html-type="submit">创建版本</a-button>
      </div>
    </a-form>
  </CatalogFormDrawer>

  <CatalogFormDrawer
    :open="editVersionDrawerOpen"
    title="编辑草稿版本"
    :width="1040"
    kicker="03.01 / 能力资源 / 版本维护"
    @close="editVersionDrawerOpen = false"
  >
    <a-form
      v-if="selectedResource && selectedVersion"
      layout="vertical"
      :model="draftVersionForm"
      @finish="submitUpdateDraftVersion"
    >
      <a-descriptions :column="2" size="small" style="margin-bottom: 16px">
        <a-descriptions-item label="资源">{{ selectedResource.name }}</a-descriptions-item>
        <a-descriptions-item label="版本号">{{ selectedVersion.version }}</a-descriptions-item>
        <a-descriptions-item label="创建时间">{{ selectedVersion.createdAt }}</a-descriptions-item>
        <a-descriptions-item label="发布时间">{{ selectedVersion.publishedAt || '-' }}</a-descriptions-item>
      </a-descriptions>

      <a-row :gutter="[16, 16]">
        <a-col :span="12">
          <a-form-item label="版本摘要">
            <a-input v-model:value="draftVersionForm.summary" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="状态">
            <a-select
              v-model:value="draftVersionForm.status"
              :options="[
                { label: '草稿', value: 'DRAFT' },
                { label: '已发布', value: 'PUBLISHED' },
              ]"
            />
          </a-form-item>
        </a-col>
      </a-row>

      <ResourceVersionConfigEditor
        :resource-type="selectedResource.type"
        :configuration="draftVersionForm.configuration"
        snapshot-binding-mode="hidden"
      />

      <div class="create-drawer__actions">
        <a-button @click="editVersionDrawerOpen = false">取消</a-button>
        <a-button type="primary" html-type="submit">保存草稿版本</a-button>
      </div>
    </a-form>
  </CatalogFormDrawer>

  <CatalogFormDrawer
    :open="editDrawerOpen"
    title="编辑资源属性"
    :width="720"
    kicker="03.01 / 能力资源 / 资源目录"
    @close="editDrawerOpen = false"
  >
    <a-form layout="vertical" :model="resourceForm" @finish="submitUpdateResource">
      <a-row :gutter="[16, 16]">
        <a-col :span="12">
          <a-form-item label="资源名称">
            <a-input v-model:value="resourceForm.name" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="资源类型">
            <a-input :value="selectedResource?.type" disabled />
          </a-form-item>
        </a-col>
      </a-row>

      <a-row :gutter="[16, 16]">
        <a-col :span="12">
          <a-form-item label="共享范围">
            <a-select
              v-model:value="resourceForm.shareScope"
              :options="[
                { label: '域内共享', value: 'DOMAIN_SHARED' },
                { label: '私有', value: 'PRIVATE' },
              ]"
            />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="业务域">
            <a-input :value="selectedResource?.domainId" disabled />
          </a-form-item>
        </a-col>
      </a-row>

      <a-row :gutter="[16, 16]">
        <a-col :span="12">
          <a-form-item label="归属类型">
            <a-segmented
              v-model:value="resourceForm.ownerType"
              :options="[
                { label: '业务域', value: 'DOMAIN' },
                { label: '助手私有', value: 'ASSISTANT' },
              ]"
              block
            />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="归属对象">
            <a-select v-model:value="resourceForm.ownerId" :options="effectiveOwnerOptions" />
          </a-form-item>
        </a-col>
      </a-row>

      <a-form-item label="摘要">
        <a-textarea v-model:value="resourceForm.summary" :rows="3" />
      </a-form-item>

      <a-row :gutter="[16, 16]">
        <a-col :span="12">
          <a-form-item label="负责人">
            <a-input v-model:value="resourceForm.steward" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="标签">
            <a-select v-model:value="resourceForm.tags" mode="tags" />
          </a-form-item>
        </a-col>
      </a-row>

      <div class="create-drawer__actions">
        <a-button @click="editDrawerOpen = false">取消</a-button>
        <a-button type="primary" html-type="submit">保存资源信息</a-button>
      </div>
    </a-form>
  </CatalogFormDrawer>
</template>

<style scoped>
.resource-workspace {
  border: 1px solid var(--line);
  border-radius: var(--r);
  background: rgba(252, 251, 248, 0.78);
  box-shadow: var(--shadow-sm);
  padding: 20px;
}

.resource-workspace__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.resource-workspace__title-block {
  min-width: 0;
}

.resource-workspace__summary {
  color: var(--ink-soft);
  margin: 8px 0;
}

.resource-workspace__actions {
  flex: 0 0 auto;
}

@media (max-width: 960px) {
  .resource-workspace {
    padding: 16px;
  }

  .resource-workspace__header {
    flex-direction: column;
  }

  .resource-workspace__actions {
    width: 100%;
  }
}
</style>
