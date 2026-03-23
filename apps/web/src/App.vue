<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { message } from 'ant-design-vue';
import type {
  CatalogSummary,
  ConversationSession,
  CreateAssistantPayload,
  CreateAgentPayload,
  CreateResourcePayload,
  CreateResourceVersionPayload,
  Role,
  TaskInstance,
  UpdateAgentBindingsPayload,
  UpdateAssistantPayload,
  UpdateAgentPayload,
  UpdateOrchestrationPayload,
  UserSession,
  WorkflowInstance,
} from './types';
import { api } from './services/api';
import DomainPage from './pages/DomainPage.vue';
import ScenarioPage from './pages/ScenarioPage.vue';
import AssistantPage from './pages/AssistantPage.vue';
import AgentPage from './pages/AgentPage.vue';
import OrchestrationPage from './pages/OrchestrationPage.vue';
import ResourceLibraryPage from './pages/ResourceLibraryPage.vue';
import ResourceCreatePage from './pages/ResourceCreatePage.vue';
import RuntimeConversationPage from './pages/RuntimeConversationPage.vue';
import WorkflowPage from './pages/WorkflowPage.vue';

type PageKey =
  | 'domain'
  | 'scenario'
  | 'assistant'
  | 'agent'
  | 'orchestration'
  | 'resource-library'
  | 'resource-create'
  | 'runtime'
  | 'workflow';

type SectionKey = 'design' | 'build' | 'asset' | 'runtime-observe';

const sectionMeta: Record<SectionKey, { label: string; description: string }> = {
  design: {
    label: '平台设计',
    description: '沉淀业务域和场景边界，定义平台承载的业务上下文。',
  },
  build: {
    label: '助手构建',
    description: '围绕助手、智能体和编排，完成核心协作链路配置。',
  },
  asset: {
    label: '资源与发布',
    description: '管理资源版本、绑定锚点和发布冻结快照。',
  },
  'runtime-observe': {
    label: '运行与观测',
    description: '查看运行会话、任务流程和人工介入状态。',
  },
};

const pageMeta: Record<PageKey, { label: string; title: string; subtitle: string; section: SectionKey }> = {
  domain: {
    label: '业务域',
    title: '业务域页',
    subtitle: '查看平台承载的业务域、域内资源和场景入口。',
    section: 'design',
  },
  scenario: {
    label: '业务场景',
    title: '业务场景页',
    subtitle: '定义业务目标、场景边界以及承载它的助手。',
    section: 'design',
  },
  assistant: {
    label: '助手配置',
    title: '助手配置页',
    subtitle: '管理助手基础信息、发布状态和冻结资源版本快照。',
    section: 'build',
  },
  agent: {
    label: '智能体',
    title: '智能体详情与资源绑定页',
    subtitle: '配置智能体职责，并把资源绑定到明确版本。',
    section: 'build',
  },
  orchestration: {
    label: '编排设计',
    title: '智能体编排页',
    subtitle: '以图形化方式组织助手内部协作主链和分支流转。',
    section: 'build',
  },
  'resource-library': {
    label: '资源目录',
    title: '资源目录页',
    subtitle: '查看现有资源、版本流转、生效状态和绑定影响。',
    section: 'asset',
  },
  'resource-create': {
    label: '资源新建',
    title: '资源新建页',
    subtitle: '按资源类型维护结构化配置，创建可版本化的知识库、Skill 和 MCP。',
    section: 'asset',
  },
  runtime: {
    label: '会话运行',
    title: '运行时对话页',
    subtitle: '选择一个助手发起持续对话，并观察其版本锚定运行链路。',
    section: 'runtime-observe',
  },
  workflow: {
    label: '流程观测',
    title: '流程实例详情页',
    subtitle: '查看流程状态、节点流转、资源锚点与人工介入。',
    section: 'runtime-observe',
  },
};

const menuItems = [
  {
    key: 'design',
    label: sectionMeta.design.label,
    children: [
      { key: 'domain', label: pageMeta.domain.label },
      { key: 'scenario', label: pageMeta.scenario.label },
    ],
  },
  {
    key: 'build',
    label: sectionMeta.build.label,
    children: [
      { key: 'assistant', label: pageMeta.assistant.label },
      { key: 'agent', label: pageMeta.agent.label },
      { key: 'orchestration', label: pageMeta.orchestration.label },
    ],
  },
  {
    key: 'asset',
    label: sectionMeta.asset.label,
    children: [
      { key: 'resource-library', label: pageMeta['resource-library'].label },
      { key: 'resource-create', label: pageMeta['resource-create'].label },
    ],
  },
  {
    key: 'runtime-observe',
    label: sectionMeta['runtime-observe'].label,
    children: [
      { key: 'runtime', label: pageMeta.runtime.label },
      { key: 'workflow', label: pageMeta.workflow.label },
    ],
  },
];

const loading = ref(true);
const activeKey = ref<PageKey>('domain');
const openKeys = ref<SectionKey[]>(['design', 'build', 'asset', 'runtime-observe']);
const session = ref<UserSession | null>(null);
const catalog = ref<CatalogSummary | null>(null);
const conversationSessions = ref<ConversationSession[]>([]);
const tasks = ref<TaskInstance[]>([]);
const workflows = ref<WorkflowInstance[]>([]);

const selectedKeys = computed(() => [activeKey.value]);
const currentPageMeta = computed(() => pageMeta[activeKey.value]);
const currentSectionMeta = computed(() => sectionMeta[currentPageMeta.value.section]);
const roleOptions = computed(() =>
  (session.value?.availableRoles ?? []).map((role) => ({ label: role, value: role })),
);
const currentWorkflow = computed(
  () => workflows.value.find((item) => item.status === 'WAITING_HUMAN') ?? workflows.value[0],
);

async function refresh() {
  loading.value = true;
  const [sessionData, catalogData, sessionList, tasksData, workflowData] = await Promise.all([
    api.getSession(),
    api.getCatalogSummary(),
    api.getConversationSessions(),
    api.getTasks(),
    api.getWorkflows(),
  ]);
  session.value = sessionData;
  catalog.value = catalogData;
  conversationSessions.value = sessionList;
  tasks.value = tasksData;
  workflows.value = workflowData;
  loading.value = false;
}

async function handleCreateSession(payload: { scenarioId: string; assistantId: string; requester: string; openingMessage: string }) {
  await api.createConversationSession(payload);
  await refresh();
  activeKey.value = 'runtime';
  void message.success('会话已创建');
}

async function handleSendMessage(payload: { sessionId: string; requester: string; message: string }) {
  await api.sendConversationMessage(payload.sessionId, {
    requester: payload.requester,
    message: payload.message,
  });
  await refresh();
}

async function handleHumanAction(payload: { workflowId: string; action: string; comment: string }) {
  await api.completeHumanAction(payload.workflowId, payload.action, payload.comment);
  await refresh();
}

async function handleRoleChange(role: Role) {
  session.value = await api.switchRole(role);
}

async function handleCreateAssistant(payload: CreateAssistantPayload) {
  await api.createAssistant(payload);
  await refresh();
  void message.success('助手已创建');
}

async function handleUpdateAssistant(payload: { assistantId: string; data: UpdateAssistantPayload }) {
  await api.updateAssistant(payload.assistantId, payload.data);
  await refresh();
  void message.success('助手已更新');
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

async function handleSaveOrchestration(payload: { assistantId: string; data: UpdateOrchestrationPayload }) {
  await api.saveOrchestration(payload.assistantId, payload.data);
  await refresh();
  void message.success('编排设计已保存');
}

async function handleCreateResource(payload: CreateResourcePayload) {
  await api.createResource(payload);
  await refresh();
  activeKey.value = 'resource-library';
  void message.success('资源已创建');
}

async function handleCreateResourceVersion(payload: { resourceId: string; data: CreateResourceVersionPayload }) {
  await api.createResourceVersion(payload.resourceId, payload.data);
  await refresh();
  void message.success('资源版本已创建');
}

async function handlePublishResourceVersion(payload: { resourceId: string; versionId: string }) {
  await api.publishResourceVersion(payload.resourceId, payload.versionId);
  await refresh();
  void message.success('资源版本已发布');
}

function handleMenuClick(info: { key: string | number }) {
  activeKey.value = String(info.key) as PageKey;
}

function handleOpenChange(keys: string[]) {
  openKeys.value = keys as SectionKey[];
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
          mode="inline"
          :selected-keys="selectedKeys"
          :open-keys="openKeys"
          :items="menuItems"
          @click="handleMenuClick"
          @openChange="handleOpenChange"
        />
      </a-layout-sider>

      <a-layout>
        <a-layout-header class="app-header">
          <div class="app-header__title-group">
            <a-typography-title :level="4" class="app-header__title">企业级智能体中台 MVP</a-typography-title>
            <a-typography-text class="app-header__eyebrow">
              {{ currentSectionMeta.label }} / {{ currentPageMeta.label }}
            </a-typography-text>
            <a-typography-text type="secondary" class="app-header__subtitle">
              {{ currentPageMeta.subtitle }}
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
          <AssistantPage
            v-else-if="activeKey === 'assistant'"
            :assistants="catalog.assistants"
            :scenarios="catalog.scenarios"
            :resources="catalog.resources"
            @create-assistant="handleCreateAssistant"
            @update-assistant="handleUpdateAssistant"
          />
          <AgentPage
            v-else-if="activeKey === 'agent'"
            :assistants="catalog.assistants"
            :agents="catalog.agents"
            :resources="catalog.resources"
            @create-agent="handleCreateAgent"
            @save-agent="handleSaveAgent"
          />
          <OrchestrationPage
            v-else-if="activeKey === 'orchestration'"
            :assistants="catalog.assistants"
            :orchestrations="catalog.orchestrations"
            :resources="catalog.resources"
            @save-orchestration="handleSaveOrchestration"
          />
          <ResourceLibraryPage
            v-else-if="activeKey === 'resource-library'"
            :resource-center="catalog.resourceCenter"
            :resources="catalog.resources"
            @create-resource-version="handleCreateResourceVersion"
            @publish-resource-version="handlePublishResourceVersion"
          />
          <ResourceCreatePage
            v-else-if="activeKey === 'resource-create'"
            :domains="catalog.domains"
            :assistants="catalog.assistants"
            :resource-blueprints="catalog.resourceBlueprints"
            @create-resource="handleCreateResource"
          />
          <RuntimeConversationPage
            v-else-if="activeKey === 'runtime'"
            :scenarios="catalog.scenarios"
            :assistants="catalog.assistants"
            :sessions="conversationSessions"
            :tasks="tasks"
            :workflows="workflows"
            @create-session="handleCreateSession"
            @send-message="handleSendMessage"
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
