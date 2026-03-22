<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { message } from 'ant-design-vue';
import type {
  CatalogSummary,
  CreateAgentGroupPayload,
  CreateAgentPayload,
  Role,
  TaskInstance,
  UpdateAgentBindingsPayload,
  UpdateAgentGroupPayload,
  UpdateAgentPayload,
  UpdateOrchestrationPayload,
  UserSession,
  WorkflowInstance,
} from './types';
import { api } from './services/api';
import DomainPage from './pages/DomainPage.vue';
import ScenarioPage from './pages/ScenarioPage.vue';
import AgentGroupPage from './pages/AgentGroupPage.vue';
import AgentPage from './pages/AgentPage.vue';
import OrchestrationPage from './pages/OrchestrationPage.vue';
import ResourceCenterPage from './pages/ResourceCenterPage.vue';
import TaskLaunchPage from './pages/TaskLaunchPage.vue';
import WorkflowPage from './pages/WorkflowPage.vue';

const menuItems = [
  { key: 'domain', label: '业务域页' },
  { key: 'scenario', label: '业务场景页' },
  { key: 'bot', label: '智能体组配置页' },
  { key: 'agent', label: '智能体页' },
  { key: 'orchestration', label: '智能体编排页' },
  { key: 'resource-center', label: '资源中心页' },
  { key: 'task', label: '任务发起页' },
  { key: 'workflow', label: '流程实例页' },
];

const loading = ref(true);
const activeKey = ref('domain');
const session = ref<UserSession | null>(null);
const catalog = ref<CatalogSummary | null>(null);
const tasks = ref<TaskInstance[]>([]);
const workflows = ref<WorkflowInstance[]>([]);

const selectedKeys = computed(() => [activeKey.value]);
const roleOptions = computed(() =>
  (session.value?.availableRoles ?? []).map((role) => ({ label: role, value: role })),
);
const currentWorkflow = computed(
  () => workflows.value.find((item) => item.status === 'WAITING_HUMAN') ?? workflows.value[0],
);

async function refresh() {
  loading.value = true;
  const [sessionData, catalogData, tasksData, workflowData] = await Promise.all([
    api.getSession(),
    api.getCatalogSummary(),
    api.getTasks(),
    api.getWorkflows(),
  ]);
  session.value = sessionData;
  catalog.value = catalogData;
  tasks.value = tasksData;
  workflows.value = workflowData;
  loading.value = false;
}

async function handleLaunch(payload: { scenarioId: string; question: string; requester: string }) {
  await api.launchTask(payload);
  await refresh();
  activeKey.value = 'workflow';
  void message.success('任务已提交');
}

async function handleHumanAction(payload: { workflowId: string; action: string; comment: string }) {
  await api.completeHumanAction(payload.workflowId, payload.action, payload.comment);
  await refresh();
}

async function handleRoleChange(role: Role) {
  session.value = await api.switchRole(role);
}

async function handleCreateAgentGroup(payload: CreateAgentGroupPayload) {
  await api.createAgentGroup(payload);
  await refresh();
  void message.success('智能体组已创建');
}

async function handleUpdateAgentGroup(payload: { agentGroupId: string; data: UpdateAgentGroupPayload }) {
  await api.updateAgentGroup(payload.agentGroupId, payload.data);
  await refresh();
  void message.success('智能体组已更新');
}

async function handleCreateAgent(payload: CreateAgentPayload) {
  await api.createAgent(payload);
  await refresh();
  void message.success('智能体已创建');
}

async function handleSaveAgent(payload: {
  agentId: string;
  agent: UpdateAgentPayload;
  bindings: UpdateAgentBindingsPayload;
}) {
  await api.updateAgent(payload.agentId, payload.agent);
  await api.updateAgentBindings(payload.agentId, payload.bindings);
  await refresh();
  void message.success('智能体配置已保存');
}

async function handleSaveOrchestration(payload: { agentGroupId: string; data: UpdateOrchestrationPayload }) {
  await api.saveOrchestration(payload.agentGroupId, payload.data);
  await refresh();
  void message.success('编排设计已保存');
}

function handleMenuClick(info: { key: string | number }) {
  activeKey.value = String(info.key);
}

function handleRoleSelect(value: string | number) {
  void handleRoleChange(value as Role);
}

onMounted(() => {
  void refresh();
});
</script>

<template>
  <div v-if="loading || !session || !catalog" class="loading-screen">
    <a-spin size="large" />
  </div>

  <a-app v-else>
    <a-layout style="min-height: 100vh">
      <a-layout-sider :width="240" theme="light" style="border-right: 1px solid #f0f0f0">
        <div class="brand-block">
          <a-typography-title :level="3">Lynxus</a-typography-title>
          <a-typography-text type="secondary">Orchestrate Enterprise Agents</a-typography-text>
        </div>
        <a-menu
          :selected-keys="selectedKeys"
          :items="menuItems"
          @click="handleMenuClick"
        />
      </a-layout-sider>

      <a-layout>
        <a-layout-header class="app-header">
          <div class="app-header__title-group">
            <a-typography-title :level="4" class="app-header__title">企业级智能体中台 MVP</a-typography-title>
            <a-typography-text type="secondary" class="app-header__subtitle">
              知识问答 + 升级处理
            </a-typography-text>
          </div>

          <a-select
            class="app-header__role-select"
            :value="session.currentRole"
            :options="roleOptions"
            @change="handleRoleSelect"
          />
        </a-layout-header>

        <a-layout-content class="app-content">
          <DomainPage
            v-if="activeKey === 'domain'"
            :domains="catalog.domains"
            :scenarios="catalog.scenarios"
          />
          <ScenarioPage v-else-if="activeKey === 'scenario'" :scenarios="catalog.scenarios" />
          <AgentGroupPage
            v-else-if="activeKey === 'bot'"
            :agent-groups="catalog.agentGroups"
            :scenarios="catalog.scenarios"
            @create-agent-group="handleCreateAgentGroup"
            @update-agent-group="handleUpdateAgentGroup"
          />
          <AgentPage
            v-else-if="activeKey === 'agent'"
            :agent-groups="catalog.agentGroups"
            :agents="catalog.agents"
            :resources="catalog.resources"
            @create-agent="handleCreateAgent"
            @save-agent="handleSaveAgent"
          />
          <OrchestrationPage
            v-else-if="activeKey === 'orchestration'"
            :agent-groups="catalog.agentGroups"
            :orchestrations="catalog.orchestrations"
            :resources="catalog.resources"
            @save-orchestration="handleSaveOrchestration"
          />
          <ResourceCenterPage
            v-else-if="activeKey === 'resource-center'"
            :resource-center="catalog.resourceCenter"
          />
          <TaskLaunchPage
            v-else-if="activeKey === 'task'"
            :scenarios="catalog.scenarios"
            :tasks="tasks"
            @launch="handleLaunch"
          />
          <WorkflowPage
            v-else
            :workflow="currentWorkflow"
            :workflows="workflows"
            @human-action="handleHumanAction"
          />
        </a-layout-content>
      </a-layout>
    </a-layout>
  </a-app>
</template>
