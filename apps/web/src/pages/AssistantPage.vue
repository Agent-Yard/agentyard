<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
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
  modelPolicy: {
    providerResourceId: null,
  },
  ragPolicy: {
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
  modelPolicy: {
    providerResourceId: null,
  },
  ragPolicy: {
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

function syncCreateDefaults() {
  if (!modelResources.value.some((item) => item.id === createForm.modelPolicy.providerResourceId)) {
    createForm.modelPolicy.providerResourceId = modelResources.value[0]?.id ?? null;
  }
  if (!props.knowledgeBases.some((item) => item.id === createForm.ragPolicy.knowledgeBaseId)) {
    createForm.ragPolicy.knowledgeBaseId = props.knowledgeBases[0]?.id ?? null;
  }
  createForm.ragPolicy.enabled = createForm.ragPolicy.enabled && createForm.ragPolicy.knowledgeBaseId !== null;
}

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
    editForm.modelPolicy = { ...assistant.modelPolicy };
    editForm.ragPolicy = { ...assistant.ragPolicy };
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

watch([modelResources, () => props.knowledgeBases], syncCreateDefaults, { immediate: true });

watch(
  () => createForm.ragPolicy.enabled,
  (enabled) => {
    if (enabled && !createForm.ragPolicy.knowledgeBaseId) {
      createForm.ragPolicy.knowledgeBaseId = props.knowledgeBases[0]?.id ?? null;
    }
    if (!enabled) {
      createForm.ragPolicy.knowledgeBaseId = null;
    }
  },
);

watch(
  () => editForm.ragPolicy.enabled,
  (enabled) => {
    if (enabled && !editForm.ragPolicy.knowledgeBaseId) {
      editForm.ragPolicy.knowledgeBaseId = props.knowledgeBases[0]?.id ?? null;
    }
    if (!enabled) {
      editForm.ragPolicy.knowledgeBaseId = null;
    }
  },
);

function submitCreate() {
  emit('createAssistant', {
    ...createForm,
    ragPolicy: {
      enabled: createForm.ragPolicy.enabled && !!createForm.ragPolicy.knowledgeBaseId,
      knowledgeBaseId: createForm.ragPolicy.enabled ? createForm.ragPolicy.knowledgeBaseId : null,
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
      ragPolicy: {
        enabled: editForm.ragPolicy.enabled && !!editForm.ragPolicy.knowledgeBaseId,
        knowledgeBaseId: editForm.ragPolicy.enabled ? editForm.ragPolicy.knowledgeBaseId : null,
      },
    },
  });
}
</script>

<template>
  <a-row :gutter="[16, 16]">
    <a-col :span="10">
      <a-card title="新建助手">
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
          <a-form-item label="默认模型">
            <a-select
              v-model:value="createForm.modelPolicy.providerResourceId"
              :options="modelResources.map((item) => ({ label: item.name, value: item.id }))"
            />
          </a-form-item>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="记忆窗口">
                <a-input-number v-model:value="createForm.memoryPolicy.windowSize" :min="1" style="width: 100%" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="默认知识检索">
                <a-switch v-model:checked="createForm.ragPolicy.enabled" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="默认知识库">
            <a-select
              v-model:value="createForm.ragPolicy.knowledgeBaseId"
              :disabled="!createForm.ragPolicy.enabled"
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
            <a-button danger ghost @click="emit('deleteAssistant', current.id)">删除助手</a-button>
          </a-space>
        </template>

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

          <a-form-item label="默认模型">
            <a-select
              v-model:value="editForm.modelPolicy.providerResourceId"
              :options="modelResources.map((item) => ({ label: item.name, value: item.id }))"
            />
          </a-form-item>

          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="知识检索">
                <a-switch v-model:checked="editForm.ragPolicy.enabled" />
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
              v-model:value="editForm.ragPolicy.knowledgeBaseId"
              :disabled="!editForm.ragPolicy.enabled"
              allow-clear
              :options="knowledgeBaseOptions"
              placeholder="选择知识库"
            />
          </a-form-item>

          <a-alert
            v-if="current.currentRelease?.assistantKnowledge"
            type="info"
            show-icon
            style="margin-bottom: 16px"
            :message="`当前发布冻结：${current.currentRelease.assistantKnowledge.knowledgeBaseName} @ ${current.currentRelease.assistantKnowledge.knowledgeReleaseVersion}`"
            :description="`运行时快照 ${current.currentRelease.assistantKnowledge.snapshotId} · ${current.currentRelease.assistantKnowledge.retrievalMode} · topK ${current.currentRelease.assistantKnowledge.defaultTopK}`"
          />

          <a-space>
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
    </a-col>
  </a-row>
</template>
