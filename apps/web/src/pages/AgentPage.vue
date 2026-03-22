<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import PageHeaderCard from '../components/PageHeaderCard.vue';
import type {
  Agent,
  AgentGroup,
  CreateAgentPayload,
  Resource,
  UpdateAgentBindingsPayload,
  UpdateAgentPayload,
} from '../types';

const props = defineProps<{
  agentGroups: AgentGroup[];
  agents: Agent[];
  resources: Resource[];
}>();

const emit = defineEmits<{
  createAgent: [payload: CreateAgentPayload];
  saveAgent: [payload: { agentId: string; agent: UpdateAgentPayload; bindings: UpdateAgentBindingsPayload }];
}>();

const selectedGroupId = ref('');
const selectedAgentId = ref('');
const createForm = reactive<CreateAgentPayload>({
  agentGroupId: '',
  name: '',
  role: '',
  instructions: '',
});
const editForm = reactive({
  name: '',
  role: '',
  instructions: '',
  resourceIds: [] as string[],
});

const currentGroup = computed(() =>
  props.agentGroups.find((item) => item.id === selectedGroupId.value) ?? props.agentGroups[0],
);

const groupAgents = computed(() =>
  props.agents.filter((item) => item.agentGroupId === currentGroup.value?.id),
);

const currentAgent = computed(() =>
  groupAgents.value.find((item) => item.id === selectedAgentId.value) ?? groupAgents.value[0],
);

watch(
  () => props.agentGroups,
  (groups) => {
    if (!groups.length) {
      selectedGroupId.value = '';
      return;
    }

    if (!groups.some((item) => item.id === selectedGroupId.value)) {
      selectedGroupId.value = groups[0].id;
    }

    if (!createForm.agentGroupId) {
      createForm.agentGroupId = groups[0].id;
    }
  },
  { immediate: true },
);

watch(
  currentGroup,
  (group) => {
    if (!group) {
      selectedAgentId.value = '';
      return;
    }

    if (!groupAgents.value.some((item) => item.id === selectedAgentId.value)) {
      selectedAgentId.value = groupAgents.value[0]?.id ?? '';
    }

    createForm.agentGroupId = group.id;
  },
  { immediate: true },
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
    editForm.resourceIds = agent.bindings.map((binding) => binding.resourceId);
  },
  { immediate: true },
);

function submitCreate() {
  emit('createAgent', { ...createForm });
  createForm.name = '';
  createForm.role = '';
  createForm.instructions = '';
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
    },
    bindings: {
      resourceIds: [...editForm.resourceIds],
    },
  });
}
</script>

<template>
  <PageHeaderCard
    title="智能体详情与资源绑定页"
    subtitle="编辑智能体职责、提示说明和 Skill / MCP / 知识库绑定关系。"
  />

  <a-row :gutter="[16, 16]">
    <a-col :span="9">
      <a-card title="新建智能体">
        <a-form layout="vertical" :model="createForm" @finish="submitCreate">
          <a-form-item label="所属智能体组" name="agentGroupId">
            <a-select
              v-model:value="createForm.agentGroupId"
              :options="agentGroups.map((item) => ({ label: item.name, value: item.id }))"
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
          <a-button type="primary" html-type="submit">创建智能体</a-button>
        </a-form>
      </a-card>

      <a-card title="智能体组视图">
        <a-space direction="vertical" style="width: 100%">
          <a-select
            v-model:value="selectedGroupId"
            :options="agentGroups.map((item) => ({ label: item.name, value: item.id }))"
          />
          <a-list :data-source="groupAgents">
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
            {{ currentGroup?.name ?? currentAgent.agentGroupId }}
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

          <a-form-item label="资源绑定" name="resourceIds">
            <a-select
              v-model:value="editForm.resourceIds"
              mode="multiple"
              :options="resources.map((item) => ({
                label: `${item.name} · ${item.type}`,
                value: item.id,
              }))"
              placeholder="选择该智能体可用的资源"
            />
          </a-form-item>

          <a-button type="primary" html-type="submit">保存智能体</a-button>
        </a-form>
      </a-card>
    </a-col>
  </a-row>
</template>
