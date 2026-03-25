<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import type { Assistant, CreateAssistantPayload, Resource, Scenario, UpdateAssistantPayload } from '../types';

const props = defineProps<{
  assistants: Assistant[];
  scenarios: Scenario[];
  resources: Resource[];
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
    knowledgeBaseResourceId: null,
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
    knowledgeBaseResourceId: null,
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
const knowledgeBases = computed(() => props.resources.filter((item) => item.type === 'KNOWLEDGE_BASE'));

function syncCreateFormResourceDefaults() {
  if (!modelResources.value.some((item) => item.id === createForm.modelPolicy.providerResourceId)) {
    createForm.modelPolicy.providerResourceId = modelResources.value[0]?.id ?? null;
  }
  if (!knowledgeBases.value.some((item) => item.id === createForm.ragPolicy.knowledgeBaseResourceId)) {
    createForm.ragPolicy.knowledgeBaseResourceId = knowledgeBases.value[0]?.id ?? null;
  }
  createForm.ragPolicy.enabled = createForm.ragPolicy.knowledgeBaseResourceId !== null;
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

watch([modelResources, knowledgeBases], syncCreateFormResourceDefaults, { immediate: true });

function submitCreate() {
  emit('createAssistant', { ...createForm });
  createForm.name = '';
  createForm.description = '';
}

function submitUpdate() {
  if (!current.value) {
    return;
  }

  emit('updateAssistant', {
    assistantId: current.value.id,
    data: { ...editForm },
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
          <a-row :gutter="[16, 16]">
            <a-col :span="24">
              <a-form-item label="默认模型">
                <a-select
                  v-model:value="createForm.modelPolicy.providerResourceId"
                  :options="modelResources.map((item) => ({ label: item.name, value: item.id }))"
                />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="[16, 16]">
            <a-col :span="24">
              <a-form-item label="记忆窗口">
                <a-input-number v-model:value="createForm.memoryPolicy.windowSize" :min="1" style="width: 100%" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="默认知识库">
                <a-select
                  v-model:value="createForm.ragPolicy.knowledgeBaseResourceId"
                  :options="knowledgeBases.map((item) => ({ label: item.name, value: item.id }))"
                />
              </a-form-item>
            </a-col>
          </a-row>
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
          <a-tag color="blue">{{ current.agents.length }} 个智能体</a-tag>
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
          <a-row :gutter="[16, 16]">
            <a-col :span="24">
              <a-form-item label="默认模型">
                <a-select
                  v-model:value="editForm.modelPolicy.providerResourceId"
                  :options="modelResources.map((item) => ({ label: item.name, value: item.id }))"
                />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="[16, 16]">
            <a-col :span="24">
              <a-form-item label="记忆窗口">
                <a-input-number v-model:value="editForm.memoryPolicy.windowSize" :min="1" style="width: 100%" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="默认知识库">
                <a-select
                  v-model:value="editForm.ragPolicy.knowledgeBaseResourceId"
                  :options="knowledgeBases.map((item) => ({ label: item.name, value: item.id }))"
                />
              </a-form-item>
            </a-col>
          </a-row>

          <a-button type="primary" html-type="submit">保存助手</a-button>
          <a-popconfirm
            title="确认删除该助手？"
            description="如果助手下仍有智能体或私有资源，删除会被阻止；删除成功后会回收编排和发布快照。"
            ok-text="删除"
            cancel-text="取消"
            @confirm="emit('deleteAssistant', current.id)"
          >
            <a-button danger style="margin-left: 12px">删除助手</a-button>
          </a-popconfirm>
        </a-form>
      </a-card>

      <a-card v-if="current" title="当前发布快照">
        <template v-if="current.currentRelease">
          <a-descriptions :column="2" size="small">
            <a-descriptions-item label="发布版本">{{ current.currentRelease.releaseVersion }}</a-descriptions-item>
            <a-descriptions-item label="发布时间">{{ current.currentRelease.publishedAt }}</a-descriptions-item>
          </a-descriptions>
          <a-list :data-source="current.currentRelease.resources" size="small">
            <template #renderItem="{ item }">
              <a-list-item>
                <a-list-item-meta
                  :title="`${item.resourceName} · ${item.resourceVersion}`"
                  :description="`${item.resourceType} · ${item.boundAgents.join(' / ')}`"
                />
              </a-list-item>
            </template>
          </a-list>
        </template>
        <a-empty v-else description="当前还没有已发布的资源冻结快照" />
      </a-card>

      <a-card v-if="current?.releases.length" title="发布历史">
        <a-list :data-source="current.releases" size="small">
          <template #renderItem="{ item }">
            <a-list-item>
              <a-list-item-meta
                :title="`${item.releaseVersion} · ${item.status}`"
                :description="`${item.resources.length} 个资源锚点 · ${item.publishedAt ?? item.createdAt}`"
              />
            </a-list-item>
          </template>
        </a-list>
      </a-card>
    </a-col>
  </a-row>
</template>
