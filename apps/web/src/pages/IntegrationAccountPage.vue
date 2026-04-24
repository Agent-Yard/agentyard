<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import PageHeaderCard from '../components/PageHeaderCard.vue';
import { api } from '../services/api';
import type { CreateIntegrationAccountPayload, IntegrationAccount, ToolConnectorType, UpdateIntegrationAccountPayload } from '../types';

const accounts = ref<IntegrationAccount[]>([]);
const loading = ref(false);
const drawerOpen = ref(false);
const editingAccountId = ref<string | null>(null);
const form = reactive({
  connectorType: 'BUSINESS_CODE_SECRET_HTTP' as ToolConnectorType,
  name: '',
  status: 'ACTIVE' as 'ACTIVE' | 'INACTIVE',
  configJson: '{}',
  credentialJson: '',
});

const isEditing = computed(() => Boolean(editingAccountId.value));
const credentialPlaceholder = computed(() => {
  if (isEditing.value) {
    return '留空则保留原凭证';
  }
  if (form.connectorType === 'SIMPLE_HTTP') {
    return '{ bearerToken }';
  }
  if (form.connectorType === 'BUSINESS_CODE_SECRET_HTTP') {
    return '{ businessCode, secretKey }';
  }
  return '{}';
});
const tableColumns = [
  { title: '名称', dataIndex: 'name', key: 'name' },
  { title: 'Connector', dataIndex: 'connectorType', key: 'connectorType' },
  { title: '状态', dataIndex: 'status', key: 'status' },
  { title: '凭证', dataIndex: 'credentialConfigured', key: 'credentialConfigured' },
  { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt' },
  { title: '操作', key: 'actions' },
];

async function loadAccounts() {
  loading.value = true;
  try {
    accounts.value = await api.listIntegrationAccounts();
  } finally {
    loading.value = false;
  }
}

onMounted(loadAccounts);

function openCreateDrawer() {
  editingAccountId.value = null;
  form.connectorType = 'BUSINESS_CODE_SECRET_HTTP';
  form.name = '';
  form.status = 'ACTIVE';
  form.configJson = '{}';
  form.credentialJson = '{\n  "businessCode": "",\n  "secretKey": ""\n}';
  drawerOpen.value = true;
}

function openEditDrawer(account: IntegrationAccount) {
  editingAccountId.value = account.id;
  form.connectorType = account.connectorType;
  form.name = account.name;
  form.status = account.status;
  form.configJson = JSON.stringify(account.config ?? {}, null, 2);
  form.credentialJson = '';
  drawerOpen.value = true;
}

function onConnectorTypeChange(value: ToolConnectorType) {
  form.connectorType = value;
  if (isEditing.value) {
    return;
  }
  form.credentialJson = value === 'SIMPLE_HTTP'
    ? '{\n  "bearerToken": ""\n}'
    : value === 'BUSINESS_CODE_SECRET_HTTP'
      ? '{\n  "businessCode": "",\n  "secretKey": ""\n}'
      : '{}';
}

function parseJsonObject(rawValue: string, fieldName: string) {
  const text = rawValue.trim();
  if (!text) {
    return null;
  }
  const parsed = JSON.parse(text) as unknown;
  if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
    throw new Error(`${fieldName} 必须是 JSON object`);
  }
  return parsed as Record<string, unknown>;
}

async function submitAccount() {
  const config = parseJsonObject(form.configJson, '配置') ?? {};
  const credential = parseJsonObject(form.credentialJson, '凭证');
  if (editingAccountId.value) {
    const payload: UpdateIntegrationAccountPayload = {
      name: form.name,
      status: form.status,
      config,
      credential,
    };
    await api.updateIntegrationAccount(editingAccountId.value, payload);
  } else {
    const payload: CreateIntegrationAccountPayload = {
      connectorType: form.connectorType,
      name: form.name,
      status: form.status,
      config,
      credential,
    };
    await api.createIntegrationAccount(payload);
  }
  drawerOpen.value = false;
  await loadAccounts();
}
</script>

<template>
  <section class="page-section">
    <PageHeaderCard
      title="Integration Account"
      subtitle="维护 Tool Connector 执行时使用的账号、配置和凭证。"
    />
    <div style="margin: 12px 0">
      <a-button type="primary" @click="openCreateDrawer">新增账号</a-button>
    </div>

    <a-table
      row-key="id"
      :columns="tableColumns"
      :data-source="accounts"
      :loading="loading"
      size="small"
      :pagination="false"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'credentialConfigured'">
          <a-tag :color="record.credentialConfigured ? 'green' : 'orange'">
            {{ record.credentialConfigured ? '已配置' : '未配置' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'updatedAt'">
          {{ new Date(record.updatedAt).toLocaleString() }}
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-button size="small" @click="openEditDrawer(record)">编辑</a-button>
        </template>
      </template>
    </a-table>

    <a-drawer
      v-model:open="drawerOpen"
      :title="isEditing ? '编辑 Integration Account' : '新增 Integration Account'"
      width="560"
      destroy-on-close
    >
      <a-form layout="vertical">
        <a-form-item label="Connector 类型">
          <a-select
            :value="form.connectorType"
            :disabled="isEditing"
            :options="[
              { label: 'Simple HTTP', value: 'SIMPLE_HTTP' },
              { label: 'Business Code Secret HTTP', value: 'BUSINESS_CODE_SECRET_HTTP' },
              { label: 'MCP', value: 'MCP' },
            ]"
            @update:value="onConnectorTypeChange"
          />
        </a-form-item>
        <a-form-item label="名称">
          <a-input v-model:value="form.name" />
        </a-form-item>
        <a-form-item label="状态">
          <a-select
            v-model:value="form.status"
            :options="[
              { label: 'ACTIVE', value: 'ACTIVE' },
              { label: 'INACTIVE', value: 'INACTIVE' },
            ]"
          />
        </a-form-item>
        <a-form-item label="配置 JSON">
          <a-textarea v-model:value="form.configJson" :rows="6" />
        </a-form-item>
        <a-form-item label="凭证 JSON">
          <a-textarea
            v-model:value="form.credentialJson"
            :rows="6"
            :placeholder="credentialPlaceholder"
          />
        </a-form-item>
      </a-form>
      <template #footer>
        <a-space>
          <a-button @click="drawerOpen = false">取消</a-button>
          <a-button type="primary" @click="submitAccount">保存</a-button>
        </a-space>
      </template>
    </a-drawer>
  </section>
</template>
