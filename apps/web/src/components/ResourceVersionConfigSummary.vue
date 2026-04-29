<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { api } from '../services/api';
import type { IntegrationAccount, ResourceType, ResourceVersionConfiguration, ToolConnectorDefinition } from '../types';

const props = defineProps<{
  resourceType: ResourceType;
  configuration: ResourceVersionConfiguration;
}>();

const toolOperationNames = computed(() =>
  props.configuration.tool?.operations?.map((operation) => operation.name).filter(Boolean).join(' / ') || '-',
);

const toolConnector = computed(() => props.configuration.tool?.connector);
const integrationAccounts = ref<IntegrationAccount[]>([]);
const toolConnectorDefinitions = ref<ToolConnectorDefinition[]>([]);
const connectorDefinition = computed(() => (
  toolConnectorDefinitions.value.find((definition) => definition.connectorType === toolConnector.value?.connectorType) ?? null
));
const connectorLabel = computed(() => {
  const connectorType = toolConnector.value?.connectorType;
  if (!connectorType) {
    return '-';
  }
  return connectorDefinition.value ? `${connectorDefinition.value.title} (${connectorType})` : connectorType;
});
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
  const riskSuffix = ['NOT_CONFIGURED', 'VALIDATION_FAILED', 'ROTATION_REQUIRED'].includes(boundAccount.value.credentialStatus)
    ? ' / 风险'
    : '';
  return `${boundAccount.value.status} / ${boundAccount.value.credentialStatus}${riskSuffix}`;
});

onMounted(async () => {
  if (props.resourceType !== 'TOOL') {
    return;
  }
  try {
    const [definitions, accounts] = await Promise.all([
      api.listToolConnectorDefinitions(),
      api.listIntegrationAccounts(),
    ]);
    toolConnectorDefinitions.value = definitions;
    integrationAccounts.value = accounts;
  } catch {
    toolConnectorDefinitions.value = [];
    integrationAccounts.value = [];
  }
});
</script>

<template>
  <a-descriptions v-if="resourceType === 'TOOL' && configuration.tool" :column="2" size="small">
    <a-descriptions-item label="Connector">{{ connectorLabel }}</a-descriptions-item>
    <a-descriptions-item label="Account">{{ accountLabel }}</a-descriptions-item>
    <a-descriptions-item label="Account Status">{{ accountStatusLabel }}</a-descriptions-item>
    <a-descriptions-item label="超时秒数">{{ toolConnector?.timeoutSeconds ?? '-' }}</a-descriptions-item>
    <a-descriptions-item label="重试策略">{{ toolConnector?.retryPolicy ?? '-' }}</a-descriptions-item>
    <a-descriptions-item label="操作定义" :span="2">{{ toolOperationNames }}</a-descriptions-item>
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
