<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import type {
  Agent,
  Assistant,
  CreateAgentPayload,
  Resource,
  UpdateAgentPayload,
} from '../types';

const props = defineProps<{
  assistants: Assistant[];
  agents: Agent[];
  resources: Resource[];
}>();

const emit = defineEmits<{
  createAgent: [payload: CreateAgentPayload];
  saveAgent: [payload: { agentId: string; agent: UpdateAgentPayload }];
  deleteAgent: [agentId: string];
}>();

const selectedAssistantId = ref('');
const selectedAgentId = ref('');
const createForm = reactive<CreateAgentPayload>({
  assistantId: '',
  name: '',
  role: '',
  instructions: '',
  executionPolicy: {
    inheritAssistantDefaults: true,
    modelResourceId: null,
    systemPrompt: '',
    ragEnabled: false,
    knowledgeBaseResourceId: null,
    memoryWindowSize: 8,
    skillResourceIds: [],
    toolResourceIds: [],
  },
});
const editForm = reactive({
  name: '',
  role: '',
  instructions: '',
  executionPolicy: {
    inheritAssistantDefaults: true,
    modelResourceId: null as string | null,
    systemPrompt: '',
    ragEnabled: false,
    knowledgeBaseResourceId: null as string | null,
    memoryWindowSize: 8,
    skillResourceIds: [] as string[],
    toolResourceIds: [] as string[],
  },
});

const currentAssistant = computed(() =>
  props.assistants.find((item) => item.id === selectedAssistantId.value) ?? props.assistants[0],
);

const assistantAgents = computed(() =>
  props.agents.filter((item) => item.assistantId === currentAssistant.value?.id),
);

const currentAgent = computed(() =>
  assistantAgents.value.find((item) => item.id === selectedAgentId.value) ?? assistantAgents.value[0],
);
const modelResources = computed(() => props.resources.filter((item) => item.type === 'LLM_MODEL'));
const skillResources = computed(() => props.resources.filter((item) => item.type === 'SKILL'));
const knowledgeBases = computed(() => props.resources.filter((item) => item.type === 'KNOWLEDGE_BASE'));
const toolResources = computed(() => props.resources.filter((item) => item.type === 'TOOL'));

function toolOperationSummary(resource: Resource) {
  return resource.effectiveVersion?.configuration.tool?.operations?.map((operation) => operation.name).join(' / ')
    || resource.latestVersion?.configuration.tool?.operations?.map((operation) => operation.name).join(' / ')
    || '未定义操作';
}

function syncCreateFormDefaults() {
  if (!knowledgeBases.value.some((item) => item.id === createForm.executionPolicy.knowledgeBaseResourceId)) {
    createForm.executionPolicy.knowledgeBaseResourceId = knowledgeBases.value[0]?.id ?? null;
  }
  createForm.executionPolicy.ragEnabled = createForm.executionPolicy.knowledgeBaseResourceId !== null;
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

    if (!createForm.assistantId) {
      createForm.assistantId = assistants[0].id;
    }
  },
  { immediate: true },
);

watch(
  currentAssistant,
  (assistant) => {
    if (!assistant) {
      selectedAgentId.value = '';
      return;
    }

    if (!assistantAgents.value.some((item) => item.id === selectedAgentId.value)) {
      selectedAgentId.value = assistantAgents.value[0]?.id ?? '';
    }

    createForm.assistantId = assistant.id;
  },
  { immediate: true },
);

watch(knowledgeBases, syncCreateFormDefaults, { immediate: true });

watch(
  currentAgent,
  (agent) => {
    if (!agent) {
      return;
    }

    editForm.name = agent.name;
    editForm.role = agent.role;
    editForm.instructions = agent.instructions;
    editForm.executionPolicy = {
      ...agent.executionPolicy,
      skillResourceIds: [...agent.executionPolicy.skillResourceIds],
      toolResourceIds: [...agent.executionPolicy.toolResourceIds],
    };
  },
  { immediate: true },
);

function submitCreate() {
  emit('createAgent', { ...createForm });
  createForm.name = '';
  createForm.role = '';
  createForm.instructions = '';
  createForm.executionPolicy.systemPrompt = '';
}

function submitSave() {
  if (!currentAgent.value) {
    return;
  }

  emit('saveAgent', {
    agentId: currentAgent.value.id,
      agent: {
        name: editForm.name,
        role: editForm.role,
        instructions: editForm.instructions,
        executionPolicy: {
          ...editForm.executionPolicy,
          skillResourceIds: [...editForm.executionPolicy.skillResourceIds],
          toolResourceIds: [...editForm.executionPolicy.toolResourceIds],
        },
      },
    });
}
</script>

<template>
  <a-row :gutter="[16, 16]">
    <a-col :span="9">
      <a-card title="新建智能体">
        <a-form layout="vertical" :model="createForm" @finish="submitCreate">
          <a-form-item label="所属助手" name="assistantId">
            <a-select
              v-model:value="createForm.assistantId"
              :options="assistants.map((item) => ({ label: item.name, value: item.id }))"
            />
          </a-form-item>
          <a-form-item label="智能体名称" name="name">
            <a-input v-model:value="createForm.name" placeholder="例如：投诉分流智能体" />
          </a-form-item>
          <a-form-item label="职责角色" name="role">
            <a-input v-model:value="createForm.role" placeholder="例如：router / analyst / reviewer" />
          </a-form-item>
          <a-form-item label="指令说明" name="instructions">
            <a-textarea v-model:value="createForm.instructions" :rows="4" />
          </a-form-item>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="模型覆盖">
                <a-select
                  v-model:value="createForm.executionPolicy.modelResourceId"
                  allow-clear
                  :options="modelResources.map((item) => ({ label: item.name, value: item.id }))"
                />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="挂载技能">
                <a-select
                  v-model:value="createForm.executionPolicy.skillResourceIds"
                  mode="multiple"
                  :options="skillResources.map((item) => ({ label: item.name, value: item.id }))"
                />
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="System Prompt">
            <a-textarea v-model:value="createForm.executionPolicy.systemPrompt" :rows="4" />
          </a-form-item>
          <a-button type="primary" html-type="submit">创建智能体</a-button>
        </a-form>
      </a-card>

      <a-card title="助手视图">
        <a-space direction="vertical" style="width: 100%">
          <a-select
            v-model:value="selectedAssistantId"
            :options="assistants.map((item) => ({ label: item.name, value: item.id }))"
          />
          <a-list :data-source="assistantAgents">
            <template #renderItem="{ item }">
              <a-list-item class="clickable-item" @click="selectedAgentId = item.id">
                <a-list-item-meta :title="item.name" :description="item.role" />
                <a-tag v-if="selectedAgentId === item.id" color="blue">当前</a-tag>
              </a-list-item>
            </template>
          </a-list>
        </a-space>
      </a-card>
    </a-col>

    <a-col :span="15">
      <a-card v-if="currentAgent" :title="currentAgent.name">
        <template #extra>
          <a-tag color="blue">
            {{ currentAssistant?.name ?? currentAgent.assistantId }}
          </a-tag>
        </template>

        <a-form layout="vertical" :model="editForm" @finish="submitSave">
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="智能体名称" name="name">
                <a-input v-model:value="editForm.name" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="职责角色" name="role">
                <a-input v-model:value="editForm.role" />
              </a-form-item>
            </a-col>
          </a-row>

          <a-form-item label="指令说明" name="instructions">
            <a-textarea v-model:value="editForm.instructions" :rows="5" />
          </a-form-item>
          <a-form-item label="System Prompt">
            <a-textarea v-model:value="editForm.executionPolicy.systemPrompt" :rows="5" />
          </a-form-item>
          <a-row :gutter="[16, 16]">
            <a-col :span="8">
              <a-form-item label="继承助手默认">
                <a-switch v-model:checked="editForm.executionPolicy.inheritAssistantDefaults" />
              </a-form-item>
            </a-col>
            <a-col :span="8">
              <a-form-item label="RAG">
                <a-switch v-model:checked="editForm.executionPolicy.ragEnabled" />
              </a-form-item>
            </a-col>
            <a-col :span="8">
              <a-form-item label="记忆窗口">
                <a-input-number v-model:value="editForm.executionPolicy.memoryWindowSize" :min="1" style="width: 100%" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="模型覆盖">
                <a-select
                  v-model:value="editForm.executionPolicy.modelResourceId"
                  allow-clear
                  :disabled="editForm.executionPolicy.inheritAssistantDefaults"
                  :options="modelResources.map((item) => ({ label: item.name, value: item.id }))"
                />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="挂载技能">
                <a-select
                  v-model:value="editForm.executionPolicy.skillResourceIds"
                  mode="multiple"
                  :options="skillResources.map((item) => ({ label: item.name, value: item.id }))"
                />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="知识库覆盖">
                <a-select
                  v-model:value="editForm.executionPolicy.knowledgeBaseResourceId"
                  allow-clear
                  :options="knowledgeBases.map((item) => ({ label: item.name, value: item.id }))"
                />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="可用工具集">
                <a-select
                  v-model:value="editForm.executionPolicy.toolResourceIds"
                  mode="multiple"
                  :options="toolResources.map((item) => ({ label: item.name, value: item.id }))"
                />
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="Tool 发布冻结">
            <a-space direction="vertical" style="width: 100%" size="middle">
              <a-alert
                type="info"
                show-icon
                message="Tool 不再单独固定版本"
                description="agent 只声明可用 Tool。助手发布时，平台会和模型、Skill、知识库一样，统一冻结当前生效版本。"
              />
              <a-card
                v-for="resource in toolResources"
                :key="resource.id"
                size="small"
              >
                <a-space direction="vertical" style="width: 100%">
                  <a-space style="justify-content: space-between; width: 100%">
                    <a-tag :color="editForm.executionPolicy.toolResourceIds.includes(resource.id) ? 'blue' : 'default'">
                      {{ editForm.executionPolicy.toolResourceIds.includes(resource.id) ? '已启用' : '未启用' }}
                    </a-tag>
                    <a-space>
                      <a-tag color="blue">最新 {{ resource.latestVersion?.version ?? '-' }}</a-tag>
                      <a-tag color="green">生效 {{ resource.effectiveVersion?.version ?? '-' }}</a-tag>
                    </a-space>
                  </a-space>
                  <a-typography-text type="secondary">
                    {{ resource.summary }}
                  </a-typography-text>
                  <a-typography-text type="secondary">
                    操作定义：{{ toolOperationSummary(resource) }}
                  </a-typography-text>
                  <a-typography-text type="secondary">
                    发布时会自动冻结生效版本：{{ resource.effectiveVersion?.version ?? resource.latestVersion?.version ?? '-' }}
                  </a-typography-text>
                </a-space>
              </a-card>
            </a-space>
          </a-form-item>

          <a-space>
            <a-button type="primary" html-type="submit">保存智能体</a-button>
            <a-popconfirm
              title="确认删除该智能体？"
              description="删除后会一并回收该智能体的资源绑定，并重建助手默认编排。"
              ok-text="删除"
              cancel-text="取消"
              @confirm="emit('deleteAgent', currentAgent.id)"
            >
              <a-button danger>删除智能体</a-button>
            </a-popconfirm>
          </a-space>
        </a-form>
      </a-card>
    </a-col>
  </a-row>
</template>
