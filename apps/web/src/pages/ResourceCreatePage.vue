<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import ResourceVersionConfigEditor from '../components/ResourceVersionConfigEditor.vue';
import type {
  Assistant,
  BusinessDomain,
  CreateResourcePayload,
  ResourceBlueprint,
  ResourceType,
} from '../types';

const props = defineProps<{
  domains: BusinessDomain[];
  assistants: Assistant[];
  resourceBlueprints: ResourceBlueprint[];
  canManageGovernance: boolean;
}>();

const emit = defineEmits<{
  createResource: [payload: CreateResourcePayload];
}>();

function defaultConfiguration(type: ResourceType) {
  const blueprint = props.resourceBlueprints.find((item) => item.type === type);
  return blueprint ? JSON.parse(JSON.stringify(blueprint.defaultConfiguration)) : { type };
}

const createForm = reactive<CreateResourcePayload>({
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

const currentBlueprint = computed(() =>
  props.resourceBlueprints.find((item) => item.type === createForm.type) ?? props.resourceBlueprints[0],
);

const ownerOptions = computed(() => {
  if (createForm.ownerType === 'ASSISTANT') {
    return props.domains
      .find((domain) => domain.id === createForm.domainId)
      ?.scenarios.flatMap((scenario) => scenario.assistants.map((assistant) => ({ label: assistant.name, value: assistant.id })))
      ?? [];
  }
  return props.domains.map((domain) => ({ label: domain.name, value: domain.id }));
});

watch(
  () => props.domains,
  (domains) => {
    if (!createForm.domainId && domains.length) {
      createForm.domainId = domains[0].id;
    }
    if (!createForm.ownerId && domains.length && createForm.ownerType === 'DOMAIN') {
      createForm.ownerId = domains[0].id;
    }
  },
  { immediate: true },
);

watch(
  () => createForm.ownerType,
  (ownerType) => {
    createForm.ownerId = ownerType === 'ASSISTANT'
      ? ownerOptions.value[0]?.value ?? ''
      : (createForm.domainId || props.domains[0]?.id || '');
  },
  { immediate: true },
);

watch(
  () => createForm.domainId,
  (domainId) => {
    if (!domainId) {
      return;
    }
    if (createForm.ownerType === 'DOMAIN') {
      createForm.ownerId = domainId;
      return;
    }
    if (!ownerOptions.value.some((option) => option.value === createForm.ownerId)) {
      createForm.ownerId = ownerOptions.value[0]?.value ?? '';
    }
  },
  { immediate: true },
);

watch(
  () => createForm.type,
  (type) => {
    createForm.initialVersion.configuration = defaultConfiguration(type);
    createForm.initialVersion.status = 'DRAFT';
  },
  { immediate: true },
);

function applyBlueprint(type: ResourceBlueprint['type']) {
  createForm.type = type;
}

function submitCreate() {
  emit('createResource', JSON.parse(JSON.stringify(createForm)));
  createForm.name = '';
  createForm.summary = '';
  createForm.steward = '';
  createForm.tags = [];
  createForm.initialVersion.summary = '初始版本';
  createForm.initialVersion.status = 'DRAFT';
  createForm.initialVersion.configuration = defaultConfiguration(createForm.type);
}
</script>

<template>
  <a-row :gutter="[16, 16]">
    <a-col :span="8">
      <a-card title="能力资源蓝图">
        <a-space direction="vertical" style="width: 100%">
          <a-card
            v-for="blueprint in resourceBlueprints"
            :key="blueprint.type"
            size="small"
            class="clickable-item"
            :class="{ 'graph-list-item--active': createForm.type === blueprint.type }"
            @click="applyBlueprint(blueprint.type)"
          >
            <a-typography-title :level="5" style="margin: 0 0 8px 0">{{ blueprint.label }}</a-typography-title>
            <a-typography-text type="secondary">{{ blueprint.description }}</a-typography-text>
          </a-card>
        </a-space>
      </a-card>

      <a-card v-if="currentBlueprint" title="当前类型需维护">
        <a-list :data-source="currentBlueprint.maintainedFields" size="small">
          <template #renderItem="{ item }">
            <a-list-item>{{ item }}</a-list-item>
          </template>
        </a-list>
      </a-card>
    </a-col>

    <a-col :span="16">
      <a-card title="创建能力资源">
        <a-alert
          v-if="!canManageGovernance"
          type="warning"
          show-icon
          style="margin-bottom: 16px"
          message="当前角色没有资源治理写权限"
          description="请使用具备治理角色的账号创建资源。"
        />
        <a-form v-if="canManageGovernance" layout="vertical" :model="createForm" @finish="submitCreate">
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="资源名称">
                <a-input v-model:value="createForm.name" placeholder="例如：售后策略 Tool" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="资源类型">
                <a-select
                  v-model:value="createForm.type"
                  :options="resourceBlueprints.map((item) => ({ label: item.label, value: item.type }))"
                />
              </a-form-item>
            </a-col>
          </a-row>

          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="所属业务域">
                <a-select
                  v-model:value="createForm.domainId"
                  :options="domains.map((item) => ({ label: item.name, value: item.id }))"
                />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="共享范围">
                <a-select
                  v-model:value="createForm.shareScope"
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
                  v-model:value="createForm.ownerType"
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
                <a-select v-model:value="createForm.ownerId" :options="ownerOptions" />
              </a-form-item>
            </a-col>
          </a-row>

          <a-form-item label="摘要">
            <a-textarea v-model:value="createForm.summary" :rows="3" />
          </a-form-item>

          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="负责人">
                <a-input v-model:value="createForm.steward" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="标签">
                <a-select v-model:value="createForm.tags" mode="tags" />
              </a-form-item>
            </a-col>
          </a-row>

          <a-divider>初始版本</a-divider>

          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="版本摘要">
                <a-input v-model:value="createForm.initialVersion.summary" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="状态">
                <a-select
                  v-model:value="createForm.initialVersion.status"
                  :options="[
                    { label: '草稿', value: 'DRAFT' },
                    { label: '已发布', value: 'PUBLISHED' },
                  ]"
                />
              </a-form-item>
            </a-col>
          </a-row>

          <ResourceVersionConfigEditor
            :resource-type="createForm.type"
            :configuration="createForm.initialVersion.configuration"
            snapshot-binding-mode="hidden"
          />

          <a-button type="primary" html-type="submit">创建资源</a-button>
        </a-form>
      </a-card>
    </a-col>
  </a-row>
</template>
