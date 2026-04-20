<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import ObjectHistoryPanel from '../components/ObjectHistoryPanel.vue';
import ObjectReferencePanel from '../components/ObjectReferencePanel.vue';
import ResourceVersionConfigEditor from '../components/ResourceVersionConfigEditor.vue';
import ResourceVersionConfigSummary from '../components/ResourceVersionConfigSummary.vue';
import type {
  BusinessDomain,
  CreateResourceVersionPayload,
  Resource,
  ResourceCenter,
  ResourceVersion,
  UpdateResourcePayload,
  UpdateResourceVersionPayload,
} from '../types';

const props = defineProps<{
  domains: BusinessDomain[];
  resourceCenter: ResourceCenter;
  resources: Resource[];
  preferredResourceId?: string | null;
  preferredVersionId?: string | null;
  catalogRevision: number;
  canManageGovernance: boolean;
}>();

const emit = defineEmits<{
  deleteResource: [resourceId: string];
  updateResource: [payload: { resourceId: string; resource: UpdateResourcePayload }];
  createResourceVersion: [payload: { resourceId: string; data: CreateResourceVersionPayload }];
  updateResourceVersion: [payload: { resourceId: string; versionId: string; version: UpdateResourceVersionPayload }];
  deleteResourceVersion: [payload: { resourceId: string; versionId: string }];
  publishResourceVersion: [payload: { resourceId: string; versionId: string }];
}>();

const selectedResourceId = ref('');
const selectedVersionId = ref('');
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
const domainOptions = computed(() => {
  return props.domains.map((item) => ({ label: item.name, value: item.id }));
});

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
const isDraftVersion = computed(() => selectedVersion.value?.status === 'DRAFT');

function cloneVersionConfiguration(version: ResourceVersion | null) {
  if (!version) {
    return { type: selectedResource.value?.type ?? 'TOOL' } as CreateResourceVersionPayload['configuration'];
  }
  return JSON.parse(JSON.stringify(version.configuration));
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
    resourceForm.name = resource.name;
    resourceForm.shareScope = resource.shareScope;
    resourceForm.ownerType = resource.ownerType;
    resourceForm.ownerId = resource.ownerId;
    resourceForm.summary = resource.summary;
    resourceForm.steward = resource.steward;
    resourceForm.tags = [...resource.tags];
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

function submitCreateVersion() {
  if (!selectedResource.value) {
    return;
  }
  emit('createResourceVersion', {
    resourceId: selectedResource.value.id,
    data: JSON.parse(JSON.stringify(createVersionForm)),
  });
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
}
</script>

<template>
  <a-row :gutter="[16, 16]">
    <a-col :span="8">
      <a-card title="能力资源目录">
        <template #extra>
          <a-space>
            <a-tag color="blue">{{ resourceCenter.totalResources }} 个资源</a-tag>
            <a-tag>{{ resourceCenter.domainSharedResources }} 个域共享</a-tag>
          </a-space>
        </template>
        <a-list :data-source="resources">
          <template #renderItem="{ item }">
            <a-list-item class="clickable-item" @click="selectedResourceId = item.id">
              <a-list-item-meta :title="item.name" :description="item.summary || item.type" />
              <a-space>
                <a-tag v-if="selectedResourceId === item.id" color="blue">当前</a-tag>
                <a-tag :color="item.type === 'TOOL' ? 'geekblue' : item.type === 'LLM_MODEL' ? 'green' : 'gold'">
                  {{ item.type }}
                </a-tag>
              </a-space>
            </a-list-item>
          </template>
        </a-list>
      </a-card>
    </a-col>

    <a-col :span="16">
      <a-card v-if="selectedResource" :title="selectedResource.name">
        <template #extra>
          <a-space>
            <a-tag>{{ selectedResource.shareScope }}</a-tag>
            <a-button v-if="canManageGovernance" danger ghost @click="emit('deleteResource', selectedResource.id)">删除资源</a-button>
          </a-space>
        </template>

        <a-card size="small" title="资源信息" style="margin-bottom: 16px">
          <a-form layout="vertical" :model="resourceForm" @finish="submitUpdateResource">
            <a-row :gutter="[16, 16]">
              <a-col :span="12">
                <a-form-item label="资源名称">
                  <a-input v-model:value="resourceForm.name" />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="资源类型">
                  <a-input :value="selectedResource.type" disabled />
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
                  <a-select :value="selectedResource.domainId" :options="domainOptions" disabled />
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

            <a-button v-if="canManageGovernance" type="primary" html-type="submit">保存资源信息</a-button>
          </a-form>
        </a-card>

        <a-row :gutter="[16, 16]">
          <a-col :span="11">
            <a-card size="small" title="版本列表">
              <a-list :data-source="selectedResource.versions">
                <template #renderItem="{ item }">
                  <a-list-item class="clickable-item" @click="selectedVersionId = item.id">
                    <a-list-item-meta :title="`v${item.version}`" :description="item.summary" />
                    <a-space>
                      <a-tag v-if="selectedVersionId === item.id" color="blue">当前</a-tag>
                      <a-tag :color="item.status === 'PUBLISHED' ? 'green' : 'gold'">{{ item.status }}</a-tag>
                    </a-space>
                  </a-list-item>
                </template>
              </a-list>
            </a-card>

            <ObjectReferencePanel
              v-if="selectedResource"
              style="margin-top: 16px"
              object-type="RESOURCE"
              :object-id="selectedResource.id"
              :reload-key="catalogRevision"
            />

            <ObjectHistoryPanel
              v-if="selectedResource"
              style="margin-top: 16px"
              aggregate-type="RESOURCE"
              :object-id="selectedResource.id"
              :reload-key="catalogRevision"
            />
          </a-col>

          <a-col :span="13">
            <a-card v-if="selectedVersion" size="small" :title="isDraftVersion ? '草稿版本编辑' : '当前版本详情'">
              <template #extra>
                <a-space>
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
              <template v-if="isDraftVersion">
                <a-form layout="vertical" :model="draftVersionForm" @finish="submitUpdateDraftVersion">
                  <a-descriptions :column="2" size="small" style="margin-bottom: 16px">
                    <a-descriptions-item label="版本号">{{ selectedVersion.version }}</a-descriptions-item>
                    <a-descriptions-item label="创建时间">{{ selectedVersion.createdAt }}</a-descriptions-item>
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

                  <a-button v-if="canManageGovernance" type="primary" html-type="submit">保存草稿版本</a-button>
                </a-form>
              </template>
              <template v-else>
                <a-descriptions :column="2" size="small" style="margin-bottom: 16px">
                  <a-descriptions-item label="版本号">{{ selectedVersion.version }}</a-descriptions-item>
                  <a-descriptions-item label="状态">{{ selectedVersion.status }}</a-descriptions-item>
                  <a-descriptions-item label="创建时间">{{ selectedVersion.createdAt }}</a-descriptions-item>
                  <a-descriptions-item label="发布时间">{{ selectedVersion.publishedAt || '-' }}</a-descriptions-item>
                </a-descriptions>
                <ResourceVersionConfigSummary
                  :resource-type="selectedResource.type"
                  :configuration="selectedVersion.configuration"
                />
              </template>
            </a-card>

            <a-card v-if="canManageGovernance" size="small" title="创建新版本" style="margin-top: 16px">
              <a-form layout="vertical" :model="createVersionForm" @finish="submitCreateVersion">
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

                <a-button type="primary" html-type="submit">创建版本</a-button>
              </a-form>
            </a-card>
          </a-col>
        </a-row>
      </a-card>
    </a-col>
  </a-row>
</template>
