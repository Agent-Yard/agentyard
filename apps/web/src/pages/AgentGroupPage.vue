<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import PageHeaderCard from '../components/PageHeaderCard.vue';
import type { AgentGroup, CreateAgentGroupPayload, Scenario, UpdateAgentGroupPayload } from '../types';

const props = defineProps<{
  agentGroups: AgentGroup[];
  scenarios: Scenario[];
}>();

const emit = defineEmits<{
  createAgentGroup: [payload: CreateAgentGroupPayload];
  updateAgentGroup: [payload: { agentGroupId: string; data: UpdateAgentGroupPayload }];
}>();

const selectedGroupId = ref('');
const createForm = reactive<CreateAgentGroupPayload>({
  scenarioId: '',
  name: '',
  description: '',
});
const editForm = reactive<UpdateAgentGroupPayload>({
  name: '',
  description: '',
  status: 'DRAFT',
});

const current = computed(() =>
  props.agentGroups.find((item) => item.id === selectedGroupId.value) ?? props.agentGroups[0],
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
  },
  { immediate: true },
);

watch(
  current,
  (group) => {
    if (!group) {
      return;
    }

    editForm.name = group.name;
    editForm.description = group.description;
    editForm.status = group.version.status;
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

function submitCreate() {
  emit('createAgentGroup', { ...createForm });
  createForm.name = '';
  createForm.description = '';
}

function submitUpdate() {
  if (!current.value) {
    return;
  }

  emit('updateAgentGroup', {
    agentGroupId: current.value.id,
    data: { ...editForm },
  });
}
</script>

<template>
  <PageHeaderCard
    title="智能体组配置页"
    subtitle="管理智能体组的基础信息、版本状态和所属场景。"
  />

  <a-row :gutter="[16, 16]">
    <a-col :span="10">
      <a-card title="新建智能体组">
        <a-form layout="vertical" :model="createForm" @finish="submitCreate">
          <a-form-item label="所属场景" name="scenarioId">
            <a-select
              v-model:value="createForm.scenarioId"
              :options="scenarios.map((item) => ({ label: item.name, value: item.id }))"
            />
          </a-form-item>
          <a-form-item label="智能体组名称" name="name">
            <a-input v-model:value="createForm.name" placeholder="例如：售后升级智能体组" />
          </a-form-item>
          <a-form-item label="描述" name="description">
            <a-textarea
              v-model:value="createForm.description"
              :rows="4"
              placeholder="说明该智能体组负责的业务目标和协作方式"
            />
          </a-form-item>
          <a-button type="primary" html-type="submit">创建智能体组</a-button>
        </a-form>
      </a-card>

      <a-card title="智能体组列表">
        <a-list :data-source="agentGroups">
          <template #renderItem="{ item }">
            <a-list-item class="clickable-item" @click="selectedGroupId = item.id">
              <a-list-item-meta :title="item.name" :description="item.description" />
              <a-space>
                <a-tag v-if="selectedGroupId === item.id" color="blue">当前</a-tag>
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
          <a-form-item label="智能体组名称" name="name">
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

          <a-button type="primary" html-type="submit">保存智能体组</a-button>
        </a-form>
      </a-card>
    </a-col>
  </a-row>
</template>
