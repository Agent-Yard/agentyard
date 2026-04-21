<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import CatalogFormDrawer from '../components/CatalogFormDrawer.vue';
import ObjectHistoryPanel from '../components/ObjectHistoryPanel.vue';
import ObjectReferencePanel from '../components/ObjectReferencePanel.vue';
import type {
  Agent,
  Assistant,
  CreateAgentPayload,
  KnowledgeBase,
  Resource,
  UpdateAgentPayload,
} from '../types';

const props = defineProps<{
  assistants: Assistant[];
  agents: Agent[];
  resources: Resource[];
  knowledgeBases: KnowledgeBase[];
  catalogRevision: number;
  canManageGovernance: boolean;
}>();

const emit = defineEmits<{
  createAgent: [payload: CreateAgentPayload];
  saveAgent: [payload: { agentId: string; agent: UpdateAgentPayload }];
  deleteAgent: [agentId: string];
}>();

const selectedAssistantId = ref('');
const selectedAgentId = ref('');
const createDrawerOpen = ref(false);
const editDrawerOpen = ref(false);
const createForm = reactive<CreateAgentPayload>({
  assistantId: '',
  name: '',
  role: '',
  responsibility: '',
  canOwnSession: true,
  allowedActions: ['REPLY', 'NO_REPLY', 'SWITCH_OWNER', 'RUN_PLAYBOOK', 'SESSION_HUMAN_HANDOFF'],
  switchableOwnerAgentIds: [],
  playbookIds: [],
  executionPolicy: {
    inheritAssistantDefaults: true,
    modelResourceId: null,
    privacyModelResourceId: null,
    privacyMappingEnabled: null,
    systemPrompt: '',
    knowledgeEnabled: false,
    inheritAssistantKnowledge: true,
    knowledgeBaseId: null,
    memoryWindowSize: 8,
    skillResourceIds: [],
    toolResourceIds: [],
  },
});
const editForm = reactive<UpdateAgentPayload>({
  name: '',
  role: '',
  responsibility: '',
  canOwnSession: true,
  allowedActions: ['REPLY', 'NO_REPLY', 'SWITCH_OWNER', 'RUN_PLAYBOOK', 'SESSION_HUMAN_HANDOFF'],
  switchableOwnerAgentIds: [],
  playbookIds: [],
  executionPolicy: {
    inheritAssistantDefaults: true,
    modelResourceId: null,
    privacyModelResourceId: null,
    privacyMappingEnabled: null,
    systemPrompt: '',
    knowledgeEnabled: false,
    inheritAssistantKnowledge: true,
    knowledgeBaseId: null,
    memoryWindowSize: 8,
    skillResourceIds: [],
    toolResourceIds: [],
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
const currentReleaseAgent = computed(() =>
  currentAssistant.value?.currentRelease?.agents.find((item) => item.agentId === currentAgent.value?.id) ?? null,
);
const availableOwnerOptions = computed(() =>
  assistantAgents.value.map((item) => ({ label: item.name, value: item.id })),
);
const availablePlaybookOptions = computed(() =>
  (currentAssistant.value?.playbooks ?? []).map((item) => ({ label: item.name, value: item.id })),
);
const modelResources = computed(() => props.resources.filter((item) => item.type === 'LLM_MODEL'));
const privateModelResources = computed(() =>
  modelResources.value.filter(
    (item) =>
      item.effectiveVersion?.configuration.llmModel?.privateDeployment
      || item.latestVersion?.configuration.llmModel?.privateDeployment,
  ),
);
const skillResources = computed(() => props.resources.filter((item) => item.type === 'SKILL'));
const toolResources = computed(() => props.resources.filter((item) => item.type === 'TOOL'));
const knowledgeBaseOptions = computed(() => props.knowledgeBases.map((item) => ({ label: item.name, value: item.id })));
const actionOptions = [
  { label: 'REPLY', value: 'REPLY' },
  { label: 'NO_REPLY', value: 'NO_REPLY' },
  { label: 'SWITCH_OWNER', value: 'SWITCH_OWNER' },
  { label: 'RUN_PLAYBOOK', value: 'RUN_PLAYBOOK' },
  { label: 'SESSION_HUMAN_HANDOFF', value: 'SESSION_HUMAN_HANDOFF' },
];
const privacyModeOptions = [
  { label: '继承助手', value: 'INHERIT' },
  { label: '强制开启', value: 'ENABLED' },
  { label: '强制关闭', value: 'DISABLED' },
];

const createPrivacyMode = computed({
  get: () => {
    if (createForm.executionPolicy.privacyMappingEnabled === true) {
      return 'ENABLED';
    }
    if (createForm.executionPolicy.privacyMappingEnabled === false) {
      return 'DISABLED';
    }
    return 'INHERIT';
  },
  set: (value: string) => {
    createForm.executionPolicy.privacyMappingEnabled = value === 'INHERIT' ? null : value === 'ENABLED';
  },
});

const editPrivacyMode = computed({
  get: () => {
    if (editForm.executionPolicy.privacyMappingEnabled === true) {
      return 'ENABLED';
    }
    if (editForm.executionPolicy.privacyMappingEnabled === false) {
      return 'DISABLED';
    }
    return 'INHERIT';
  },
  set: (value: string) => {
    editForm.executionPolicy.privacyMappingEnabled = value === 'INHERIT' ? null : value === 'ENABLED';
  },
});

function toolOperationSummary(resource: Resource) {
  return resource.effectiveVersion?.configuration.tool?.operations?.map((operation) => operation.name).join(' / ')
    || resource.latestVersion?.configuration.tool?.operations?.map((operation) => operation.name).join(' / ')
    || '未定义操作';
}

function resourceName(resourceId: string | null | undefined) {
  if (!resourceId) {
    return '未配置';
  }
  return props.resources.find((item) => item.id === resourceId)?.name ?? resourceId;
}

function knowledgeBaseName(knowledgeBaseId: string | null | undefined) {
  if (!knowledgeBaseId) {
    return '未配置';
  }
  return props.knowledgeBases.find((item) => item.id === knowledgeBaseId)?.name ?? knowledgeBaseId;
}

function playbookNames(playbookIds: string[]) {
  if (!playbookIds.length) {
    return '未配置';
  }
  return playbookIds
    .map((playbookId) => currentAssistant.value?.playbooks.find((item) => item.id === playbookId)?.name ?? playbookId)
    .join(' / ');
}

function syncEditForm(agent: Agent) {
  editForm.name = agent.name;
  editForm.role = agent.role;
  editForm.responsibility = agent.responsibility;
  editForm.canOwnSession = agent.canOwnSession;
  editForm.allowedActions = [...agent.allowedActions];
  editForm.switchableOwnerAgentIds = [...agent.switchableOwnerAgentIds];
  editForm.playbookIds = [...agent.playbookIds];
  editForm.executionPolicy = {
    ...agent.executionPolicy,
    skillResourceIds: [...agent.executionPolicy.skillResourceIds],
    toolResourceIds: [...agent.executionPolicy.toolResourceIds],
  };
}

function normalizeKnowledgeSelection(
  policy: CreateAgentPayload['executionPolicy'] | UpdateAgentPayload['executionPolicy'],
) {
  if (!policy.knowledgeEnabled) {
    policy.knowledgeBaseId = null;
    policy.inheritAssistantKnowledge = true;
    return;
  }
  if (!policy.inheritAssistantKnowledge && !policy.knowledgeBaseId) {
    policy.knowledgeBaseId = props.knowledgeBases[0]?.id ?? null;
  }
  if (policy.inheritAssistantKnowledge) {
    policy.knowledgeBaseId = null;
  }
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

watch(
  currentAgent,
  (agent) => {
    if (!agent) {
      return;
    }
    syncEditForm(agent);
  },
  { immediate: true },
);

watch(
  () => [
    createForm.executionPolicy.knowledgeEnabled,
    createForm.executionPolicy.inheritAssistantKnowledge,
    props.knowledgeBases,
  ],
  () => normalizeKnowledgeSelection(createForm.executionPolicy),
  { immediate: true },
);

watch(
  () => [
    editForm.executionPolicy.knowledgeEnabled,
    editForm.executionPolicy.inheritAssistantKnowledge,
    props.knowledgeBases,
  ],
  () => normalizeKnowledgeSelection(editForm.executionPolicy),
  { immediate: true },
);

watch(
  () => [createForm.executionPolicy.privacyMappingEnabled, privateModelResources.value],
  () => {
    if (createForm.executionPolicy.privacyMappingEnabled === true && !createForm.executionPolicy.privacyModelResourceId) {
      createForm.executionPolicy.privacyModelResourceId = privateModelResources.value[0]?.id ?? null;
    }
    if (createForm.executionPolicy.privacyMappingEnabled !== true) {
      createForm.executionPolicy.privacyModelResourceId = null;
    }
  },
  { immediate: true },
);

watch(
  () => [editForm.executionPolicy.privacyMappingEnabled, privateModelResources.value],
  () => {
    if (editForm.executionPolicy.privacyMappingEnabled === true && !editForm.executionPolicy.privacyModelResourceId) {
      editForm.executionPolicy.privacyModelResourceId = privateModelResources.value[0]?.id ?? null;
    }
    if (editForm.executionPolicy.privacyMappingEnabled !== true) {
      editForm.executionPolicy.privacyModelResourceId = null;
    }
  },
  { immediate: true },
);

function submitCreate() {
  emit('createAgent', {
    ...createForm,
    allowedActions: [...createForm.allowedActions],
    switchableOwnerAgentIds: [...createForm.switchableOwnerAgentIds],
    playbookIds: [...createForm.playbookIds],
    executionPolicy: {
      ...createForm.executionPolicy,
      skillResourceIds: [...createForm.executionPolicy.skillResourceIds],
      toolResourceIds: [...createForm.executionPolicy.toolResourceIds],
    },
  });
  createDrawerOpen.value = false;
  createForm.name = '';
  createForm.role = '';
  createForm.responsibility = '';
  createForm.canOwnSession = true;
  createForm.allowedActions = ['REPLY', 'NO_REPLY', 'SWITCH_OWNER', 'RUN_PLAYBOOK', 'SESSION_HUMAN_HANDOFF'];
  createForm.switchableOwnerAgentIds = [];
  createForm.playbookIds = [];
  createForm.executionPolicy.systemPrompt = '';
  createForm.executionPolicy.modelResourceId = null;
  createForm.executionPolicy.privacyModelResourceId = null;
  createForm.executionPolicy.privacyMappingEnabled = null;
  createForm.executionPolicy.skillResourceIds = [];
  createForm.executionPolicy.toolResourceIds = [];
}

function openCreateDrawer() {
  createForm.assistantId = currentAssistant.value?.id ?? props.assistants[0]?.id ?? '';
  createDrawerOpen.value = true;
}

function openEditDrawer(agentId: string) {
  selectedAgentId.value = agentId;
  const agent = assistantAgents.value.find((item) => item.id === agentId);
  if (agent) {
    syncEditForm(agent);
  }
  editDrawerOpen.value = true;
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
      responsibility: editForm.responsibility,
      canOwnSession: editForm.canOwnSession,
      allowedActions: [...editForm.allowedActions],
      switchableOwnerAgentIds: [...editForm.switchableOwnerAgentIds],
      playbookIds: [...editForm.playbookIds],
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
  <div v-if="canManageGovernance" class="page-inline-toolbar">
    <a-button type="primary" @click="openCreateDrawer">新建智能体</a-button>
  </div>

  <a-row :gutter="[16, 16]">
    <a-col :span="9">
      <a-card title="助手视图">
        <a-space direction="vertical" style="width: 100%">
          <a-select
            v-model:value="selectedAssistantId"
            :options="assistants.map((item) => ({ label: item.name, value: item.id }))"
          />
          <a-list :data-source="assistantAgents" :locale="{ emptyText: '当前助手下暂无智能体' }">
            <template #renderItem="{ item }">
              <a-list-item
                class="clickable-item"
                :class="{ 'graph-list-item--active': currentAgent?.id === item.id }"
                @click="selectedAgentId = item.id"
              >
                <a-list-item-meta :title="item.name" :description="item.role || '暂无角色'" />
                <a-space>
                  <a-tag class="console-accent-tag" v-if="item.canOwnSession">可接管会话</a-tag>
                  <a-button v-if="canManageGovernance" type="link" size="small" @click.stop="openEditDrawer(item.id)">编辑</a-button>
                  <a-button v-if="canManageGovernance" type="link" size="small" danger @click.stop="emit('deleteAgent', item.id)">
                    删除
                  </a-button>
                </a-space>
              </a-list-item>
            </template>
          </a-list>
        </a-space>
      </a-card>
    </a-col>

    <a-col :span="15">
      <div v-if="currentAgent" class="console-stack">
        <a-card :title="currentAgent.name">
          <template #extra>
            <a-space>
              <a-tag class="console-accent-tag">{{ currentAssistant?.name ?? currentAgent.assistantId }}</a-tag>
              <a-tag>{{ currentAgent.allowedActions.length }} 动作</a-tag>
            </a-space>
          </template>

          <a-descriptions :column="2" size="small">
            <a-descriptions-item label="智能体 ID">{{ currentAgent.id }}</a-descriptions-item>
            <a-descriptions-item label="职责角色">{{ currentAgent.role || '未配置' }}</a-descriptions-item>
            <a-descriptions-item label="可接管会话">
              {{ currentAgent.canOwnSession ? '是' : '否' }}
            </a-descriptions-item>
            <a-descriptions-item label="可切换 Owner">
              {{ currentAgent.switchableOwnerAgentIds.length }}
            </a-descriptions-item>
            <a-descriptions-item label="职责说明" :span="2">
              {{ currentAgent.responsibility || '暂无说明' }}
            </a-descriptions-item>
          </a-descriptions>
        </a-card>

        <a-card size="small" title="执行策略摘要">
          <a-descriptions :column="2" size="small">
            <a-descriptions-item label="默认模型">
              {{
                currentAgent.executionPolicy.inheritAssistantDefaults
                  ? '继承助手'
                  : resourceName(currentAgent.executionPolicy.modelResourceId)
              }}
            </a-descriptions-item>
            <a-descriptions-item label="隐私映射">
              {{
                currentAgent.executionPolicy.privacyMappingEnabled === null
                  ? '继承助手'
                  : currentAgent.executionPolicy.privacyMappingEnabled
                    ? '强制开启'
                    : '强制关闭'
              }}
            </a-descriptions-item>
            <a-descriptions-item label="知识能力">
              {{ currentAgent.executionPolicy.knowledgeEnabled ? '已启用' : '未启用' }}
            </a-descriptions-item>
            <a-descriptions-item label="知识库">
              {{
                currentAgent.executionPolicy.knowledgeEnabled
                  ? currentAgent.executionPolicy.inheritAssistantKnowledge
                    ? '继承助手'
                    : knowledgeBaseName(currentAgent.executionPolicy.knowledgeBaseId)
                  : '未启用'
              }}
            </a-descriptions-item>
            <a-descriptions-item label="记忆窗口">
              {{ currentAgent.executionPolicy.memoryWindowSize }}
            </a-descriptions-item>
            <a-descriptions-item label="挂载 Skill">
              {{ currentAgent.executionPolicy.skillResourceIds.length }}
            </a-descriptions-item>
            <a-descriptions-item label="挂载 Tool">
              {{ currentAgent.executionPolicy.toolResourceIds.length }}
            </a-descriptions-item>
            <a-descriptions-item label="可启动 Playbook">
              {{ playbookNames(currentAgent.playbookIds) }}
            </a-descriptions-item>
            <a-descriptions-item label="System Prompt" :span="2">
              {{ currentAgent.executionPolicy.systemPrompt || '未配置' }}
            </a-descriptions-item>
          </a-descriptions>
        </a-card>

        <a-card size="small" title="Tool 发布冻结">
          <a-space direction="vertical" style="width: 100%" size="middle">
            <a-alert
              type="info"
              show-icon
              message="Tool 不再单独固定版本"
              description="智能体只声明可用 Tool。助手发布时会统一冻结当前生效版本，与模型、Skill 和知识绑定一起进入运行时快照。"
            />
            <a-card v-for="resource in toolResources" :key="resource.id" size="small">
              <a-space direction="vertical" style="width: 100%">
                <a-space style="justify-content: space-between; width: 100%">
                  <a-tag :color="currentAgent.executionPolicy.toolResourceIds.includes(resource.id) ? 'blue' : 'default'">
                    {{ currentAgent.executionPolicy.toolResourceIds.includes(resource.id) ? '已启用' : '未启用' }}
                  </a-tag>
                  <a-space>
                    <a-tag class="console-accent-tag">最新 {{ resource.latestVersion?.version ?? '-' }}</a-tag>
                    <a-tag color="green">生效 {{ resource.effectiveVersion?.version ?? '-' }}</a-tag>
                  </a-space>
                </a-space>
                <a-typography-text type="secondary">{{ resource.summary }}</a-typography-text>
                <a-typography-text type="secondary">操作定义：{{ toolOperationSummary(resource) }}</a-typography-text>
              </a-space>
            </a-card>
          </a-space>
        </a-card>

        <a-alert
          v-if="currentReleaseAgent?.knowledgeBinding"
          type="info"
          show-icon
          :message="`当前发布冻结知识：${currentReleaseAgent.knowledgeBinding.knowledgeBaseName}`"
          :description="`版本 ${currentReleaseAgent.knowledgeBinding.knowledgeReleaseVersion} · 快照 ${currentReleaseAgent.knowledgeBinding.snapshotId}`"
        />
        <a-alert
          v-if="currentReleaseAgent?.effectivePrivacyMappingEnabled"
          type="info"
          show-icon
          :message="`当前发布冻结隐私映射：${currentReleaseAgent.effectivePrivacyModelBinding?.resourceName ?? '未命名私有模型'}`"
          :description="`版本 ${currentReleaseAgent.effectivePrivacyModelBinding?.resourceVersion ?? '-'}`"
        />

        <ObjectReferencePanel
          object-type="AGENT"
          :object-id="currentAgent.id"
          :reload-key="catalogRevision"
        />

        <ObjectHistoryPanel
          aggregate-type="AGENT"
          :object-id="currentAgent.id"
          :reload-key="catalogRevision"
        />
      </div>

      <a-empty v-else description="当前助手下暂无智能体" />
    </a-col>
  </a-row>

  <CatalogFormDrawer
    :open="createDrawerOpen"
    title="新建智能体"
    :width="760"
    kicker="02.02 / 助手构建 / 智能体"
    @close="createDrawerOpen = false"
  >
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
      <a-form-item label="职责说明" name="responsibility">
        <a-textarea v-model:value="createForm.responsibility" :rows="4" />
      </a-form-item>
      <a-form-item label="System Prompt">
        <a-textarea v-model:value="createForm.executionPolicy.systemPrompt" :rows="4" />
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
          <a-form-item label="隐私映射策略">
            <a-select v-model:value="createPrivacyMode" :options="privacyModeOptions" />
          </a-form-item>
        </a-col>
      </a-row>
      <a-row :gutter="[16, 16]">
        <a-col :span="12">
          <a-form-item label="私有映射模型">
            <a-select
              v-model:value="createForm.executionPolicy.privacyModelResourceId"
              allow-clear
              :disabled="createForm.executionPolicy.privacyMappingEnabled !== true"
              :options="privateModelResources.map((item) => ({ label: item.name, value: item.id }))"
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
      <a-form-item label="可用工具集">
        <a-select
          v-model:value="createForm.executionPolicy.toolResourceIds"
          mode="multiple"
          :options="toolResources.map((item) => ({ label: item.name, value: item.id }))"
        />
      </a-form-item>
      <a-row :gutter="[16, 16]">
        <a-col :span="12">
          <a-form-item label="允许动作">
            <a-select v-model:value="createForm.allowedActions" mode="multiple" :options="actionOptions" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="可切换 Owner">
            <a-select v-model:value="createForm.switchableOwnerAgentIds" mode="multiple" :options="availableOwnerOptions" />
          </a-form-item>
        </a-col>
      </a-row>
      <a-form-item label="可启动 Playbook">
        <a-select v-model:value="createForm.playbookIds" mode="multiple" :options="availablePlaybookOptions" />
      </a-form-item>
      <div class="create-drawer__actions">
        <a-button @click="createDrawerOpen = false">取消</a-button>
        <a-button type="primary" html-type="submit">创建智能体</a-button>
      </div>
    </a-form>
  </CatalogFormDrawer>

  <CatalogFormDrawer
    :open="editDrawerOpen"
    title="编辑智能体"
    :width="760"
    kicker="02.02 / 助手构建 / 智能体"
    @close="editDrawerOpen = false"
  >
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

      <a-form-item label="职责说明" name="responsibility">
        <a-textarea v-model:value="editForm.responsibility" :rows="5" />
      </a-form-item>
      <a-form-item label="System Prompt">
        <a-textarea v-model:value="editForm.executionPolicy.systemPrompt" :rows="5" />
      </a-form-item>

      <a-row :gutter="[16, 16]">
        <a-col :span="8">
          <a-form-item label="继承助手默认模型">
            <a-switch v-model:checked="editForm.executionPolicy.inheritAssistantDefaults" />
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item label="启用知识能力">
            <a-switch v-model:checked="editForm.executionPolicy.knowledgeEnabled" />
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
          <a-form-item label="隐私映射策略">
            <a-select v-model:value="editPrivacyMode" :options="privacyModeOptions" />
          </a-form-item>
        </a-col>
      </a-row>

      <a-row :gutter="[16, 16]">
        <a-col :span="12">
          <a-form-item label="私有映射模型">
            <a-select
              v-model:value="editForm.executionPolicy.privacyModelResourceId"
              allow-clear
              :disabled="editForm.executionPolicy.privacyMappingEnabled !== true"
              :options="privateModelResources.map((item) => ({ label: item.name, value: item.id }))"
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
        <a-col :span="8">
          <a-form-item label="继承助手知识库">
            <a-switch
              v-model:checked="editForm.executionPolicy.inheritAssistantKnowledge"
              :disabled="!editForm.executionPolicy.knowledgeEnabled"
            />
          </a-form-item>
        </a-col>
        <a-col :span="16">
          <a-form-item label="知识库覆盖">
            <a-select
              v-model:value="editForm.executionPolicy.knowledgeBaseId"
              allow-clear
              :disabled="!editForm.executionPolicy.knowledgeEnabled || editForm.executionPolicy.inheritAssistantKnowledge"
              :options="knowledgeBaseOptions"
            />
          </a-form-item>
        </a-col>
      </a-row>

      <a-form-item label="可用工具集">
        <a-select
          v-model:value="editForm.executionPolicy.toolResourceIds"
          mode="multiple"
          :options="toolResources.map((item) => ({ label: item.name, value: item.id }))"
        />
      </a-form-item>
      <a-row :gutter="[16, 16]">
        <a-col :span="12">
          <a-form-item label="允许动作">
            <a-select v-model:value="editForm.allowedActions" mode="multiple" :options="actionOptions" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="可切换 Owner">
            <a-select v-model:value="editForm.switchableOwnerAgentIds" mode="multiple" :options="availableOwnerOptions" />
          </a-form-item>
        </a-col>
      </a-row>
      <a-form-item label="可启动 Playbook">
        <a-select v-model:value="editForm.playbookIds" mode="multiple" :options="availablePlaybookOptions" />
      </a-form-item>

      <div class="create-drawer__actions">
        <a-button @click="editDrawerOpen = false">取消</a-button>
        <a-button type="primary" html-type="submit">保存智能体</a-button>
      </div>
    </a-form>
  </CatalogFormDrawer>
</template>
