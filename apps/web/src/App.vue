<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { message } from 'ant-design-vue';
import type {
  CatalogSummary,
  ConversationSession,
  CreateAssistantPayload,
  CreateAgentPayload,
  CreateDomainPayload,
  CreateKnowledgeBasePayload,
  CreateResourcePayload,
  CreateResourceVersionPayload,
  CreateScenarioPayload,
  Role,
  TaskInstance,
  UpdateResourcePayload,
  UpdateResourceVersionPayload,
  UpdateAssistantPayload,
  UpdateAgentPayload,
  UpdateDomainPayload,
  UpdateOrchestrationPayload,
  UpdateScenarioPayload,
  UserSession,
  WorkflowInstance,
} from './types';
import { api } from './services/api';
import DomainPage from './pages/DomainPage.vue';
import ScenarioPage from './pages/ScenarioPage.vue';
import AssistantPage from './pages/AssistantPage.vue';
import AgentPage from './pages/AgentPage.vue';
import OrchestrationPage from './pages/OrchestrationPage.vue';
import KnowledgeLibraryPage from './pages/KnowledgeLibraryPage.vue';
import KnowledgeCreatePage from './pages/KnowledgeCreatePage.vue';
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
  | 'knowledge-library'
  | 'knowledge-create'
  | 'resource-library'
  | 'resource-create'
  | 'runtime'
  | 'workflow';

type SectionKey = 'design' | 'build' | 'knowledge' | 'resource' | 'runtime-observe';

const sectionMeta: Record<SectionKey, { label: string; description: string }> = {
  design: {
    label: '平台设计',
    description: '沉淀业务域和场景边界，定义平台承载的业务上下文。',
  },
  build: {
    label: '助手构建',
    description: '围绕助手、智能体和编排，完成核心协作链路配置。',
  },
  knowledge: {
    label: '知识库',
    description: '管理知识库目录、内容导入、索引快照和发布版本。',
  },
  resource: {
    label: '能力资源',
    description: '管理 Tool / LLM / Skill 等可复用能力资源。',
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
    subtitle: '配置智能体职责、继承覆盖和可用 Tool 集。',
    section: 'build',
  },
  orchestration: {
    label: '编排设计',
    title: '智能体编排页',
    subtitle: '以图形化方式组织助手内部协作主链和分支流转。',
    section: 'build',
  },
  'knowledge-library': {
    label: '知识库目录',
    title: '知识库工作台',
    subtitle: '集中处理内容导入、文档、索引快照、发布版本与引用分析。',
    section: 'knowledge',
  },
  'knowledge-create': {
    label: '知识库新建',
    title: '知识库创建页',
    subtitle: '创建知识库治理对象，后续再进入工作台维护内容与发布。',
    section: 'knowledge',
  },
  'resource-library': {
    label: '资源目录',
    title: '资源目录页',
    subtitle: '查看 Tool / LLM / Skill 的版本流转、生效状态和引用分析。',
    section: 'resource',
  },
  'resource-create': {
    label: '资源新建',
    title: '资源新建页',
    subtitle: '按资源类型维护结构化配置，创建可版本化的能力资源。',
    section: 'resource',
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
    key: 'knowledge',
    label: sectionMeta.knowledge.label,
    children: [
      { key: 'knowledge-library', label: pageMeta['knowledge-library'].label },
      { key: 'knowledge-create', label: pageMeta['knowledge-create'].label },
    ],
  },
  {
    key: 'resource',
    label: sectionMeta.resource.label,
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
const creatingSession = ref(false);
const sendingSessionId = ref<string | null>(null);
const runtimePreferredSessionId = ref<string | null>(null);
const runtimeSelectedSessionId = ref<string | null>(null);
const selectedWorkflowId = ref<string | null>(null);
const knowledgeLibraryPreferredKnowledgeBaseId = ref<string | null>(null);
const resourceLibraryPreferredResourceId = ref<string | null>(null);
const resourceLibraryPreferredVersionId = ref<string | null>(null);
const activeKey = ref<PageKey>('domain');
const openKeys = ref<SectionKey[]>(['design', 'build', 'knowledge', 'resource', 'runtime-observe']);
const session = ref<UserSession | null>(null);
const catalog = ref<CatalogSummary | null>(null);
const conversationSessions = ref<ConversationSession[]>([]);
const tasks = ref<TaskInstance[]>([]);
const workflows = ref<WorkflowInstance[]>([]);
const workflowPollingHandle = ref<number | null>(null);
let workflowRefreshInFlight = false;

const selectedKeys = computed(() => [activeKey.value]);
const currentPageMeta = computed(() => pageMeta[activeKey.value]);
const currentSectionMeta = computed(() => sectionMeta[currentPageMeta.value.section]);
const roleOptions = computed(() =>
  (session.value?.availableRoles ?? []).map((role) => ({ label: role, value: role })),
);
function preferredWorkflowId(workflowList: WorkflowInstance[]) {
  return workflowList.find((item) => item.status === 'WAITING_HUMAN')?.id ?? workflowList[0]?.id ?? null;
}

const currentWorkflow = computed(
  () => workflows.value.find((item) => item.id === selectedWorkflowId.value)
    ?? workflows.value.find((item) => item.status === 'WAITING_HUMAN')
    ?? workflows.value[0],
);

function errorMessage(error: unknown, fallback: string) {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
}

function findSessionById(sessionId: string) {
  return conversationSessions.value.find((item) => item.id === sessionId);
}

function findWorkflowById(workflowId: string | null | undefined) {
  return workflows.value.find((item) => item.id === workflowId);
}

async function refresh(showLoading = false) {
  if (!showLoading && workflowRefreshInFlight) {
    return;
  }
  workflowRefreshInFlight = true;
  if (showLoading) {
    loading.value = true;
  }
  try {
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
    if (!workflowData.length) {
      selectedWorkflowId.value = null;
    } else if (!selectedWorkflowId.value || !workflowData.some((item) => item.id === selectedWorkflowId.value)) {
      selectedWorkflowId.value = preferredWorkflowId(workflowData);
    }
    if (!sessionList.length) {
      runtimeSelectedSessionId.value = null;
    } else if (runtimePreferredSessionId.value && sessionList.some((item) => item.id === runtimePreferredSessionId.value)) {
      runtimeSelectedSessionId.value = runtimePreferredSessionId.value;
    } else if (!runtimeSelectedSessionId.value || !sessionList.some((item) => item.id === runtimeSelectedSessionId.value)) {
      runtimeSelectedSessionId.value = sessionList[0].id;
    }
  } finally {
    workflowRefreshInFlight = false;
    if (showLoading) {
      loading.value = false;
    }
  }
}

function stopWorkflowPolling() {
  if (workflowPollingHandle.value === null) {
    return;
  }
  window.clearInterval(workflowPollingHandle.value);
  workflowPollingHandle.value = null;
}

function startWorkflowPolling() {
  if (workflowPollingHandle.value !== null) {
    return;
  }
  workflowPollingHandle.value = window.setInterval(() => {
    void refresh();
  }, 3000);
}

async function handleCreateSession(payload: { scenarioId: string; assistantId: string; requester: string; openingMessage: string }) {
  creatingSession.value = true;
  try {
    const openingMessage = payload.openingMessage.trim();
    const created = await api.createConversationSession({
      ...payload,
      openingMessage: '',
    });
    runtimePreferredSessionId.value = created.id;
    runtimeSelectedSessionId.value = created.id;
    if (openingMessage) {
      try {
        await api.sendConversationMessage(created.id, {
          requester: payload.requester,
          message: openingMessage,
        });
      } catch (error) {
        await refresh();
        const recoveredSession = findSessionById(created.id);
        const recoveredWorkflow = findWorkflowById(recoveredSession?.latestWorkflowInstanceId);
        if (recoveredWorkflow) {
          selectedWorkflowId.value = recoveredWorkflow.id;
          activeKey.value = recoveredWorkflow.status === 'WAITING_HUMAN' ? 'workflow' : 'runtime';
          void message.warning(
            recoveredWorkflow.status === 'WAITING_HUMAN'
              ? '开场消息请求已超时，但 workflow 已进入人工等待，可在流程观测页继续恢复。'
              : '开场消息请求已超时，但 workflow 已经启动，可继续在运行页观察结果。',
          );
          return;
        }
        throw error;
      }
    }
    await refresh();
    activeKey.value = 'runtime';
    void message.success('会话已创建');
  } catch (error) {
    void message.error(errorMessage(error, '创建会话失败'));
  } finally {
    creatingSession.value = false;
  }
}

async function handleSendMessage(payload: { sessionId: string; requester: string; message: string }) {
  sendingSessionId.value = payload.sessionId;
  runtimePreferredSessionId.value = payload.sessionId;
  runtimeSelectedSessionId.value = payload.sessionId;
  const previousWorkflowId = findSessionById(payload.sessionId)?.latestWorkflowInstanceId ?? null;
  try {
    await api.sendConversationMessage(payload.sessionId, {
      requester: payload.requester,
      message: payload.message,
    });
    await refresh();
  } catch (error) {
    await refresh();
    const recoveredSession = findSessionById(payload.sessionId);
    const recoveredWorkflowId = recoveredSession?.latestWorkflowInstanceId ?? null;
    const recoveredWorkflow = findWorkflowById(recoveredWorkflowId);
    if (recoveredWorkflowId && recoveredWorkflowId !== previousWorkflowId && recoveredWorkflow) {
      selectedWorkflowId.value = recoveredWorkflow.id;
      activeKey.value = recoveredWorkflow.status === 'WAITING_HUMAN' ? 'workflow' : 'runtime';
      void message.warning(
        recoveredWorkflow.status === 'WAITING_HUMAN'
          ? '请求超时，但 workflow 已进入人工等待，可直接在流程观测页提交人工动作恢复。'
          : '请求超时，但 workflow 已经启动，页面会继续自动刷新结果。',
      );
    } else {
      void message.error(errorMessage(error, '发送消息失败'));
    }
  } finally {
    sendingSessionId.value = null;
  }
}

function handleSelectRuntimeSession(sessionId: string) {
  runtimeSelectedSessionId.value = sessionId;
}

function handleSelectWorkflow(workflowId: string) {
  selectedWorkflowId.value = workflowId;
}

async function handleHumanAction(payload: { workflowId: string; action: string; comment: string; operatorId: string; attributes: Record<string, string> }) {
  await api.completeHumanAction(payload.workflowId, {
    action: payload.action,
    comment: payload.comment,
    operatorId: payload.operatorId,
    attributes: payload.attributes,
  });
  await refresh();
}

async function handleRoleChange(role: Role) {
  session.value = await api.switchRole(role);
}

async function handleCreateAssistant(payload: CreateAssistantPayload) {
  try {
    await api.createAssistant(payload);
    await refresh();
    void message.success('助手已创建');
  } catch (error) {
    void message.error(errorMessage(error, '创建助手失败'));
  }
}

async function handleCreateDomain(payload: CreateDomainPayload) {
  try {
    await api.createDomain(payload);
    await refresh();
    void message.success('业务域已创建');
  } catch (error) {
    void message.error(errorMessage(error, '创建业务域失败'));
  }
}

async function handleUpdateDomain(payload: { domainId: string; data: UpdateDomainPayload }) {
  try {
    await api.updateDomain(payload.domainId, payload.data);
    await refresh();
    void message.success('业务域已更新');
  } catch (error) {
    void message.error(errorMessage(error, '更新业务域失败'));
  }
}

async function handleDeleteDomain(domainId: string) {
  try {
    await api.deleteDomain(domainId);
    await refresh();
    void message.success('业务域已删除');
  } catch (error) {
    void message.error(errorMessage(error, '删除业务域失败'));
  }
}

async function handleCreateScenario(payload: CreateScenarioPayload) {
  try {
    await api.createScenario(payload);
    await refresh();
    void message.success('业务场景已创建');
  } catch (error) {
    void message.error(errorMessage(error, '创建业务场景失败'));
  }
}

async function handleUpdateScenario(payload: { scenarioId: string; data: UpdateScenarioPayload }) {
  try {
    await api.updateScenario(payload.scenarioId, payload.data);
    await refresh();
    void message.success('业务场景已更新');
  } catch (error) {
    void message.error(errorMessage(error, '更新业务场景失败'));
  }
}

async function handleDeleteScenario(scenarioId: string) {
  try {
    await api.deleteScenario(scenarioId);
    await refresh();
    void message.success('业务场景已删除');
  } catch (error) {
    void message.error(errorMessage(error, '删除业务场景失败'));
  }
}

async function handleUpdateAssistant(payload: { assistantId: string; data: UpdateAssistantPayload }) {
  try {
    await api.updateAssistant(payload.assistantId, payload.data);
    await refresh();
    void message.success('助手已更新');
  } catch (error) {
    void message.error(errorMessage(error, '更新助手失败'));
  }
}

async function handleDeleteAssistant(assistantId: string) {
  try {
    await api.deleteAssistant(assistantId);
    await refresh();
    void message.success('助手已删除');
  } catch (error) {
    void message.error(errorMessage(error, '删除助手失败'));
  }
}

async function handleCreateAgent(payload: CreateAgentPayload) {
  try {
    await api.createAgent(payload);
    await refresh();
    void message.success('智能体已创建');
  } catch (error) {
    void message.error(errorMessage(error, '创建智能体失败'));
  }
}

async function handleDeleteAgent(agentId: string) {
  try {
    await api.deleteAgent(agentId);
    await refresh();
    void message.success('智能体已删除');
  } catch (error) {
    void message.error(errorMessage(error, '删除智能体失败'));
  }
}

async function handleSaveAgent(payload: {
  agentId: string;
  agent: UpdateAgentPayload;
}) {
  try {
    await api.updateAgent(payload.agentId, payload.agent);
    await refresh();
    void message.success('智能体配置已保存');
  } catch (error) {
    void message.error(errorMessage(error, '保存智能体失败'));
  }
}

async function handleSaveOrchestration(payload: { assistantId: string; data: UpdateOrchestrationPayload }) {
  try {
    await api.saveOrchestration(payload.assistantId, payload.data);
    await refresh();
    void message.success('编排设计已保存');
  } catch (error) {
    void message.error(errorMessage(error, '保存编排失败'));
  }
}

async function handleCreateKnowledgeBase(payload: CreateKnowledgeBasePayload) {
  try {
    const created = await api.createKnowledgeBase(payload);
    await refresh();
    knowledgeLibraryPreferredKnowledgeBaseId.value = created.id;
    activeKey.value = 'knowledge-library';
    void message.success('知识库已创建');
  } catch (error) {
    void message.error(errorMessage(error, '创建知识库失败'));
  }
}

async function handleCreateResource(payload: CreateResourcePayload) {
  const created = await api.createResource(payload);
  await refresh();
  resourceLibraryPreferredResourceId.value = created.id;
  resourceLibraryPreferredVersionId.value = created.latestVersion?.id ?? null;
  activeKey.value = 'resource-library';
  void message.success('资源已创建');
}

async function handleDeleteResource(resourceId: string) {
  try {
    await api.deleteResource(resourceId);
    await refresh();
    void message.success('资源已删除');
  } catch (error) {
    void message.error(errorMessage(error, '删除资源失败'));
  }
}

async function handleUpdateResource(payload: { resourceId: string; resource: UpdateResourcePayload }) {
  try {
    await api.updateResource(payload.resourceId, payload.resource);
    await refresh();
    resourceLibraryPreferredResourceId.value = payload.resourceId;
    void message.success('资源信息已保存');
  } catch (error) {
    void message.error(errorMessage(error, '保存资源信息失败'));
  }
}

async function handleCreateResourceVersion(payload: { resourceId: string; data: CreateResourceVersionPayload }) {
  const created = await api.createResourceVersion(payload.resourceId, payload.data);
  await refresh();
  resourceLibraryPreferredResourceId.value = payload.resourceId;
  resourceLibraryPreferredVersionId.value = created.id;
  void message.success('资源版本已创建');
}

async function handleUpdateResourceVersion(payload: {
  resourceId: string;
  versionId: string;
  version: UpdateResourceVersionPayload;
}) {
  try {
    await api.updateResourceVersion(payload.resourceId, payload.versionId, payload.version);
    await refresh();
    resourceLibraryPreferredResourceId.value = payload.resourceId;
    resourceLibraryPreferredVersionId.value = payload.versionId;
    void message.success(payload.version.status === 'PUBLISHED' ? '草稿版本已保存并发布' : '草稿版本已保存');
  } catch (error) {
    void message.error(errorMessage(error, '保存资源版本失败'));
  }
}

async function handleDeleteResourceVersion(payload: { resourceId: string; versionId: string }) {
  try {
    await api.deleteResourceVersion(payload.resourceId, payload.versionId);
    await refresh();
    void message.success('资源版本已删除');
  } catch (error) {
    void message.error(errorMessage(error, '删除资源版本失败'));
  }
}

async function handlePublishResourceVersion(payload: { resourceId: string; versionId: string }) {
  try {
    await api.publishResourceVersion(payload.resourceId, payload.versionId);
    await refresh();
    void message.success('资源版本已发布');
  } catch (error) {
    void message.error(errorMessage(error, '发布资源版本失败'));
  }
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

watch(
  () => workflows.value.some((item) => item.status === 'RUNNING'),
  (hasRunningWorkflow) => {
    if (hasRunningWorkflow) {
      startWorkflowPolling();
      return;
    }
    stopWorkflowPolling();
  },
  { immediate: true },
);

onMounted(() => {
  void refresh(true);
});

onUnmounted(() => {
  stopWorkflowPolling();
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
            <a-typography-title :level="4" class="app-header__title">企业级智能体中台</a-typography-title>
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
            @create-domain="handleCreateDomain"
            @update-domain="handleUpdateDomain"
            @delete-domain="handleDeleteDomain"
          />
          <ScenarioPage
            v-else-if="activeKey === 'scenario'"
            :domains="catalog.domains"
            :scenarios="catalog.scenarios"
            @create-scenario="handleCreateScenario"
            @update-scenario="handleUpdateScenario"
            @delete-scenario="handleDeleteScenario"
          />
          <AssistantPage
            v-else-if="activeKey === 'assistant'"
            :assistants="catalog.assistants"
            :scenarios="catalog.scenarios"
            :resources="catalog.resources"
            :knowledge-bases="catalog.knowledgeBases"
            @create-assistant="handleCreateAssistant"
            @update-assistant="handleUpdateAssistant"
            @delete-assistant="handleDeleteAssistant"
          />
          <AgentPage
            v-else-if="activeKey === 'agent'"
            :assistants="catalog.assistants"
            :agents="catalog.agents"
            :resources="catalog.resources"
            :knowledge-bases="catalog.knowledgeBases"
            @create-agent="handleCreateAgent"
            @save-agent="handleSaveAgent"
            @delete-agent="handleDeleteAgent"
          />
          <OrchestrationPage
            v-else-if="activeKey === 'orchestration'"
            :assistants="catalog.assistants"
            :orchestrations="catalog.orchestrations"
            :resources="catalog.resources"
            @save-orchestration="handleSaveOrchestration"
          />
          <KnowledgeLibraryPage
            v-else-if="activeKey === 'knowledge-library'"
            :knowledge-bases="catalog.knowledgeBases"
            :preferred-knowledge-base-id="knowledgeLibraryPreferredKnowledgeBaseId"
            @refresh-catalog="refresh"
          />
          <KnowledgeCreatePage
            v-else-if="activeKey === 'knowledge-create'"
            :domains="catalog.domains"
            :assistants="catalog.assistants"
            @create-knowledge-base="handleCreateKnowledgeBase"
          />
          <ResourceLibraryPage
            v-else-if="activeKey === 'resource-library'"
            :domains="catalog.domains"
            :resource-center="catalog.resourceCenter"
            :resources="catalog.resources"
            :preferred-resource-id="resourceLibraryPreferredResourceId"
            :preferred-version-id="resourceLibraryPreferredVersionId"
            @delete-resource="handleDeleteResource"
            @update-resource="handleUpdateResource"
            @create-resource-version="handleCreateResourceVersion"
            @update-resource-version="handleUpdateResourceVersion"
            @delete-resource-version="handleDeleteResourceVersion"
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
            :creating-session="creatingSession"
            :sending-session-id="sendingSessionId"
            :preferred-session-id="runtimePreferredSessionId"
            :selected-session-id="runtimeSelectedSessionId"
            @select-session="handleSelectRuntimeSession"
            @create-session="handleCreateSession"
            @send-message="handleSendMessage"
          />
          <WorkflowPage
            v-else
            :workflow="currentWorkflow"
            :workflows="workflows"
            :selected-workflow-id="selectedWorkflowId"
            @select-workflow="handleSelectWorkflow"
            @human-action="handleHumanAction"
          />
        </a-layout-content>
      </a-layout>
    </a-layout>
  </a-app>
</template>
