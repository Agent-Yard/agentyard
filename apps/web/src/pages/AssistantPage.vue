<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import ObjectHistoryPanel from '../components/ObjectHistoryPanel.vue';
import ObjectReferencePanel from '../components/ObjectReferencePanel.vue';
import type {
  Assistant,
  CreateAssistantPayload,
  KnowledgeBase,
  Resource,
  Scenario,
  UpdateAssistantPayload,
} from '../types';

const props = defineProps<{
  assistants: Assistant[];
  scenarios: Scenario[];
  resources: Resource[];
  knowledgeBases: KnowledgeBase[];
  catalogRevision: number;
  canManageGovernance: boolean;
}>();

const emit = defineEmits<{
  createAssistant: [payload: CreateAssistantPayload];
  updateAssistant: [payload: { assistantId: string; data: UpdateAssistantPayload }];
  deleteAssistant: [assistantId: string];
}>();

const selectedAssistantId = ref('');
const createForm = reactive<CreateAssistantPayload>({
  scenarioId: '',
  name: '',
  description: '',
  primaryAgentId: null,
  ownerPolicy: {
    maxOwnerSwitchesPerTurn: 3,
  },
  sessionPolicy: {
    idleTimeout: 'PT30M',
    maxWorkflowAge: 'P7D',
    maxWorkflowHistoryEvents: 20000,
  },
  replyPolicy: {
    ownerOnly: true,
  },
  playbookPolicy: {
    timeoutPolicy: null,
    retryPolicy: null,
  },
  modelPolicy: {
    defaultModelResourceId: null,
  },
  knowledgeAccessPolicy: {
    enabled: false,
    knowledgeBaseId: null,
  },
  memoryPolicy: {
    enabled: true,
    windowSize: 8,
  },
});
const editForm = reactive<UpdateAssistantPayload>({
  name: '',
  description: '',
  status: 'DRAFT',
  primaryAgentId: null,
  ownerPolicy: {
    maxOwnerSwitchesPerTurn: 3,
  },
  sessionPolicy: {
    idleTimeout: 'PT30M',
    maxWorkflowAge: 'P7D',
    maxWorkflowHistoryEvents: 20000,
  },
  replyPolicy: {
    ownerOnly: true,
  },
  playbookPolicy: {
    timeoutPolicy: null,
    retryPolicy: null,
  },
  modelPolicy: {
    defaultModelResourceId: null,
  },
  knowledgeAccessPolicy: {
    enabled: false,
    knowledgeBaseId: null,
  },
  memoryPolicy: {
    enabled: true,
    windowSize: 8,
  },
});

const current = computed(() =>
  props.assistants.find((item) => item.id === selectedAssistantId.value) ?? props.assistants[0],
);
const modelResources = computed(() => props.resources.filter((item) => item.type === 'LLM_MODEL'));
const knowledgeBaseOptions = computed(() => props.knowledgeBases.map((item) => ({ label: item.name, value: item.id })));

const currentDraftModelResource = computed(() =>
  props.resources.find((item) => item.id === current.value?.modelPolicy.defaultModelResourceId) ?? null,
);
const createModelHint = computed(() => {
  if (!modelResources.value.length) {
    return { type: 'warning' as const, message: '当前没有可用 LLM 资源，无法配置默认模型。' };
  }
  if (!createForm.modelPolicy.defaultModelResourceId) {
    return { type: 'info' as const, message: '未选择默认模型。可以先保存草稿，但发布和运行会被阻止。' };
  }
  return null;
});

const editModelHint = computed(() => {
  if (!modelResources.value.length) {
    return { type: 'warning' as const, message: '当前没有可用 LLM 资源，无法配置默认模型。' };
  }
  if (!editForm.modelPolicy.defaultModelResourceId) {
    return { type: 'info' as const, message: '未选择默认模型。可以继续保存草稿，但发布和运行会被阻止。' };
  }
  return null;
});

function resourceVersionLabel(resource: Resource | null | undefined) {
  if (!resource) {
    return '未配置';
  }
  const version = resource.effectiveVersion?.version ?? resource.latestVersion?.version ?? '-';
  return `${resource.name} @ ${version}`;
}

const draftModelDescription = computed(() => {
  if (!current.value?.modelPolicy.defaultModelResourceId) {
    return '未配置';
  }
  return resourceVersionLabel(currentDraftModelResource.value);
});

const releaseModelDescription = computed(() => {
  const binding = current.value?.currentRelease?.defaultModelBinding;
  if (!binding) {
    return '尚未发布';
  }
  const provider = binding.providerType && binding.modelId ? `${binding.providerType} / ${binding.modelId}` : '模型配置未记录';
  return `${binding.resourceName} @ ${binding.resourceVersion} · ${provider}`;
});

watch(
  () => props.assistants,
  (assistants) => {
    if (!assistants.length) {
      selectedAssistantId.value = '';
      return;
    }
    if (!assistants.some((item) => item.id === selectedAssistantId.value)) {
      selectedAssistantId.value = assistants[0].id;
    }
  },
  { immediate: true },
);

watch(
  current,
  (assistant) => {
    if (!assistant) {
      return;
    }
    editForm.name = assistant.name;
    editForm.description = assistant.description;
    editForm.status = assistant.version.status;
    editForm.primaryAgentId = assistant.primaryAgentId;
    editForm.ownerPolicy = { ...assistant.ownerPolicy };
    editForm.sessionPolicy = { ...assistant.sessionPolicy };
    editForm.replyPolicy = { ...assistant.replyPolicy };
    editForm.playbookPolicy = { ...assistant.playbookPolicy };
    editForm.modelPolicy = { ...assistant.modelPolicy };
    editForm.knowledgeAccessPolicy = { ...assistant.knowledgeAccessPolicy };
    editForm.memoryPolicy = { ...assistant.memoryPolicy };
  },
  { immediate: true },
);

watch(
  () => props.scenarios,
  (scenarios) => {
    if (!createForm.scenarioId && scenarios.length > 0) {
      createForm.scenarioId = scenarios[0].id;
    }
  },
  { immediate: true },
);

watch(
  modelResources,
  (resources) => {
    if (!resources.some((item) => item.id === createForm.modelPolicy.defaultModelResourceId)) {
      createForm.modelPolicy.defaultModelResourceId = null;
    }
    if (!resources.some((item) => item.id === editForm.modelPolicy.defaultModelResourceId)) {
      editForm.modelPolicy.defaultModelResourceId = null;
    }
  },
  { immediate: true },
);

watch(
  () => props.knowledgeBases,
  (knowledgeBases) => {
    if (!knowledgeBases.some((item) => item.id === createForm.knowledgeAccessPolicy.knowledgeBaseId)) {
      createForm.knowledgeAccessPolicy.knowledgeBaseId = knowledgeBases[0]?.id ?? null;
    }
  },
  { immediate: true },
);

watch(
  () => createForm.knowledgeAccessPolicy.enabled,
  (enabled) => {
    if (enabled && !createForm.knowledgeAccessPolicy.knowledgeBaseId) {
      createForm.knowledgeAccessPolicy.knowledgeBaseId = props.knowledgeBases[0]?.id ?? null;
    }
    if (!enabled) {
      createForm.knowledgeAccessPolicy.knowledgeBaseId = null;
    }
  },
);

watch(
  () => editForm.knowledgeAccessPolicy.enabled,
  (enabled) => {
    if (enabled && !editForm.knowledgeAccessPolicy.knowledgeBaseId) {
      editForm.knowledgeAccessPolicy.knowledgeBaseId = props.knowledgeBases[0]?.id ?? null;
    }
    if (!enabled) {
      editForm.knowledgeAccessPolicy.knowledgeBaseId = null;
    }
  },
);

function submitCreate() {
  emit('createAssistant', {
    ...createForm,
    knowledgeAccessPolicy: {
      enabled: createForm.knowledgeAccessPolicy.enabled && !!createForm.knowledgeAccessPolicy.knowledgeBaseId,
      knowledgeBaseId: createForm.knowledgeAccessPolicy.enabled ? createForm.knowledgeAccessPolicy.knowledgeBaseId : null,
    },
  });
  createForm.name = '';
  createForm.description = '';
}

function submitUpdate() {
  if (!current.value) {
    return;
  }
  emit('updateAssistant', {
    assistantId: current.value.id,
    data: {
      ...editForm,
      knowledgeAccessPolicy: {
        enabled: editForm.knowledgeAccessPolicy.enabled && !!editForm.knowledgeAccessPolicy.knowledgeBaseId,
        knowledgeBaseId: editForm.knowledgeAccessPolicy.enabled ? editForm.knowledgeAccessPolicy.knowledgeBaseId : null,
      },
    },
  });
}
</script>

<template>
  <a-row :gutter="[16, 16]">
    <a-col :span="10">
      <a-card v-if="canManageGovernance" title="新建助手">
        <a-form layout="vertical" :model="createForm" @finish="submitCreate">
          <a-form-item label="所属场景" name="scenarioId">
            <a-select
              v-model:value="createForm.scenarioId"
              :options="scenarios.map((item) => ({ label: item.name, value: item.id }))"
            />
          </a-form-item>
          <a-form-item label="助手名称" name="name">
            <a-input v-model:value="createForm.name" placeholder="例如：售后策略助手" />
          </a-form-item>
          <a-form-item label="描述" name="description">
            <a-textarea
              v-model:value="createForm.description"
              :rows="4"
              placeholder="说明该助手负责的业务目标和协作方式"
            />
          </a-form-item>
          <a-form-item label="草稿默认模型">
            <a-select
              v-model:value="createForm.modelPolicy.defaultModelResourceId"
              allow-clear
              :disabled="!modelResources.length"
              :options="modelResources.map((item) => ({ label: item.name, value: item.id }))"
              placeholder="选择默认模型资源"
            />
          </a-form-item>
          <a-alert
            v-if="createModelHint"
            :type="createModelHint.type"
            show-icon
            :message="createModelHint.message"
            style="margin-bottom: 16px"
          />
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="记忆窗口">
                <a-input-number v-model:value="createForm.memoryPolicy.windowSize" :min="1" style="width: 100%" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="默认知识能力">
                <a-switch v-model:checked="createForm.knowledgeAccessPolicy.enabled" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="默认知识库">
            <a-select
              v-model:value="createForm.knowledgeAccessPolicy.knowledgeBaseId"
              :disabled="!createForm.knowledgeAccessPolicy.enabled"
              allow-clear
              :options="knowledgeBaseOptions"
              placeholder="选择知识库"
            />
          </a-form-item>
          <a-button type="primary" html-type="submit">创建助手</a-button>
        </a-form>
      </a-card>

      <a-card title="助手列表">
        <a-list :data-source="assistants">
          <template #renderItem="{ item }">
            <a-list-item class="clickable-item" @click="selectedAssistantId = item.id">
              <a-list-item-meta :title="item.name" :description="item.description" />
              <a-space>
                <a-tag v-if="selectedAssistantId === item.id" color="blue">当前</a-tag>
                <a-tag :color="item.version.status === 'PUBLISHED' ? 'green' : 'gold'">
                  {{ item.version.status }}
                </a-tag>
              </a-space>
            </a-list-item>
          </template>
        </a-list>
      </a-card>
    </a-col>

    <a-col :span="14">
      <a-card v-if="current" :title="current.name">
        <template #extra>
          <a-space>
            <a-tag color="blue">{{ current.agents.length }} 个智能体</a-tag>
            <a-button v-if="canManageGovernance" danger ghost @click="emit('deleteAssistant', current.id)">删除助手</a-button>
          </a-space>
        </template>

        <a-card size="small" title="模型语义" style="margin-bottom: 16px">
          <a-descriptions :column="1" size="small">
            <a-descriptions-item label="草稿默认模型">{{ draftModelDescription }}</a-descriptions-item>
            <a-descriptions-item label="当前发布冻结模型">{{ releaseModelDescription }}</a-descriptions-item>
          </a-descriptions>
        </a-card>

        <a-form layout="vertical" :model="editForm" @finish="submitUpdate">
          <a-form-item label="助手名称" name="name">
            <a-input v-model:value="editForm.name" />
          </a-form-item>
          <a-form-item label="描述" name="description">
            <a-textarea v-model:value="editForm.description" :rows="4" />
          </a-form-item>

          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="版本状态" name="status">
                <a-select
                  v-model:value="editForm.status"
                  :options="[
                    { label: '草稿', value: 'DRAFT' },
                    { label: '已发布', value: 'PUBLISHED' },
                  ]"
                />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="所属场景">
                <a-input
                  :value="scenarios.find((item) => item.id === current.scenarioId)?.name ?? current.scenarioId"
                  disabled
                />
              </a-form-item>
            </a-col>
          </a-row>

          <a-form-item label="草稿默认模型">
            <a-select
              v-model:value="editForm.modelPolicy.defaultModelResourceId"
              allow-clear
              :disabled="!modelResources.length"
              :options="modelResources.map((item) => ({ label: item.name, value: item.id }))"
              placeholder="选择默认模型资源"
            />
          </a-form-item>
          <a-alert
            v-if="editModelHint"
            :type="editModelHint.type"
            show-icon
            :message="editModelHint.message"
            style="margin-bottom: 16px"
          />

          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="知识能力">
                <a-switch v-model:checked="editForm.knowledgeAccessPolicy.enabled" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="记忆窗口">
                <a-input-number v-model:value="editForm.memoryPolicy.windowSize" :min="1" style="width: 100%" />
              </a-form-item>
            </a-col>
          </a-row>

          <a-form-item label="默认知识库">
            <a-select
              v-model:value="editForm.knowledgeAccessPolicy.knowledgeBaseId"
              :disabled="!editForm.knowledgeAccessPolicy.enabled"
              allow-clear
              :options="knowledgeBaseOptions"
              placeholder="选择知识库"
            />
          </a-form-item>

          <a-alert
            v-if="current.currentRelease?.assistantKnowledgeBinding"
            type="info"
            show-icon
            style="margin-bottom: 16px"
            :message="`当前发布冻结知识：${current.currentRelease.assistantKnowledgeBinding.knowledgeBaseName} @ ${current.currentRelease.assistantKnowledgeBinding.knowledgeReleaseVersion}`"
            :description="`运行时快照 ${current.currentRelease.assistantKnowledgeBinding.snapshotId} · ${current.currentRelease.assistantKnowledgeBinding.retrievalMode} · topK ${current.currentRelease.assistantKnowledgeBinding.defaultTopK}`"
          />

          <a-space v-if="canManageGovernance">
            <a-button type="primary" html-type="submit">保存助手</a-button>
          </a-space>
        </a-form>
      </a-card>

      <ObjectReferencePanel
        v-if="current"
        style="margin-top: 16px"
        object-type="ASSISTANT"
        :object-id="current.id"
        :reload-key="catalogRevision"
      />

      <ObjectHistoryPanel
        v-if="current"
        style="margin-top: 16px"
        aggregate-type="ASSISTANT"
        :object-id="current.id"
        :reload-key="catalogRevision"
      />
    </a-col>
  </a-row>
</template>
