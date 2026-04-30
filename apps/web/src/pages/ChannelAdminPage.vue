<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { message } from 'ant-design-vue';
import PageHeadActions from '../components/PageHeadActions.vue';
import SchemaDrivenForm from '../components/SchemaDrivenForm.vue';
import { schemaDrivenUiSchemaWithFallback } from '../components/toolConnectorDefinitionForms';
import { validateSchemaDrivenForm } from '../components/schemaDrivenForm';
import {
  accountOptionsForChannelProvider,
  buildProviderJobWritePayload,
  buildTemplateBindingWritePayload,
  channelProviderOptions,
  createJobFormState,
  createProfileFormForDefinition,
  currentProfileAccountHardBlock,
  profileFormFromProfile,
} from './channelAdminPage';
import type {
  ChannelProviderJobFormState,
  ChannelProfileFormState,
  TemplateBindingFormState,
} from './channelAdminPage';
import { api } from '../services/api';
import type {
  Assistant,
  ChannelConversationBinding,
  ChannelInboundEvent,
  ChannelOutboundDelivery,
  ChannelProfile,
  ChannelProviderDefinition,
  ChannelProviderJobConfig,
  ChannelProviderJobRun,
  ChannelTemplateBinding,
  CreateChannelProfilePayload,
  IntegrationAccount,
  Scenario,
  UpdateChannelProfilePayload,
} from '../types';
import type {
  JsonObject,
  SchemaDrivenFormUiField,
  SchemaDrivenFormValidationResult,
} from '../components/schemaDrivenForm';

interface SchemaDrivenFormExpose {
  validate: () => SchemaDrivenFormValidationResult;
}

type JobDefinition = ChannelProviderDefinition['jobDefinitions'][number];

const props = withDefaults(defineProps<{
  assistants?: Assistant[];
  scenarios?: Scenario[];
}>(), {
  assistants: () => [],
  scenarios: () => [],
});

const definitions = ref<ChannelProviderDefinition[]>([]);
const profiles = ref<ChannelProfile[]>([]);
const accounts = ref<IntegrationAccount[]>([]);
const conversationBindings = ref<ChannelConversationBinding[]>([]);
const inboundEvents = ref<ChannelInboundEvent[]>([]);
const outboundDeliveries = ref<ChannelOutboundDelivery[]>([]);
const templateBindings = ref<ChannelTemplateBinding[]>([]);
const providerJobs = ref<ChannelProviderJobConfig[]>([]);
const providerJobRuns = reactive<Record<string, ChannelProviderJobRun[]>>({});
const selectedProfileId = ref('');
const loading = ref(false);
const detailLoading = ref(false);
const savingProfile = ref(false);
const deletingProfile = ref(false);
const savingTemplate = ref(false);
const profileDrawerOpen = ref(false);
const templateDrawerOpen = ref(false);
const editingProfileId = ref<string | null>(null);
const editingTemplateBinding = ref<ChannelTemplateBinding | null>(null);
const activeTab = ref('profile');
const profileConfigFormRef = ref<SchemaDrivenFormExpose | null>(null);

const profileForm = reactive<ChannelProfileFormState>({
  providerType: '',
  displayName: '',
  status: 'INACTIVE',
  inboundEnabled: false,
  assistantId: null,
  scenarioId: null,
  integrationAccountId: null,
  config: {},
});
const jobForms = reactive<Record<string, ChannelProviderJobFormState>>({});
const jobSaving = reactive<Record<string, boolean>>({});
const jobRunning = reactive<Record<string, boolean>>({});
const templateKeyForm = reactive({
  assistantId: '',
  messageType: 'CARD',
  messageSubtype: '',
  messageVersion: 'v1',
});
const templateForm = reactive<TemplateBindingFormState>({
  externalTemplateId: '',
  externalTemplateVersion: null,
  variableSchemaJson: '{}',
  displayName: '',
  externalEditUrl: null,
  enabled: true,
  expectedRevision: null,
});

const profileColumns = [
  { title: '名称', dataIndex: 'displayName', key: 'displayName' },
  { title: 'Provider', dataIndex: 'providerType', key: 'providerType' },
  { title: '状态', dataIndex: 'status', key: 'status' },
  { title: 'Inbound', dataIndex: 'inboundEnabled', key: 'inboundEnabled' },
];
const templateColumns = [
  { title: '名称', dataIndex: 'displayName', key: 'displayName' },
  { title: 'Assistant', dataIndex: 'assistantId', key: 'assistantId' },
  { title: '消息', key: 'message' },
  { title: '外部模板', dataIndex: 'externalTemplateId', key: 'externalTemplateId' },
  { title: '状态', dataIndex: 'enabled', key: 'enabled' },
  { title: '版本', dataIndex: 'revision', key: 'revision' },
  { title: '操作', key: 'actions' },
];
const bindingColumns = [
  { title: '外部会话', dataIndex: 'externalConversationId', key: 'externalConversationId' },
  { title: '外部用户', dataIndex: 'externalUserId', key: 'externalUserId' },
  { title: 'Assistant', dataIndex: 'assistantId', key: 'assistantId' },
  { title: 'Session', dataIndex: 'sessionId', key: 'sessionId' },
  { title: '状态', dataIndex: 'status', key: 'status' },
  { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt' },
];
const inboundColumns = [
  { title: '事件', dataIndex: 'eventType', key: 'eventType' },
  { title: '状态', dataIndex: 'status', key: 'status' },
  { title: 'Dedup', dataIndex: 'dedupKey', key: 'dedupKey' },
  { title: '外部会话', dataIndex: 'externalConversationId', key: 'externalConversationId' },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt' },
];
const outboundColumns = [
  { title: '外部会话', dataIndex: 'externalConversationId', key: 'externalConversationId' },
  { title: '状态', dataIndex: 'status', key: 'status' },
  { title: '尝试', dataIndex: 'attemptCount', key: 'attemptCount' },
  { title: '错误', dataIndex: 'lastError', key: 'lastError' },
  { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt' },
];

const statusOptions = [
  { label: 'ACTIVE', value: 'ACTIVE' },
  { label: 'INACTIVE', value: 'INACTIVE' },
];
const scheduleTypeOptions = [
  { label: 'INTERVAL', value: 'INTERVAL' },
  { label: 'CRON', value: 'CRON' },
  { label: 'MANUAL', value: 'MANUAL' },
];
const booleanOptions = [
  { label: '启用', value: true },
  { label: '停用', value: false },
];

const selectedProfile = computed(() => (
  profiles.value.find((profile) => profile.id === selectedProfileId.value) ?? profiles.value[0] ?? null
));
const selectedDefinition = computed(() => (
  definitions.value.find((definition) => definition.providerType === selectedProfile.value?.providerType) ?? null
));
const profileFormDefinition = computed(() => (
  definitions.value.find((definition) => definition.providerType === profileForm.providerType) ?? null
));
const providerOptions = computed(() => channelProviderOptions(definitions.value));
const accountOptions = computed(() => accountOptionsForChannelProvider(accounts.value, profileForm.providerType));
const selectedProfileHardBlock = computed(() => (
  editingProfileId.value
    ? currentProfileAccountHardBlock(selectedProfile.value, profileForm.integrationAccountId)
    : null
));
const accountRiskSummary = computed(() => selectedProfile.value?.integrationAccount?.risks ?? []);
const assistantOptions = computed(() => props.assistants.map((assistant) => ({
  label: assistant.name,
  value: assistant.id,
})));
const scenarioOptions = computed(() => {
  const assistant = props.assistants.find((item) => item.id === profileForm.assistantId);
  const scenarioIds = assistant ? new Set([assistant.scenarioId]) : new Set(props.scenarios.map((scenario) => scenario.id));
  return props.scenarios
    .filter((scenario) => scenarioIds.has(scenario.id))
    .map((scenario) => ({ label: scenario.name, value: scenario.id }));
});
const profileConfigUiSchema = computed(() => schemaDrivenUiSchemaWithFallback(
  profileFormDefinition.value?.configSchema,
  profileFormDefinition.value?.configUiSchema,
  'Provider config',
));

function schema(value: Record<string, unknown> | null | undefined): JsonObject {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
}

function uiSchema(value: Record<string, unknown>[] | null | undefined): SchemaDrivenFormUiField[] {
  return Array.isArray(value) ? value as unknown as SchemaDrivenFormUiField[] : [];
}

function formatTime(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString() : '-';
}

function compactJson(value: unknown): string {
  const raw = JSON.stringify(value ?? {}, null, 2);
  return raw.length > 180 ? `${raw.slice(0, 177)}...` : raw;
}

function profileProviderLabel(profile: ChannelProfile): string {
  const definition = definitions.value.find((item) => item.providerType === profile.providerType);
  return definition ? `${definition.title} (${profile.providerType})` : profile.providerType;
}

function clearProfileResources() {
  conversationBindings.value = [];
  inboundEvents.value = [];
  outboundDeliveries.value = [];
  templateBindings.value = [];
  providerJobs.value = [];
  for (const key of Object.keys(providerJobRuns)) {
    delete providerJobRuns[key];
  }
}

function syncJobForms() {
  const jobDefinitions = selectedDefinition.value?.jobDefinitions ?? [];
  const activeJobTypes = new Set(jobDefinitions.map((definition) => definition.jobType));
  for (const key of Object.keys(jobForms)) {
    if (!activeJobTypes.has(key)) {
      delete jobForms[key];
    }
  }
  for (const jobDefinition of jobDefinitions) {
    const existingJob = providerJobs.value.find((job) => job.jobType === jobDefinition.jobType);
    jobForms[jobDefinition.jobType] = createJobFormState(jobDefinition, existingJob);
  }
}

async function loadJobRunsForSavedJobs(profileId: string, jobs: ChannelProviderJobConfig[]) {
  const runEntries = await Promise.all(jobs.map(async (job) => [
    job.jobType,
    await api.listChannelProviderJobRuns(profileId, job.jobType),
  ] as const));
  if (selectedProfileId.value !== profileId) {
    return;
  }
  for (const key of Object.keys(providerJobRuns)) {
    delete providerJobRuns[key];
  }
  for (const [jobType, runs] of runEntries) {
    providerJobRuns[jobType] = runs;
  }
}

async function loadSelectedProfileResources() {
  const profile = selectedProfile.value;
  if (!profile) {
    clearProfileResources();
    return;
  }
  const profileId = profile.id;

  detailLoading.value = true;
  try {
    const [
      bindings,
      inbound,
      outbound,
      templates,
      jobs,
    ] = await Promise.all([
      api.listChannelBindings(profileId),
      api.listChannelInboundEvents(profileId),
      api.listChannelOutboundDeliveries(profileId),
      api.listChannelTemplateBindings(profileId),
      api.listChannelProviderJobs(profileId),
    ]);
    if (selectedProfileId.value !== profileId) {
      return;
    }
    conversationBindings.value = bindings;
    inboundEvents.value = inbound;
    outboundDeliveries.value = outbound;
    templateBindings.value = templates;
    providerJobs.value = jobs;
    syncJobForms();
    await loadJobRunsForSavedJobs(profileId, jobs);
  } finally {
    if (selectedProfileId.value === profileId) {
      detailLoading.value = false;
    }
  }
}

async function loadBaseData() {
  loading.value = true;
  try {
    const [definitionList, profileList, accountList] = await Promise.all([
      api.listChannelProviderDefinitions(),
      api.listChannelProfiles(),
      api.listIntegrationAccounts(),
    ]);
    definitions.value = definitionList;
    profiles.value = profileList;
    accounts.value = accountList;
    if (!profiles.value.some((profile) => profile.id === selectedProfileId.value)) {
      selectedProfileId.value = profiles.value[0]?.id ?? '';
    }
  } finally {
    loading.value = false;
  }
}

async function refreshAll() {
  try {
    await loadBaseData();
    await loadSelectedProfileResources();
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '加载 Channel Admin 失败');
  }
}

onMounted(() => {
  void refreshAll();
});

watch(selectedProfileId, () => {
  void loadSelectedProfileResources().catch((error) => {
    void message.error(error instanceof Error ? error.message : '加载 Channel Profile 详情失败');
  });
});

watch(
  () => profileForm.providerType,
  (providerType, previousProviderType) => {
    if (!profileDrawerOpen.value || providerType === previousProviderType) {
      return;
    }
    const definition = profileFormDefinition.value;
    profileForm.config = definition ? createProfileFormForDefinition(definition).config : {};
    profileForm.integrationAccountId = null;
  },
);

function openCreateProfileDrawer() {
  editingProfileId.value = null;
  const nextForm = createProfileFormForDefinition(definitions.value[0] ?? null);
  Object.assign(profileForm, nextForm);
  profileDrawerOpen.value = true;
}

function openEditProfileDrawer(profile: ChannelProfile) {
  editingProfileId.value = profile.id;
  Object.assign(profileForm, profileFormFromProfile(profile));
  profileDrawerOpen.value = true;
}

function validateProfileConfig(): boolean {
  const result = profileConfigFormRef.value?.validate();
  if (result && !result.valid) {
    void message.error('Provider config 校验未通过');
    return false;
  }
  return true;
}

function profilePayload(): CreateChannelProfilePayload {
  return {
    providerType: profileForm.providerType,
    displayName: profileForm.displayName.trim(),
    status: profileForm.status,
    inboundEnabled: profileForm.inboundEnabled,
    config: profileForm.config,
    assistantBinding: {
      assistantId: profileForm.assistantId || null,
      scenarioId: profileForm.scenarioId || null,
    },
    integrationAccountId: profileForm.integrationAccountId || null,
  };
}

async function submitProfile() {
  if (!profileFormDefinition.value) {
    void message.error('请选择可用的 Channel Provider definition');
    return;
  }
  if (!profileForm.displayName.trim()) {
    void message.error('请输入 Channel Profile 名称');
    return;
  }
  if (selectedProfileHardBlock.value) {
    void message.error('当前账号状态阻断保存，请更换账号或先修复账号');
    return;
  }
  if (!validateProfileConfig()) {
    return;
  }

  savingProfile.value = true;
  try {
    if (editingProfileId.value) {
      const profile = profiles.value.find((item) => item.id === editingProfileId.value);
      if (!profile) {
        throw new Error('Channel Profile 不存在');
      }
      const payload: UpdateChannelProfilePayload = {
        ...profilePayload(),
        expectedRevision: profile.revision,
      };
      await api.updateChannelProfile(editingProfileId.value, payload);
    } else {
      await api.createChannelProfile(profilePayload());
    }
    profileDrawerOpen.value = false;
    await refreshAll();
    void message.success('Channel Profile 已保存');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '保存 Channel Profile 失败');
  } finally {
    savingProfile.value = false;
  }
}

async function deleteSelectedProfile() {
  const profile = selectedProfile.value;
  if (!profile) {
    return;
  }
  deletingProfile.value = true;
  try {
    await api.deleteChannelProfile(profile.id, profile.revision);
    await refreshAll();
    void message.success('Channel Profile 已删除');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '删除 Channel Profile 失败');
  } finally {
    deletingProfile.value = false;
  }
}

function existingJob(jobType: string): ChannelProviderJobConfig | null {
  return providerJobs.value.find((job) => job.jobType === jobType) ?? null;
}

function jobConfigUiSchema(jobDefinition: JobDefinition): SchemaDrivenFormUiField[] {
  return schemaDrivenUiSchemaWithFallback(
    jobDefinition.jobConfigSchema,
    jobDefinition.jobConfigUiSchema,
    `${jobDefinition.title} config`,
  );
}

async function submitProviderJob(jobDefinition: JobDefinition) {
  const profile = selectedProfile.value;
  const form = jobForms[jobDefinition.jobType];
  if (!profile || !form) {
    return;
  }

  const validation = validateSchemaDrivenForm(jobDefinition.jobConfigSchema, form.jobConfig, {
    mode: 'config',
    uiSchema: jobConfigUiSchema(jobDefinition),
  });
  if (!validation.valid) {
    void message.error(`${jobDefinition.title} config 校验未通过`);
    return;
  }

  jobSaving[jobDefinition.jobType] = true;
  try {
    await api.upsertChannelProviderJob(
      profile.id,
      jobDefinition.jobType,
      buildProviderJobWritePayload(form, existingJob(jobDefinition.jobType)),
    );
    await loadSelectedProfileResources();
    void message.success('Provider Job 已保存');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '保存 Provider Job 失败');
  } finally {
    jobSaving[jobDefinition.jobType] = false;
  }
}

async function disableProviderJob(job: ChannelProviderJobConfig) {
  const profile = selectedProfile.value;
  if (!profile) {
    return;
  }
  jobSaving[job.jobType] = true;
  try {
    await api.deleteChannelProviderJob(profile.id, job.jobType, job.revision);
    await loadSelectedProfileResources();
    void message.success('Provider Job 已停用');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '停用 Provider Job 失败');
  } finally {
    jobSaving[job.jobType] = false;
  }
}

async function runProviderJob(job: ChannelProviderJobConfig) {
  const profile = selectedProfile.value;
  if (!profile || job.status !== 'ACTIVE') {
    return;
  }
  const profileId = profile.id;
  jobRunning[job.jobType] = true;
  try {
    await api.runChannelProviderJob(profileId, job.jobType);
    const refreshedJobs = await api.listChannelProviderJobs(profileId);
    if (selectedProfileId.value !== profileId) {
      return;
    }
    providerJobs.value = refreshedJobs;
    await loadJobRunsForSavedJobs(profileId, refreshedJobs);
    if (selectedProfileId.value !== profileId) {
      return;
    }
    syncJobForms();
    void message.success('Provider Job 已触发');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '触发 Provider Job 失败');
  } finally {
    jobRunning[job.jobType] = false;
  }
}

function resetTemplateDrawer() {
  editingTemplateBinding.value = null;
  templateKeyForm.assistantId = selectedProfile.value?.assistantBinding?.assistantId ?? '';
  templateKeyForm.messageType = 'CARD';
  templateKeyForm.messageSubtype = '';
  templateKeyForm.messageVersion = 'v1';
  templateForm.externalTemplateId = '';
  templateForm.externalTemplateVersion = null;
  templateForm.variableSchemaJson = '{}';
  templateForm.displayName = '';
  templateForm.externalEditUrl = null;
  templateForm.enabled = true;
  templateForm.expectedRevision = null;
}

function openCreateTemplateDrawer() {
  resetTemplateDrawer();
  templateDrawerOpen.value = true;
}

function openEditTemplateDrawer(binding: ChannelTemplateBinding) {
  editingTemplateBinding.value = binding;
  templateKeyForm.assistantId = binding.assistantId;
  templateKeyForm.messageType = binding.messageType;
  templateKeyForm.messageSubtype = binding.messageSubtype;
  templateKeyForm.messageVersion = binding.messageVersion;
  templateForm.externalTemplateId = binding.externalTemplateId;
  templateForm.externalTemplateVersion = binding.externalTemplateVersion;
  templateForm.variableSchemaJson = JSON.stringify(binding.variableSchema ?? {}, null, 2);
  templateForm.displayName = binding.displayName;
  templateForm.externalEditUrl = binding.externalEditUrl;
  templateForm.enabled = binding.enabled;
  templateForm.expectedRevision = binding.revision;
  templateDrawerOpen.value = true;
}

async function submitTemplateBinding() {
  const profile = selectedProfile.value;
  if (!profile) {
    return;
  }
  const profileId = profile.id;
  if (!templateKeyForm.assistantId.trim() || !templateKeyForm.messageType.trim() || !templateKeyForm.messageSubtype.trim() || !templateKeyForm.messageVersion.trim()) {
    void message.error('请填写完整的消息匹配字段');
    return;
  }
  if (!templateForm.externalTemplateId.trim() || !templateForm.displayName.trim()) {
    void message.error('请填写外部模板 ID 和名称');
    return;
  }

  const payload = buildTemplateBindingWritePayload(templateForm);
  if (!payload) {
    void message.error('variableSchema 必须是 JSON object');
    return;
  }

  savingTemplate.value = true;
  try {
    await api.upsertChannelTemplateBinding(
      profileId,
      templateKeyForm.assistantId.trim(),
      templateKeyForm.messageType.trim(),
      templateKeyForm.messageSubtype.trim(),
      templateKeyForm.messageVersion.trim(),
      payload,
    );
    templateDrawerOpen.value = false;
    const refreshedBindings = await api.listChannelTemplateBindings(profileId);
    if (selectedProfileId.value !== profileId) {
      return;
    }
    templateBindings.value = refreshedBindings;
    void message.success('Template Binding 已保存');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '保存 Template Binding 失败');
  } finally {
    savingTemplate.value = false;
  }
}

async function deleteTemplateBinding(binding: ChannelTemplateBinding) {
  const profile = selectedProfile.value;
  if (!profile) {
    return;
  }
  const profileId = profile.id;
  try {
    await api.deleteChannelTemplateBinding(
      profileId,
      binding.assistantId,
      binding.messageType,
      binding.messageSubtype,
      binding.messageVersion,
      binding.revision,
    );
    const refreshedBindings = await api.listChannelTemplateBindings(profileId);
    if (selectedProfileId.value !== profileId) {
      return;
    }
    templateBindings.value = refreshedBindings;
    void message.success('Template Binding 已删除');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '删除 Template Binding 失败');
  }
}
</script>

<template>
  <PageHeadActions>
    <a-button type="primary" :loading="loading" @click="openCreateProfileDrawer">新建 Channel Profile</a-button>
    <a-button :loading="loading || detailLoading" @click="refreshAll">刷新</a-button>
  </PageHeadActions>

  <a-row :gutter="[16, 16]">
    <a-col :span="8">
      <a-card title="Channel Profiles">
        <a-table
          row-key="id"
          :columns="profileColumns"
          :data-source="profiles"
          :loading="loading"
          size="small"
          :pagination="false"
          :custom-row="(record: ChannelProfile) => ({
            onClick: () => { selectedProfileId = record.id; },
            class: selectedProfileId === record.id ? 'channel-admin-row-active' : '',
          })"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'providerType'">
              {{ profileProviderLabel(record) }}
            </template>
            <template v-else-if="column.key === 'status'">
              <a-tag :color="record.status === 'ACTIVE' ? 'green' : 'default'">{{ record.status }}</a-tag>
            </template>
            <template v-else-if="column.key === 'inboundEnabled'">
              <a-tag :color="record.inboundEnabled ? 'blue' : 'default'">
                {{ record.inboundEnabled ? 'ON' : 'OFF' }}
              </a-tag>
            </template>
          </template>
        </a-table>
      </a-card>
    </a-col>

    <a-col :span="16">
      <template v-if="selectedProfile">
        <a-card :title="selectedProfile.displayName" :loading="detailLoading">
          <template #extra>
            <a-space>
              <a-button size="small" @click="openEditProfileDrawer(selectedProfile)">编辑</a-button>
              <a-popconfirm title="确认删除当前 Channel Profile？" @confirm="deleteSelectedProfile">
                <a-button size="small" danger :loading="deletingProfile">删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>

          <a-alert
            v-if="selectedProfile.integrationAccount?.availabilityHardBlock"
            type="error"
            show-icon
            style="margin-bottom: 12px"
            :message="`Integration Account 阻断：${selectedProfile.integrationAccount.availabilityHardBlock}`"
          />
          <a-alert
            v-else-if="accountRiskSummary.length > 0"
            type="warning"
            show-icon
            style="margin-bottom: 12px"
            :message="`Integration Account 风险：${accountRiskSummary.join(', ')}`"
          />

            <a-tabs v-model:activeKey="activeTab">
              <a-tab-pane key="profile" tab="Profile">
                <a-descriptions :column="2" size="small" bordered>
                  <a-descriptions-item label="Profile ID">{{ selectedProfile.id }}</a-descriptions-item>
                  <a-descriptions-item label="Provider">{{ profileProviderLabel(selectedProfile) }}</a-descriptions-item>
                  <a-descriptions-item label="Status">
                    <a-tag :color="selectedProfile.status === 'ACTIVE' ? 'green' : 'default'">{{ selectedProfile.status }}</a-tag>
                  </a-descriptions-item>
                  <a-descriptions-item label="Inbound">
                    {{ selectedProfile.inboundEnabled ? 'ON' : 'OFF' }}
                  </a-descriptions-item>
                  <a-descriptions-item label="Assistant">
                    {{ selectedProfile.assistantBinding?.assistantId || '-' }}
                  </a-descriptions-item>
                  <a-descriptions-item label="Scenario">
                    {{ selectedProfile.assistantBinding?.scenarioId || '-' }}
                  </a-descriptions-item>
                  <a-descriptions-item label="Integration Account">
                    {{ selectedProfile.integrationAccount?.name || selectedProfile.accountId || '-' }}
                  </a-descriptions-item>
                  <a-descriptions-item label="Revision">{{ selectedProfile.revision }}</a-descriptions-item>
                  <a-descriptions-item label="Updated">{{ formatTime(selectedProfile.updatedAt) }}</a-descriptions-item>
                  <a-descriptions-item label="Created">{{ formatTime(selectedProfile.createdAt) }}</a-descriptions-item>
                </a-descriptions>
                <a-divider orientation="left">Config</a-divider>
                <pre class="channel-admin-json">{{ compactJson(selectedProfile.config) }}</pre>
              </a-tab-pane>

              <a-tab-pane key="jobs" tab="Provider Jobs">
                <a-empty
                  v-if="!selectedDefinition?.jobDefinitions.length"
                  description="当前 Provider 未声明 Provider Job"
                />
                <a-collapse v-else>
                  <a-collapse-panel
                    v-for="jobDefinition in selectedDefinition.jobDefinitions"
                    :key="jobDefinition.jobType"
                    :header="`${jobDefinition.title} (${jobDefinition.jobType})`"
                  >
                    <a-alert
                      v-if="jobDefinition.description"
                      type="info"
                      show-icon
                      style="margin-bottom: 12px"
                      :message="jobDefinition.description"
                    />
                    <a-form
                      v-if="jobForms[jobDefinition.jobType]"
                      layout="vertical"
                    >
                      <a-row :gutter="[12, 12]">
                        <a-col :span="6">
                          <a-form-item label="启用">
                            <a-switch v-model:checked="jobForms[jobDefinition.jobType].enabled" />
                          </a-form-item>
                        </a-col>
                        <a-col :span="6">
                          <a-form-item label="Schedule Type">
                            <a-select
                              v-model:value="jobForms[jobDefinition.jobType].scheduleType"
                              :options="scheduleTypeOptions"
                            />
                          </a-form-item>
                        </a-col>
                        <a-col :span="6">
                          <a-form-item label="Interval Seconds">
                            <a-input-number
                              v-model:value="jobForms[jobDefinition.jobType].intervalSeconds"
                              style="width: 100%"
                              :disabled="jobForms[jobDefinition.jobType].scheduleType !== 'INTERVAL'"
                            />
                          </a-form-item>
                        </a-col>
                        <a-col :span="6">
                          <a-form-item label="Timeout Seconds">
                            <a-input-number
                              v-model:value="jobForms[jobDefinition.jobType].jobTimeoutSeconds"
                              style="width: 100%"
                            />
                          </a-form-item>
                        </a-col>
                        <a-col :span="12">
                          <a-form-item label="Cron Expression">
                            <a-input
                              v-model:value="jobForms[jobDefinition.jobType].cronExpression"
                              :disabled="jobForms[jobDefinition.jobType].scheduleType !== 'CRON'"
                            />
                          </a-form-item>
                        </a-col>
                        <a-col :span="12">
                          <a-form-item label="Timezone">
                            <a-input v-model:value="jobForms[jobDefinition.jobType].timezone" />
                          </a-form-item>
                        </a-col>
                      </a-row>
                    </a-form>

                    <SchemaDrivenForm
                      v-if="jobForms[jobDefinition.jobType]"
                      v-model="jobForms[jobDefinition.jobType].jobConfig"
                      :schema="schema(jobDefinition.jobConfigSchema)"
                      :ui-schema="jobConfigUiSchema(jobDefinition)"
                      mode="config"
                    />

                    <a-space style="margin-top: 12px" wrap>
                      <a-button
                        type="primary"
                        :loading="jobSaving[jobDefinition.jobType]"
                        @click="submitProviderJob(jobDefinition)"
                      >
                        保存 Job
                      </a-button>
                      <a-button
                        :disabled="existingJob(jobDefinition.jobType)?.status !== 'ACTIVE'"
                        :loading="jobRunning[jobDefinition.jobType]"
                        @click="existingJob(jobDefinition.jobType) && runProviderJob(existingJob(jobDefinition.jobType)!)"
                      >
                        手动运行
                      </a-button>
                      <a-button
                        v-if="existingJob(jobDefinition.jobType)"
                        danger
                        :loading="jobSaving[jobDefinition.jobType]"
                        @click="disableProviderJob(existingJob(jobDefinition.jobType)!)"
                      >
                        停用 Job
                      </a-button>
                      <a-tag v-if="existingJob(jobDefinition.jobType)">
                        {{ existingJob(jobDefinition.jobType)?.status }} · rev {{ existingJob(jobDefinition.jobType)?.revision }}
                      </a-tag>
                    </a-space>

                    <a-table
                      style="margin-top: 12px"
                      row-key="runId"
                      size="small"
                      :pagination="false"
                      :data-source="providerJobRuns[jobDefinition.jobType] ?? []"
                      :columns="[
                        { title: 'Run', dataIndex: 'runId', key: 'runId' },
                        { title: '状态', dataIndex: 'status', key: 'status' },
                        { title: '事件数', dataIndex: 'eventsIngested', key: 'eventsIngested' },
                        { title: '开始', dataIndex: 'startedAt', key: 'startedAt' },
                        { title: '结束', dataIndex: 'finishedAt', key: 'finishedAt' },
                      ]"
                    >
                      <template #bodyCell="{ column, record }">
                        <template v-if="column.key === 'startedAt'">{{ formatTime(record.startedAt) }}</template>
                        <template v-else-if="column.key === 'finishedAt'">{{ formatTime(record.finishedAt) }}</template>
                      </template>
                    </a-table>
                  </a-collapse-panel>
                </a-collapse>
              </a-tab-pane>

              <a-tab-pane key="templates" tab="Template Bindings">
                <div style="margin-bottom: 12px">
                  <a-button type="primary" @click="openCreateTemplateDrawer">新增绑定</a-button>
                </div>
                <a-table
                  row-key="id"
                  size="small"
                  :pagination="false"
                  :columns="templateColumns"
                  :data-source="templateBindings"
                >
                  <template #bodyCell="{ column, record }">
                    <template v-if="column.key === 'message'">
                      {{ record.messageType }}/{{ record.messageSubtype }}/{{ record.messageVersion }}
                    </template>
                    <template v-else-if="column.key === 'enabled'">
                      <a-tag :color="record.enabled ? 'green' : 'default'">
                        {{ record.enabled ? 'ENABLED' : 'DISABLED' }}
                      </a-tag>
                    </template>
                    <template v-else-if="column.key === 'actions'">
                      <a-space>
                        <a-button size="small" @click="openEditTemplateDrawer(record)">编辑</a-button>
                        <a-popconfirm title="确认删除当前绑定？" @confirm="deleteTemplateBinding(record)">
                          <a-button size="small" danger>删除</a-button>
                        </a-popconfirm>
                      </a-space>
                    </template>
                  </template>
                </a-table>
              </a-tab-pane>

              <a-tab-pane key="runtime" tab="Runtime Tables">
                <a-divider orientation="left">Conversation Bindings</a-divider>
                <a-table
                  row-key="id"
                  size="small"
                  :pagination="{ pageSize: 6 }"
                  :columns="bindingColumns"
                  :data-source="conversationBindings"
                >
                  <template #bodyCell="{ column, record }">
                    <template v-if="column.key === 'updatedAt'">{{ formatTime(record.updatedAt) }}</template>
                  </template>
                </a-table>

                <a-divider orientation="left">Inbound Events</a-divider>
                <a-table
                  row-key="eventId"
                  size="small"
                  :pagination="{ pageSize: 6 }"
                  :columns="inboundColumns"
                  :data-source="inboundEvents"
                >
                  <template #bodyCell="{ column, record }">
                    <template v-if="column.key === 'createdAt'">{{ formatTime(record.createdAt) }}</template>
                  </template>
                </a-table>

                <a-divider orientation="left">Outbound Deliveries</a-divider>
                <a-table
                  row-key="deliveryId"
                  size="small"
                  :pagination="{ pageSize: 6 }"
                  :columns="outboundColumns"
                  :data-source="outboundDeliveries"
                >
                  <template #bodyCell="{ column, record }">
                    <template v-if="column.key === 'updatedAt'">{{ formatTime(record.updatedAt) }}</template>
                  </template>
                </a-table>
              </a-tab-pane>
            </a-tabs>
          </a-card>
        </template>
        <a-empty v-else description="暂无 Channel Profile" />
      </a-col>
    </a-row>

    <a-drawer
      v-model:open="profileDrawerOpen"
      :title="editingProfileId ? '编辑 Channel Profile' : '新增 Channel Profile'"
      width="760"
      destroy-on-close
    >
      <a-alert
        v-if="selectedProfileHardBlock"
        type="error"
        show-icon
        style="margin-bottom: 16px"
        :message="`当前账号阻断保存：${selectedProfileHardBlock}`"
      />
      <a-form layout="vertical">
        <a-form-item label="Channel Provider" required>
          <a-select
            v-model:value="profileForm.providerType"
            :disabled="Boolean(editingProfileId)"
            :options="providerOptions"
            :loading="loading"
            show-search
            option-filter-prop="label"
          />
        </a-form-item>
        <a-form-item label="名称" required>
          <a-input v-model:value="profileForm.displayName" />
        </a-form-item>
        <a-row :gutter="[12, 12]">
          <a-col :span="12">
            <a-form-item label="状态">
              <a-select v-model:value="profileForm.status" :options="statusOptions" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="Inbound">
              <a-switch v-model:checked="profileForm.inboundEnabled" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="[12, 12]">
          <a-col :span="12">
            <a-form-item label="Assistant">
              <a-select
                v-model:value="profileForm.assistantId"
                :options="assistantOptions"
                show-search
                allow-clear
                option-filter-prop="label"
              />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="Scenario">
              <a-select
                v-model:value="profileForm.scenarioId"
                :options="scenarioOptions"
                show-search
                allow-clear
                option-filter-prop="label"
              />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="Integration Account">
          <a-select
            v-model:value="profileForm.integrationAccountId"
            :options="accountOptions"
            show-search
            allow-clear
            option-filter-prop="label"
          />
        </a-form-item>
      </a-form>

      <a-divider orientation="left">Provider Config</a-divider>
      <SchemaDrivenForm
        v-if="profileFormDefinition"
        ref="profileConfigFormRef"
        v-model="profileForm.config"
        :schema="schema(profileFormDefinition.configSchema)"
        :ui-schema="profileConfigUiSchema"
        mode="config"
      />

      <template #footer>
        <a-space>
          <a-button @click="profileDrawerOpen = false">取消</a-button>
          <a-button
            type="primary"
            :loading="savingProfile"
            :disabled="Boolean(selectedProfileHardBlock)"
            @click="submitProfile"
          >
            保存
          </a-button>
        </a-space>
      </template>
    </a-drawer>

    <a-drawer
      v-model:open="templateDrawerOpen"
      :title="editingTemplateBinding ? '编辑 Template Binding' : '新增 Template Binding'"
      width="720"
      destroy-on-close
    >
      <a-form layout="vertical">
        <a-row :gutter="[12, 12]">
          <a-col :span="12">
            <a-form-item label="Assistant" required>
              <a-select
                v-model:value="templateKeyForm.assistantId"
                :options="assistantOptions"
                :disabled="Boolean(editingTemplateBinding)"
                show-search
                option-filter-prop="label"
              />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="Message Type" required>
              <a-input v-model:value="templateKeyForm.messageType" :disabled="Boolean(editingTemplateBinding)" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="Message Subtype" required>
              <a-input v-model:value="templateKeyForm.messageSubtype" :disabled="Boolean(editingTemplateBinding)" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="Message Version" required>
              <a-input v-model:value="templateKeyForm.messageVersion" :disabled="Boolean(editingTemplateBinding)" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="Display Name" required>
          <a-input v-model:value="templateForm.displayName" />
        </a-form-item>
        <a-row :gutter="[12, 12]">
          <a-col :span="12">
            <a-form-item label="External Template ID" required>
              <a-input v-model:value="templateForm.externalTemplateId" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="External Template Version">
              <a-input v-model:value="templateForm.externalTemplateVersion" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="External Edit URL">
          <a-input v-model:value="templateForm.externalEditUrl" />
        </a-form-item>
        <a-form-item label="状态">
          <a-radio-group v-model:value="templateForm.enabled" :options="booleanOptions" />
        </a-form-item>
        <a-form-item label="Variable Schema">
          <a-textarea v-model:value="templateForm.variableSchemaJson" :rows="8" />
        </a-form-item>
      </a-form>

      <template #footer>
        <a-space>
          <a-button @click="templateDrawerOpen = false">取消</a-button>
          <a-button type="primary" :loading="savingTemplate" @click="submitTemplateBinding">保存</a-button>
        </a-space>
      </template>
    </a-drawer>
</template>

<style scoped>
.channel-admin-row-active :deep(td) {
  background: #f0f7ff;
}

.channel-admin-json {
  max-height: 260px;
  overflow: auto;
  padding: 12px;
  margin: 0;
  background: #f7f8fa;
  border: 1px solid #edf0f5;
  border-radius: 6px;
  white-space: pre-wrap;
}
</style>
