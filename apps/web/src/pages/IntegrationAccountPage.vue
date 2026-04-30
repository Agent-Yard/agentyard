<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { message } from 'ant-design-vue';
import PageHeadActions from '../components/PageHeadActions.vue';
import SchemaDrivenForm from '../components/SchemaDrivenForm.vue';
import { api } from '../services/api';
import {
  canCreateCredential,
  canRevokeCredential,
  canRotateCredential,
  canValidateCredential,
  descriptorOptions,
  findAccountDefinition,
  integrationAccountDefinitions,
  isBlockingCredentialStatus,
  isRiskCredentialStatus,
  supportsCredentialManagement,
} from './integrationAccountDefinition';
import type {
  IntegrationAccountDescriptorOption,
} from './integrationAccountDefinition';
import type {
  ChannelProviderDefinition,
  CreateIntegrationAccountPayload,
  IntegrationAccount,
  IntegrationAccountStatus,
  IntegrationAccountSubjectType,
  ToolConnectorDefinition,
  UpdateIntegrationAccountPayload,
} from '../types';
import type {
  JsonObject,
  SchemaDrivenFormUiField,
  SchemaDrivenFormValidationResult,
} from '../components/schemaDrivenForm';

interface SchemaDrivenFormExpose {
  validate: () => SchemaDrivenFormValidationResult;
}

type CredentialAction = 'create' | 'rotate';

const accounts = ref<IntegrationAccount[]>([]);
const toolConnectorDefinitions = ref<ToolConnectorDefinition[]>([]);
const channelProviderDefinitions = ref<ChannelProviderDefinition[]>([]);
const loading = ref(false);
const saving = ref(false);
const definitionsLoading = ref(false);
const drawerOpen = ref(false);
const credentialDrawerOpen = ref(false);
const editingAccountId = ref<string | null>(null);
const credentialAction = ref<CredentialAction>('create');
const credentialAccount = ref<IntegrationAccount | null>(null);
const credentialActionLoading = ref<string | null>(null);
const configFormRef = ref<SchemaDrivenFormExpose | null>(null);
const initialCredentialFormRef = ref<SchemaDrivenFormExpose | null>(null);
const lifecycleCredentialFormRef = ref<SchemaDrivenFormExpose | null>(null);

const form = reactive({
  subjectType: 'TOOL_CONNECTOR' as IntegrationAccountSubjectType,
  subjectId: '',
  name: '',
  status: 'ENABLED' as IntegrationAccountStatus,
  config: {} as JsonObject,
  includeInitialCredential: false,
  initialCredential: {} as JsonObject,
});

const credentialForm = reactive({
  credential: {} as JsonObject,
});

const allDefinitions = computed(() => integrationAccountDefinitions(
  toolConnectorDefinitions.value,
  channelProviderDefinitions.value,
));

const descriptorSelectOptions = computed<IntegrationAccountDescriptorOption[]>(() => (
  descriptorOptions(allDefinitions.value, form.subjectType)
));

const selectedDefinition = computed(() => (
  findAccountDefinition(allDefinitions.value, form.subjectType, form.subjectId)
));

const selectedCredentialDefinition = computed(() => {
  const account = credentialAccount.value;
  if (!account) {
    return null;
  }
  return findAccountDefinition(allDefinitions.value, account.subjectType, account.subjectId);
});

const isEditing = computed(() => Boolean(editingAccountId.value));
const createCredentialSupported = computed(() => (
  !isEditing.value && supportsCredentialManagement(selectedDefinition.value)
));
const shouldRenderInitialCredential = computed(() => (
  createCredentialSupported.value && form.includeInitialCredential
));

const tableColumns = [
  { title: '名称', dataIndex: 'name', key: 'name' },
  { title: '对象类型', dataIndex: 'subjectType', key: 'subjectType' },
  { title: 'Descriptor', dataIndex: 'subjectId', key: 'subjectId' },
  { title: '账号状态', dataIndex: 'status', key: 'status' },
  { title: '凭证状态', dataIndex: 'credentialStatus', key: 'credentialStatus' },
  { title: '凭证', key: 'credentialConfigured' },
  { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt' },
  { title: '操作', key: 'actions' },
];

const accountStatusOptions = [
  { label: 'ENABLED', value: 'ENABLED' },
  { label: 'DISABLED', value: 'DISABLED' },
  { label: 'ARCHIVED', value: 'ARCHIVED' },
];

const subjectTypeOptions = [
  { label: 'Tool Connector', value: 'TOOL_CONNECTOR' },
  { label: 'Channel Provider', value: 'CHANNEL_PROVIDER' },
];

function schema(value: Record<string, unknown> | null | undefined): JsonObject {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
}

function uiSchema(value: Record<string, unknown>[] | null | undefined): SchemaDrivenFormUiField[] {
  return Array.isArray(value) ? value as unknown as SchemaDrivenFormUiField[] : [];
}

function descriptorLabel(account: IntegrationAccount): string {
  const definition = findAccountDefinition(allDefinitions.value, account.subjectType, account.subjectId);
  return definition ? `${definition.title} (${account.subjectId})` : account.subjectId;
}

function credentialStatusColor(account: IntegrationAccount): string {
  if (isBlockingCredentialStatus(account.credentialStatus)) {
    return 'red';
  }
  if (isRiskCredentialStatus(account.credentialStatus)) {
    return 'orange';
  }
  return account.credentialConfigured ? 'green' : 'default';
}

function credentialStatusLabel(account: IntegrationAccount): string {
  if (isBlockingCredentialStatus(account.credentialStatus)) {
    return `${account.credentialStatus} · 阻断`;
  }
  if (isRiskCredentialStatus(account.credentialStatus)) {
    return `${account.credentialStatus} · 风险`;
  }
  return account.credentialStatus;
}

function resetDescriptorForSubjectType(subjectType: IntegrationAccountSubjectType) {
  const firstOption = descriptorOptions(allDefinitions.value, subjectType)[0];
  form.subjectId = firstOption?.value ?? '';
}

async function loadDefinitions() {
  definitionsLoading.value = true;
  try {
    const [toolConnectors, channelProviders] = await Promise.all([
      api.listToolConnectorDefinitions(),
      api.listChannelProviderDefinitions(),
    ]);
    toolConnectorDefinitions.value = toolConnectors;
    channelProviderDefinitions.value = channelProviders;
    if (!form.subjectId) {
      resetDescriptorForSubjectType(form.subjectType);
    }
  } finally {
    definitionsLoading.value = false;
  }
}

async function loadAccounts() {
  loading.value = true;
  try {
    accounts.value = await api.listIntegrationAccounts();
  } finally {
    loading.value = false;
  }
}

onMounted(async () => {
  try {
    await Promise.all([loadDefinitions(), loadAccounts()]);
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '加载 Integration Account 失败');
  }
});

watch(() => form.subjectType, (subjectType) => {
  if (!isEditing.value) {
    resetDescriptorForSubjectType(subjectType);
    form.config = {};
    form.includeInitialCredential = false;
    form.initialCredential = {};
  }
});

watch(() => form.subjectId, () => {
  if (!isEditing.value) {
    form.config = {};
    form.includeInitialCredential = false;
    form.initialCredential = {};
  }
});

function openCreateDrawer() {
  editingAccountId.value = null;
  form.subjectType = 'TOOL_CONNECTOR';
  resetDescriptorForSubjectType(form.subjectType);
  form.name = '';
  form.status = 'ENABLED';
  form.config = {};
  form.includeInitialCredential = false;
  form.initialCredential = {};
  drawerOpen.value = true;
}

function openEditDrawer(account: IntegrationAccount) {
  editingAccountId.value = account.id;
  form.subjectType = account.subjectType;
  form.subjectId = account.subjectId;
  form.name = account.name;
  form.status = account.status;
  form.config = { ...(account.config ?? {}) };
  form.includeInitialCredential = false;
  form.initialCredential = {};
  drawerOpen.value = true;
}

function validateConfigForm(): boolean {
  const result = configFormRef.value?.validate();
  if (result && !result.valid) {
    void message.error('账号配置校验未通过');
    return false;
  }
  return true;
}

function validateInitialCredentialForm(): boolean {
  if (!shouldRenderInitialCredential.value) {
    return true;
  }
  const result = initialCredentialFormRef.value?.validate();
  if (result && !result.valid) {
    void message.error('初始凭证校验未通过');
    return false;
  }
  return true;
}

function validateLifecycleCredentialForm(): boolean {
  const result = lifecycleCredentialFormRef.value?.validate();
  if (result && !result.valid) {
    void message.error('凭证校验未通过');
    return false;
  }
  return true;
}

async function submitAccount() {
  if (!selectedDefinition.value) {
    void message.error('请选择可用的 extension definition');
    return;
  }
  if (!form.name.trim()) {
    void message.error('请输入账号名称');
    return;
  }
  if (!validateConfigForm() || !validateInitialCredentialForm()) {
    return;
  }

  saving.value = true;
  try {
    if (editingAccountId.value) {
      const payload: UpdateIntegrationAccountPayload = {
        name: form.name.trim(),
        status: form.status,
        config: form.config,
      };
      await api.updateIntegrationAccount(editingAccountId.value, payload);
    } else {
      const payload: CreateIntegrationAccountPayload = {
        subjectType: form.subjectType,
        subjectId: form.subjectId,
        name: form.name.trim(),
        status: form.status,
        config: form.config,
        ...(shouldRenderInitialCredential.value ? { credential: form.initialCredential } : {}),
      };
      await api.createIntegrationAccount(payload);
    }
    drawerOpen.value = false;
    await loadAccounts();
    void message.success('Integration Account 已保存');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '保存 Integration Account 失败');
  } finally {
    saving.value = false;
  }
}

function openCredentialDrawer(account: IntegrationAccount, action: CredentialAction) {
  credentialAccount.value = account;
  credentialAction.value = action;
  credentialForm.credential = {};
  credentialDrawerOpen.value = true;
}

async function submitCredentialAction() {
  const account = credentialAccount.value;
  if (!account || !selectedCredentialDefinition.value || !validateLifecycleCredentialForm()) {
    return;
  }

  credentialActionLoading.value = credentialAction.value;
  try {
    if (credentialAction.value === 'create') {
      await api.createIntegrationAccountCredential(account.id, { credential: credentialForm.credential });
    } else {
      await api.rotateIntegrationAccountCredential(account.id, { credential: credentialForm.credential });
    }
    credentialDrawerOpen.value = false;
    await loadAccounts();
    void message.success(credentialAction.value === 'create' ? '凭证已创建' : '凭证已轮换');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '凭证操作失败');
  } finally {
    credentialActionLoading.value = null;
  }
}

async function runCredentialAction(account: IntegrationAccount, action: 'validate' | 'revoke') {
  credentialActionLoading.value = `${action}:${account.id}`;
  try {
    if (action === 'validate') {
      await api.validateIntegrationAccountCredential(account.id);
    } else {
      await api.revokeIntegrationAccountCredential(account.id);
    }
    await loadAccounts();
    void message.success(action === 'validate' ? '凭证校验已完成' : '凭证已撤销');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '凭证操作失败');
  } finally {
    credentialActionLoading.value = null;
  }
}
</script>

<template>
  <PageHeadActions>
    <a-button type="primary" :loading="definitionsLoading" @click="openCreateDrawer">新建接入账号</a-button>
  </PageHeadActions>

  <a-card title="接入账号列表">
    <template #extra>
      <a-tag class="console-accent-tag">{{ accounts.length }} 个账号</a-tag>
    </template>

    <a-table
      row-key="id"
      :columns="tableColumns"
      :data-source="accounts"
      :loading="loading || definitionsLoading"
      size="small"
      :pagination="false"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'subjectId'">
          {{ descriptorLabel(record) }}
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="record.status === 'ENABLED' ? 'green' : 'default'">{{ record.status }}</a-tag>
        </template>
        <template v-else-if="column.key === 'credentialStatus'">
          <a-tag :color="credentialStatusColor(record)">
            {{ credentialStatusLabel(record) }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'credentialConfigured'">
          <a-space size="small">
            <a-tag :color="record.credentialConfigured ? 'green' : 'default'">
              {{ record.credentialConfigured ? '已配置' : '未配置' }}
            </a-tag>
            <a-tag v-if="record.hasExternalSecretRef" color="blue">外部引用</a-tag>
          </a-space>
        </template>
        <template v-else-if="column.key === 'updatedAt'">
          {{ new Date(record.updatedAt).toLocaleString() }}
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-space wrap>
            <a-button size="small" @click="openEditDrawer(record)">编辑</a-button>
            <a-button
              v-if="canCreateCredential(record, findAccountDefinition(allDefinitions, record.subjectType, record.subjectId))"
              size="small"
              @click="openCredentialDrawer(record, 'create')"
            >
              创建凭证
            </a-button>
            <a-button
              v-if="canRotateCredential(record, findAccountDefinition(allDefinitions, record.subjectType, record.subjectId))"
              size="small"
              @click="openCredentialDrawer(record, 'rotate')"
            >
              轮换
            </a-button>
            <a-button
              v-if="canValidateCredential(record, findAccountDefinition(allDefinitions, record.subjectType, record.subjectId))"
              size="small"
              :loading="credentialActionLoading === `validate:${record.id}`"
              @click="runCredentialAction(record, 'validate')"
            >
              校验
            </a-button>
            <a-button
              v-if="canRevokeCredential(record, findAccountDefinition(allDefinitions, record.subjectType, record.subjectId))"
              size="small"
              danger
              :loading="credentialActionLoading === `revoke:${record.id}`"
              @click="runCredentialAction(record, 'revoke')"
            >
              撤销
            </a-button>
          </a-space>
        </template>
      </template>
    </a-table>
  </a-card>

  <a-drawer
    v-model:open="drawerOpen"
    :title="isEditing ? '编辑 Integration Account' : '新增 Integration Account'"
    width="720"
    destroy-on-close
  >
    <a-alert
      v-if="isEditing && selectedDefinition && !supportsCredentialManagement(selectedDefinition)"
      type="info"
      show-icon
      style="margin-bottom: 16px"
      message="当前 descriptor 未声明 Core/Web 可管理的 credential"
      description="此账号只维护非敏感 config；credential 由 extension 私有配置或私有管理面维护。"
    />
    <a-alert
      v-if="!selectedDefinition"
      type="error"
      show-icon
      style="margin-bottom: 16px"
      message="未找到匹配的 extension definition"
      description="请刷新 definition registry 后再创建或编辑账号。"
    />

    <a-form layout="vertical">
      <a-form-item label="对象类型">
        <a-select
          v-model:value="form.subjectType"
          :disabled="isEditing"
          :options="subjectTypeOptions"
        />
      </a-form-item>
      <a-form-item :label="form.subjectType === 'TOOL_CONNECTOR' ? 'Connector' : 'Provider'">
        <a-select
          v-model:value="form.subjectId"
          :disabled="isEditing"
          :loading="definitionsLoading"
          :options="descriptorSelectOptions"
          option-filter-prop="label"
          show-search
        />
      </a-form-item>
      <a-form-item label="名称" required>
        <a-input v-model:value="form.name" />
      </a-form-item>
      <a-form-item label="状态">
        <a-select v-model:value="form.status" :options="accountStatusOptions" />
      </a-form-item>
    </a-form>

    <a-divider orientation="left">账号配置</a-divider>
    <SchemaDrivenForm
      v-if="selectedDefinition"
      ref="configFormRef"
      v-model="form.config"
      :schema="schema(selectedDefinition.accountConfigSchema)"
      :ui-schema="uiSchema(selectedDefinition.accountConfigUiSchema)"
      mode="config"
    />

    <template v-if="!isEditing && createCredentialSupported">
      <a-divider orientation="left">初始凭证</a-divider>
      <a-checkbox v-model:checked="form.includeInitialCredential">
        创建账号时同步提交初始 credential
      </a-checkbox>
      <SchemaDrivenForm
        v-if="shouldRenderInitialCredential && selectedDefinition?.credentialCapability.credentialSchema"
        ref="initialCredentialFormRef"
        v-model="form.initialCredential"
        :schema="schema(selectedDefinition.credentialCapability.credentialSchema)"
        :ui-schema="uiSchema(selectedDefinition.credentialCapability.credentialUiSchema)"
        mode="credential"
        style="margin-top: 16px"
      />
    </template>

    <template #footer>
      <a-space>
        <a-button @click="drawerOpen = false">取消</a-button>
        <a-button type="primary" :loading="saving" @click="submitAccount">保存</a-button>
      </a-space>
    </template>
  </a-drawer>

  <a-drawer
    v-model:open="credentialDrawerOpen"
    :title="credentialAction === 'create' ? '创建 Credential' : '轮换 Credential'"
    width="640"
    destroy-on-close
  >
    <a-alert
      v-if="selectedCredentialDefinition"
      type="info"
      show-icon
      style="margin-bottom: 16px"
      :message="selectedCredentialDefinition.title"
      :description="selectedCredentialDefinition.credentialCapability.mode ?? 'Credential lifecycle'"
    />
    <SchemaDrivenForm
      v-if="selectedCredentialDefinition?.credentialCapability.credentialSchema"
      ref="lifecycleCredentialFormRef"
      v-model="credentialForm.credential"
      :schema="schema(selectedCredentialDefinition.credentialCapability.credentialSchema)"
      :ui-schema="uiSchema(selectedCredentialDefinition.credentialCapability.credentialUiSchema)"
      mode="credential"
    />
    <template #footer>
      <a-space>
        <a-button @click="credentialDrawerOpen = false">取消</a-button>
        <a-button
          type="primary"
          :loading="credentialActionLoading === credentialAction"
          @click="submitCredentialAction"
        >
          提交
        </a-button>
      </a-space>
    </template>
  </a-drawer>
</template>
