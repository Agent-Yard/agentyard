<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import type {
  Agent,
  Assistant,
  CreateAgentPayload,
  Resource,
  UpdateAgentBindingsPayload,
  UpdateAgentPayload,
} from '../types';

const props = defineProps<{
  assistants: Assistant[];
  agents: Agent[];
  resources: Resource[];
}>();

const emit = defineEmits<{
  createAgent: [payload: CreateAgentPayload];
  saveAgent: [payload: { agentId: string; agent: UpdateAgentPayload; bindings: UpdateAgentBindingsPayload }];
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
    promptTemplateResourceId: null,
    inlinePrompt: '',
    ragEnabled: true,
    knowledgeBaseResourceId: 'resource-kb-support',
    memoryWindowSize: 8,
    toolResourceIds: [],
  },
});
const editForm = reactive({
  name: '',
  role: '',
  instructions: '',
  bindings: [] as Array<{ resourceId: string; resourceVersionId: string }>,
  executionPolicy: {
    inheritAssistantDefaults: true,
    modelResourceId: null as string | null,
    promptTemplateResourceId: null as string | null,
    inlinePrompt: '',
    ragEnabled: true,
    knowledgeBaseResourceId: 'resource-kb-support' as string | null,
    memoryWindowSize: 8,
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
const promptResources = computed(() => props.resources.filter((item) => item.type === 'PROMPT_TEMPLATE'));
const knowledgeBases = computed(() => props.resources.filter((item) => item.type === 'KNOWLEDGE_BASE'));
const toolResources = computed(() => props.resources.filter((item) => item.type === 'SKILL' || item.type === 'MCP'));

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

const bindingMap = computed(() =>
  new Map(editForm.bindings.map((binding) => [binding.resourceId, binding.resourceVersionId])),
);

watch(
  currentAgent,
  (agent) => {
    if (!agent) {
      return;
    }

    editForm.name = agent.name;
    editForm.role = agent.role;
    editForm.instructions = agent.instructions;
    editForm.bindings = agent.bindings.map((binding) => ({
      resourceId: binding.resourceId,
      resourceVersionId: binding.resourceVersionId,
    }));
    editForm.executionPolicy = {
      ...agent.executionPolicy,
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
  createForm.executionPolicy.inlinePrompt = '';
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
      executionPolicy: { ...editForm.executionPolicy, toolResourceIds: [...editForm.executionPolicy.toolResourceIds] },
    },
    bindings: {
      bindings: editForm.bindings.map((binding) => ({ ...binding })),
    },
  });
}

function isResourceBound(resourceId: string) {
  return bindingMap.value.has(resourceId);
}

function versionOptions(resource: Resource) {
  return resource.versions.map((version) => ({
    label: `${version.version} · ${version.status}`,
    value: version.id,
  }));
}

function toggleResourceBinding(resource: Resource, checked: boolean) {
  if (checked) {
    const selectedVersionId = bindingMap.value.get(resource.id) ?? resource.effectiveVersion?.id ?? resource.latestVersion?.id ?? resource.versions[0]?.id;
    if (!selectedVersionId) {
      return;
    }
    editForm.bindings = editForm.bindings
      .filter((binding) => binding.resourceId !== resource.id)
      .concat({ resourceId: resource.id, resourceVersionId: selectedVersionId });
    return;
  }

  editForm.bindings = editForm.bindings.filter((binding) => binding.resourceId !== resource.id);
}

function updateBindingVersion(resourceId: string, resourceVersionId: string) {
  editForm.bindings = editForm.bindings.map((binding) =>
    binding.resourceId === resourceId ? { ...binding, resourceVersionId } : binding,
  );
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
              <a-form-item label="Prompt 覆盖">
                <a-select
                  v-model:value="createForm.executionPolicy.promptTemplateResourceId"
                  allow-clear
                  :options="promptResources.map((item) => ({ label: item.name, value: item.id }))"
                />
              </a-form-item>
            </a-col>
          </a-row>
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
              <a-form-item label="Prompt 覆盖">
                <a-select
                  v-model:value="editForm.executionPolicy.promptTemplateResourceId"
                  allow-clear
                  :options="promptResources.map((item) => ({ label: item.name, value: item.id }))"
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
          <a-form-item label="Inline Prompt">
            <a-textarea v-model:value="editForm.executionPolicy.inlinePrompt" :rows="4" />
          </a-form-item>

          <a-form-item label="资源版本绑定">
            <a-space direction="vertical" style="width: 100%" size="middle">
              <a-card
                v-for="resource in resources"
                :key="resource.id"
                size="small"
              >
                <a-space direction="vertical" style="width: 100%">
                  <a-space style="justify-content: space-between; width: 100%">
                    <a-checkbox
                      :checked="isResourceBound(resource.id)"
                      @change="(event: { target: { checked: boolean } }) => toggleResourceBinding(resource, event.target.checked)"
                    >
                      {{ resource.name }} · {{ resource.type }}
                    </a-checkbox>
                    <a-space>
                      <a-tag color="blue">最新 {{ resource.latestVersion?.version ?? '-' }}</a-tag>
                      <a-tag color="green">生效 {{ resource.effectiveVersion?.version ?? '-' }}</a-tag>
                    </a-space>
                  </a-space>
                  <a-typography-text type="secondary">
                    {{ resource.summary }}
                  </a-typography-text>
                  <a-select
                    :disabled="!isResourceBound(resource.id)"
                    :value="bindingMap.get(resource.id)"
                    :options="versionOptions(resource)"
                    placeholder="选择绑定版本"
                    @change="(value: string | number) => updateBindingVersion(resource.id, String(value))"
                  />
                </a-space>
              </a-card>
            </a-space>
          </a-form-item>

          <a-button type="primary" html-type="submit">保存智能体</a-button>
        </a-form>
      </a-card>
    </a-col>
  </a-row>
</template>
