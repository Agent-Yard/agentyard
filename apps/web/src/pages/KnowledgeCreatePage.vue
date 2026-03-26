<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import type {
  Assistant,
  BusinessDomain,
  CreateKnowledgeBasePayload,
} from '../types';

const props = defineProps<{
  domains: BusinessDomain[];
  assistants: Assistant[];
}>();

const emit = defineEmits<{
  createKnowledgeBase: [payload: CreateKnowledgeBasePayload];
}>();

const createForm = reactive<CreateKnowledgeBasePayload>({
  domainId: '',
  name: '',
  shareScope: 'DOMAIN_SHARED',
  ownerType: 'DOMAIN',
  ownerId: '',
  summary: '',
  steward: '',
  tags: [],
});

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

function submitCreate() {
  emit('createKnowledgeBase', JSON.parse(JSON.stringify(createForm)));
  createForm.name = '';
  createForm.summary = '';
  createForm.steward = '';
  createForm.tags = [];
}
</script>

<template>
  <a-row justify="center">
    <a-col :span="16">
      <a-card title="创建知识库">
        <a-alert
          type="info"
          show-icon
          style="margin-bottom: 16px"
          message="知识库已从资源中心独立出来"
          description="这里仅创建知识库治理对象。文档导入、索引快照和发布版本会在知识库工作台里继续完成。"
        />
        <a-form layout="vertical" :model="createForm" @finish="submitCreate">
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="知识库名称">
                <a-input v-model:value="createForm.name" placeholder="例如：客服知识库" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="所属业务域">
                <a-select
                  v-model:value="createForm.domainId"
                  :options="domains.map((item) => ({ label: item.name, value: item.id }))"
                />
              </a-form-item>
            </a-col>
          </a-row>

          <a-row :gutter="[16, 16]">
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
          </a-row>

          <a-form-item label="归属对象">
            <a-select v-model:value="createForm.ownerId" :options="ownerOptions" />
          </a-form-item>

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

          <a-button type="primary" html-type="submit">创建知识库</a-button>
        </a-form>
      </a-card>
    </a-col>
  </a-row>
</template>
