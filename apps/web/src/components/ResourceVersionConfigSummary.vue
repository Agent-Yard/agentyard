<script setup lang="ts">
import { computed } from 'vue';
import type { ResourceType, ResourceVersionConfiguration } from '../types';

const props = defineProps<{
  resourceType: ResourceType;
  configuration: ResourceVersionConfiguration;
}>();

const toolOperationNames = computed(() =>
  props.configuration.tool?.operations?.map((operation) => operation.name).filter(Boolean).join(' / ') || '-',
);
</script>

<template>
  <a-descriptions v-if="resourceType === 'TOOL' && configuration.tool" :column="2" size="small">
    <a-descriptions-item label="Provider">{{ configuration.tool.providerType }}</a-descriptions-item>
    <a-descriptions-item label="鉴权方式">{{ configuration.tool.authType }}</a-descriptions-item>
    <a-descriptions-item label="超时秒数">{{ configuration.tool.timeoutSeconds }}</a-descriptions-item>
    <a-descriptions-item label="重试策略">{{ configuration.tool.retryPolicy }}</a-descriptions-item>
    <a-descriptions-item label="操作定义" :span="2">{{ toolOperationNames }}</a-descriptions-item>
    <a-descriptions-item v-if="configuration.tool.http" label="HTTP Method">{{ configuration.tool.http.method }}</a-descriptions-item>
    <a-descriptions-item v-if="configuration.tool.http" label="HTTP Endpoint">{{ configuration.tool.http.endpoint }}</a-descriptions-item>
    <a-descriptions-item v-if="configuration.tool.mcp" label="MCP 服务">{{ configuration.tool.mcp.serverName }}</a-descriptions-item>
    <a-descriptions-item v-if="configuration.tool.mcp" label="MCP 传输">{{ configuration.tool.mcp.transport }}</a-descriptions-item>
    <a-descriptions-item v-if="configuration.tool.mcp" label="连接地址">{{ configuration.tool.mcp.connectionUri }}</a-descriptions-item>
    <a-descriptions-item v-if="configuration.tool.mcp" label="命名空间">{{ configuration.tool.mcp.namespace }}</a-descriptions-item>
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
