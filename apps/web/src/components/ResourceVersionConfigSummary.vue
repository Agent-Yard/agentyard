<script setup lang="ts">
import { computed } from 'vue';
import type { KnowledgeIndexSnapshot, ResourceType, ResourceVersionConfiguration } from '../types';

const props = withDefaults(defineProps<{
  resourceType: ResourceType;
  configuration: ResourceVersionConfiguration;
  knowledgeSnapshots?: KnowledgeIndexSnapshot[];
}>(), {
  knowledgeSnapshots: () => [],
});

const toolOperationNames = computed(() =>
  props.configuration.tool?.operations?.map((operation) => operation.name).filter(Boolean).join(' / ') || '-',
);
const selectedKnowledgeSnapshot = computed(() =>
  props.knowledgeSnapshots.find((snapshot) => snapshot.id === props.configuration.knowledgeBase?.indexSnapshotId) ?? null,
);

function formatDateTime(value: string | null): string {
  if (!value) {
    return '-';
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}
</script>

<template>
  <a-descriptions v-if="resourceType === 'KNOWLEDGE_BASE' && configuration.knowledgeBase" :column="2" size="small">
    <a-descriptions-item label="绑定快照">{{ configuration.knowledgeBase.indexSnapshotId || '未绑定' }}</a-descriptions-item>
    <a-descriptions-item label="快照状态">{{ selectedKnowledgeSnapshot?.status || '-' }}</a-descriptions-item>
    <a-descriptions-item label="文档数">{{ selectedKnowledgeSnapshot?.documentCount ?? '-' }}</a-descriptions-item>
    <a-descriptions-item label="Chunk 数">{{ selectedKnowledgeSnapshot?.chunkCount ?? '-' }}</a-descriptions-item>
    <a-descriptions-item label="构建时间">{{ formatDateTime(selectedKnowledgeSnapshot?.builtAt ?? null) }}</a-descriptions-item>
    <a-descriptions-item label="快照检索模式">{{ selectedKnowledgeSnapshot?.retrievalMode || '-' }}</a-descriptions-item>
    <a-descriptions-item label="默认召回数">{{ configuration.knowledgeBase.defaultTopK }}</a-descriptions-item>
    <a-descriptions-item label="检索模式">{{ configuration.knowledgeBase.retrievalMode }}</a-descriptions-item>
    <a-descriptions-item label="最低得分阈值">{{ configuration.knowledgeBase.minScore }}</a-descriptions-item>
  </a-descriptions>

  <a-descriptions v-else-if="resourceType === 'TOOL' && configuration.tool" :column="2" size="small">
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
    <a-descriptions-item label="Organization">{{ configuration.llmModel.organization }}</a-descriptions-item>
    <a-descriptions-item label="Project">{{ configuration.llmModel.project }}</a-descriptions-item>
    <a-descriptions-item label="Region">{{ configuration.llmModel.region }}</a-descriptions-item>
    <a-descriptions-item label="Temperature / Max Tokens">
      {{ configuration.llmModel.temperature }} / {{ configuration.llmModel.maxTokens }}
    </a-descriptions-item>
  </a-descriptions>

  <a-descriptions v-else-if="resourceType === 'SKILL' && configuration.skill" :column="1" size="small">
    <a-descriptions-item label="技能名称">{{ configuration.skill.skillName }}</a-descriptions-item>
    <a-descriptions-item label="技能描述">{{ configuration.skill.skillDesc }}</a-descriptions-item>
    <a-descriptions-item label="技能提示">{{ configuration.skill.skillPrompt }}</a-descriptions-item>
  </a-descriptions>
</template>
