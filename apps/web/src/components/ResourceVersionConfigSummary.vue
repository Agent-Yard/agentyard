<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { api } from '../services/api';
import type { IntegrationAccount, ResourceType, ResourceVersionConfiguration } from '../types';

const props = defineProps<{
  resourceType: ResourceType;
  configuration: ResourceVersionConfiguration;
}>();

const toolOperationNames = computed(() =>
  props.configuration.tool?.operations?.map((operation) => operation.name).filter(Boolean).join(' / ') || '-',
);

const toolConnector = computed(() => props.configuration.tool?.connector);
const integrationAccounts = ref<IntegrationAccount[]>([]);
const boundAccount = computed(() => {
  const accountId = toolConnector.value?.accountId;
  if (!accountId) {
    return null;
  }
  return integrationAccounts.value.find((account) => account.id === accountId) ?? null;
});
const accountLabel = computed(() => {
  const accountId = toolConnector.value?.accountId;
  if (!accountId) {
    return '未绑定';
  }
  return boundAccount.value ? `${boundAccount.value.name} (${boundAccount.value.subjectId})` : accountId;
});
const accountStatusLabel = computed(() => {
  const accountId = toolConnector.value?.accountId;
  if (!accountId) {
    return '未绑定';
  }
  if (!boundAccount.value) {
    return '未找到';
  }
  return `${boundAccount.value.status} / ${boundAccount.value.credentialStatus}`;
});

onMounted(async () => {
  if (props.resourceType !== 'TOOL') {
    return;
  }
  try {
    integrationAccounts.value = await api.listIntegrationAccounts();
  } catch {
    integrationAccounts.value = [];
  }
});
</script>

<template>
  <a-descriptions v-if="resourceType === 'TOOL' && configuration.tool" :column="2" size="small">
    <a-descriptions-item label="Connector">{{ toolConnector?.connectorType ?? '-' }}</a-descriptions-item>
    <a-descriptions-item label="Account">{{ accountLabel }}</a-descriptions-item>
    <a-descriptions-item label="Account Status">{{ accountStatusLabel }}</a-descriptions-item>
    <a-descriptions-item label="超时秒数">{{ toolConnector?.timeoutSeconds ?? '-' }}</a-descriptions-item>
    <a-descriptions-item label="重试策略">{{ toolConnector?.retryPolicy ?? '-' }}</a-descriptions-item>
    <a-descriptions-item label="操作定义" :span="2">{{ toolOperationNames }}</a-descriptions-item>
    <a-descriptions-item v-if="toolConnector?.config.baseUrl" label="Base URL">{{ toolConnector.config.baseUrl }}</a-descriptions-item>
    <a-descriptions-item v-if="toolConnector?.config.connectionUri" label="连接地址">{{ toolConnector.config.connectionUri }}</a-descriptions-item>
    <a-descriptions-item v-if="toolConnector?.config.namespace" label="命名空间">{{ toolConnector.config.namespace }}</a-descriptions-item>
  </a-descriptions>

  <a-descriptions v-else-if="resourceType === 'LLM_MODEL' && configuration.llmModel" :column="2" size="small">
    <a-descriptions-item label="供应商">{{ configuration.llmModel.providerType }}</a-descriptions-item>
    <a-descriptions-item label="模型">{{ configuration.llmModel.modelId }}</a-descriptions-item>
    <a-descriptions-item label="Base URL">{{ configuration.llmModel.baseUrl }}</a-descriptions-item>
    <a-descriptions-item label="API Key 环境变量">{{ configuration.llmModel.apiKeyEnvVar }}</a-descriptions-item>
    <a-descriptions-item label="Temperature / Max Tokens">
      {{ configuration.llmModel.temperature }} / {{ configuration.llmModel.maxTokens }}
    </a-descriptions-item>
    <a-descriptions-item label="Private Deployment">
      {{ configuration.llmModel.privateDeployment ? '是' : '否' }}
    </a-descriptions-item>
  </a-descriptions>

  <a-descriptions v-else-if="resourceType === 'SKILL' && configuration.skill" :column="1" size="small">
    <a-descriptions-item label="技能名称">{{ configuration.skill.skillName }}</a-descriptions-item>
    <a-descriptions-item label="技能描述">{{ configuration.skill.skillDesc }}</a-descriptions-item>
    <a-descriptions-item label="技能提示">{{ configuration.skill.skillPrompt }}</a-descriptions-item>
  </a-descriptions>
</template>
