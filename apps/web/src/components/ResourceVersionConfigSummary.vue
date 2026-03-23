<script setup lang="ts">
import type { ResourceType, ResourceVersionConfiguration } from '../types';

defineProps<{
  resourceType: ResourceType;
  configuration: ResourceVersionConfiguration;
}>();
</script>

<template>
  <a-descriptions v-if="resourceType === 'KNOWLEDGE_BASE' && configuration.knowledgeBase" :column="2" size="small">
    <a-descriptions-item label="数据来源">{{ configuration.knowledgeBase.sourceType }}</a-descriptions-item>
    <a-descriptions-item label="同步方式">{{ configuration.knowledgeBase.syncMode }}</a-descriptions-item>
    <a-descriptions-item label="数据位置">{{ configuration.knowledgeBase.sourceLocation }}</a-descriptions-item>
    <a-descriptions-item label="检索模式">{{ configuration.knowledgeBase.retrievalMode }}</a-descriptions-item>
    <a-descriptions-item label="Embedding">{{ configuration.knowledgeBase.embeddingModel }}</a-descriptions-item>
    <a-descriptions-item label="切片策略">{{ configuration.knowledgeBase.chunkStrategy }}</a-descriptions-item>
    <a-descriptions-item label="默认召回数">{{ configuration.knowledgeBase.defaultTopK }}</a-descriptions-item>
    <a-descriptions-item label="文档规模">{{ configuration.knowledgeBase.documentCount }}</a-descriptions-item>
  </a-descriptions>

  <a-descriptions v-else-if="resourceType === 'SKILL' && configuration.skill" :column="2" size="small">
    <a-descriptions-item label="运行方式">{{ configuration.skill.runtime }}</a-descriptions-item>
    <a-descriptions-item label="请求方法">{{ configuration.skill.method }}</a-descriptions-item>
    <a-descriptions-item label="调用端点">{{ configuration.skill.endpoint }}</a-descriptions-item>
    <a-descriptions-item label="鉴权方式">{{ configuration.skill.authType }}</a-descriptions-item>
    <a-descriptions-item label="超时秒数">{{ configuration.skill.timeoutSeconds }}</a-descriptions-item>
    <a-descriptions-item label="重试策略">{{ configuration.skill.retryPolicy }}</a-descriptions-item>
    <a-descriptions-item label="输入 Schema">{{ configuration.skill.inputSchema }}</a-descriptions-item>
    <a-descriptions-item label="输出 Schema">{{ configuration.skill.outputSchema }}</a-descriptions-item>
  </a-descriptions>

  <a-descriptions v-else-if="resourceType === 'MCP' && configuration.mcp" :column="2" size="small">
    <a-descriptions-item label="服务名称">{{ configuration.mcp.serverName }}</a-descriptions-item>
    <a-descriptions-item label="传输协议">{{ configuration.mcp.transport }}</a-descriptions-item>
    <a-descriptions-item label="连接地址">{{ configuration.mcp.connectionUri }}</a-descriptions-item>
    <a-descriptions-item label="命名空间">{{ configuration.mcp.namespace }}</a-descriptions-item>
    <a-descriptions-item label="鉴权方式">{{ configuration.mcp.authType }}</a-descriptions-item>
    <a-descriptions-item label="心跳秒数">{{ configuration.mcp.heartbeatSeconds }}</a-descriptions-item>
    <a-descriptions-item label="暴露工具">{{ configuration.mcp.exposedTools.join(' / ') }}</a-descriptions-item>
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

  <a-descriptions v-else-if="resourceType === 'PROMPT_TEMPLATE' && configuration.promptTemplate" :column="1" size="small">
    <a-descriptions-item label="模板类型">{{ configuration.promptTemplate.templateType }}</a-descriptions-item>
    <a-descriptions-item label="响应格式">{{ configuration.promptTemplate.responseFormat }}</a-descriptions-item>
    <a-descriptions-item label="System Prompt">{{ configuration.promptTemplate.systemPrompt }}</a-descriptions-item>
    <a-descriptions-item label="User Prompt Template">{{ configuration.promptTemplate.userPromptTemplate }}</a-descriptions-item>
  </a-descriptions>
</template>
