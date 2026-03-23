<script setup lang="ts">
import { computed } from 'vue';
import type { ResourceType, ResourceVersionConfiguration } from '../types';

const props = defineProps<{
  resourceType: ResourceType;
  configuration: ResourceVersionConfiguration;
}>();

const knowledgeBase = computed(() => props.configuration.knowledgeBase!);
const skill = computed(() => props.configuration.skill!);
const mcp = computed(() => props.configuration.mcp!);
const llmModel = computed(() => props.configuration.llmModel!);
const promptTemplate = computed(() => props.configuration.promptTemplate!);
const isOpenAiCompatible = computed(() => llmModel.value?.providerType === 'OPENAI_COMPATIBLE');
const llmModelIdPlaceholder = computed(() => (
  isOpenAiCompatible.value ? '例如：qwen2.5-72b-instruct / deepseek-chat' : '例如：gpt-4.1-mini'
));
const llmBaseUrlPlaceholder = computed(() => (
  isOpenAiCompatible.value ? '例如：http://localhost:11434/v1 或 https://your-gateway.example/v1' : '例如：https://api.openai.com/v1'
));
const llmApiKeyPlaceholder = computed(() => (
  isOpenAiCompatible.value ? '例如：OPENAI_COMPATIBLE_API_KEY' : '例如：OPENAI_API_KEY'
));
</script>

<template>
  <template v-if="resourceType === 'KNOWLEDGE_BASE'">
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="数据来源">
          <a-select
            v-model:value="knowledgeBase.sourceType"
            :options="[
              { label: '对象存储', value: 'OBJECT_STORAGE' },
              { label: 'Web 同步', value: 'WEB_SYNC' },
              { label: '手动导入', value: 'MANUAL_IMPORT' },
            ]"
          />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="同步方式">
          <a-select
            v-model:value="knowledgeBase.syncMode"
            :options="[
              { label: '手动', value: 'MANUAL' },
              { label: '定时同步', value: 'SCHEDULED' },
            ]"
          />
        </a-form-item>
      </a-col>
    </a-row>
    <a-form-item label="数据位置">
      <a-input v-model:value="knowledgeBase.sourceLocation" placeholder="例如：minio://knowledge/support-faq" />
    </a-form-item>
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="检索模式">
          <a-select
            v-model:value="knowledgeBase.retrievalMode"
            :options="[
              { label: '语义检索', value: 'SEMANTIC' },
              { label: '混合检索', value: 'HYBRID' },
              { label: '关键词检索', value: 'KEYWORD' },
            ]"
          />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="Embedding 模型">
          <a-input v-model:value="knowledgeBase.embeddingModel" />
        </a-form-item>
      </a-col>
    </a-row>
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="切片策略">
          <a-input v-model:value="knowledgeBase.chunkStrategy" />
        </a-form-item>
      </a-col>
      <a-col :span="6">
        <a-form-item label="默认召回数">
          <a-input-number v-model:value="knowledgeBase.defaultTopK" :min="1" style="width: 100%" />
        </a-form-item>
      </a-col>
      <a-col :span="6">
        <a-form-item label="文档规模">
          <a-input-number v-model:value="knowledgeBase.documentCount" :min="0" style="width: 100%" />
        </a-form-item>
      </a-col>
    </a-row>
  </template>

  <template v-else-if="resourceType === 'SKILL'">
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="运行方式">
          <a-select
            v-model:value="skill.runtime"
            :options="[
              { label: 'HTTP', value: 'HTTP' },
              { label: 'Workflow Activity', value: 'WORKFLOW_ACTIVITY' },
              { label: 'Function Call', value: 'FUNCTION_CALL' },
            ]"
          />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="请求方法">
          <a-select
            v-model:value="skill.method"
            :options="[
              { label: 'POST', value: 'POST' },
              { label: 'GET', value: 'GET' },
              { label: 'RPC', value: 'RPC' },
            ]"
          />
        </a-form-item>
      </a-col>
    </a-row>
    <a-form-item label="调用端点">
      <a-input v-model:value="skill.endpoint" placeholder="例如：https://skill-gateway.internal/answer" />
    </a-form-item>
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="鉴权方式">
          <a-select
            v-model:value="skill.authType"
            :options="[
              { label: '无鉴权', value: 'NONE' },
              { label: 'API Key', value: 'API_KEY' },
              { label: '服务账号', value: 'SERVICE_ACCOUNT' },
            ]"
          />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="超时秒数">
          <a-input-number v-model:value="skill.timeoutSeconds" :min="1" style="width: 100%" />
        </a-form-item>
      </a-col>
    </a-row>
    <a-form-item label="重试策略">
      <a-input v-model:value="skill.retryPolicy" />
    </a-form-item>
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="输入 Schema">
          <a-textarea v-model:value="skill.inputSchema" :rows="4" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="输出 Schema">
          <a-textarea v-model:value="skill.outputSchema" :rows="4" />
        </a-form-item>
      </a-col>
    </a-row>
  </template>

  <template v-else-if="resourceType === 'MCP'">
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="服务名称">
          <a-input v-model:value="mcp.serverName" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="传输协议">
          <a-select
            v-model:value="mcp.transport"
            :options="[
              { label: 'Streamable HTTP', value: 'STREAMABLE_HTTP' },
              { label: 'SSE', value: 'SSE' },
              { label: 'STDIO', value: 'STDIO' },
            ]"
          />
        </a-form-item>
      </a-col>
    </a-row>
    <a-form-item label="连接地址">
      <a-input v-model:value="mcp.connectionUri" placeholder="例如：https://mcp-gateway.internal/ticketing" />
    </a-form-item>
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="命名空间">
          <a-input v-model:value="mcp.namespace" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="鉴权方式">
          <a-select
            v-model:value="mcp.authType"
            :options="[
              { label: '无鉴权', value: 'NONE' },
              { label: 'API Key', value: 'API_KEY' },
              { label: 'OAuth', value: 'OAUTH' },
            ]"
          />
        </a-form-item>
      </a-col>
    </a-row>
    <a-row :gutter="[16, 16]">
      <a-col :span="8">
        <a-form-item label="心跳秒数">
          <a-input-number v-model:value="mcp.heartbeatSeconds" :min="5" style="width: 100%" />
        </a-form-item>
      </a-col>
      <a-col :span="16">
        <a-form-item label="暴露工具">
          <a-select v-model:value="mcp.exposedTools" mode="tags" style="width: 100%" placeholder="输入工具名后回车" />
        </a-form-item>
      </a-col>
    </a-row>
  </template>

  <template v-else-if="resourceType === 'LLM_MODEL'">
    <a-alert
      v-if="isOpenAiCompatible"
      type="info"
      show-icon
      style="margin-bottom: 16px"
      message="当前使用 OpenAI Compatible 模式"
      description="可接入兼容 OpenAI Chat Completions API 的私有化网关或第三方模型服务。Base URL 通常形如 http://localhost:11434/v1。"
    />
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="供应商类型">
          <a-select
            v-model:value="llmModel.providerType"
            :options="[
              { label: 'OpenAI', value: 'OPENAI' },
              { label: 'Anthropic', value: 'ANTHROPIC' },
              { label: 'Gemini', value: 'GEMINI' },
              { label: 'OpenAI Compatible', value: 'OPENAI_COMPATIBLE' },
            ]"
          />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="模型 ID">
          <a-input v-model:value="llmModel.modelId" :placeholder="llmModelIdPlaceholder" />
        </a-form-item>
      </a-col>
    </a-row>
    <a-form-item label="Base URL">
      <a-input v-model:value="llmModel.baseUrl" :placeholder="llmBaseUrlPlaceholder" />
    </a-form-item>
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="API Key 环境变量">
          <a-input v-model:value="llmModel.apiKeyEnvVar" :placeholder="llmApiKeyPlaceholder" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="组织 / 项目 / 区域">
          <a-input :value="`${llmModel.organization} / ${llmModel.project} / ${llmModel.region}`" disabled />
        </a-form-item>
      </a-col>
    </a-row>
    <a-row :gutter="[16, 16]">
      <a-col :span="8">
        <a-form-item label="Organization">
          <a-input v-model:value="llmModel.organization" />
        </a-form-item>
      </a-col>
      <a-col :span="8">
        <a-form-item label="Project">
          <a-input v-model:value="llmModel.project" />
        </a-form-item>
      </a-col>
      <a-col :span="8">
        <a-form-item label="Region">
          <a-input v-model:value="llmModel.region" />
        </a-form-item>
      </a-col>
    </a-row>
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="Temperature">
          <a-input-number v-model:value="llmModel.temperature" :min="0" :max="2" :step="0.1" style="width: 100%" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="Max Tokens">
          <a-input-number v-model:value="llmModel.maxTokens" :min="1" style="width: 100%" />
        </a-form-item>
      </a-col>
    </a-row>
  </template>

  <template v-else>
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="模板类型">
          <a-select
            v-model:value="promptTemplate.templateType"
            :options="[
              { label: 'Chat', value: 'CHAT' },
              { label: 'Router', value: 'ROUTER' },
              { label: 'Structured Output', value: 'STRUCTURED_OUTPUT' },
            ]"
          />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="响应格式">
          <a-input v-model:value="promptTemplate.responseFormat" />
        </a-form-item>
      </a-col>
    </a-row>
    <a-form-item label="System Prompt">
      <a-textarea v-model:value="promptTemplate.systemPrompt" :rows="5" />
    </a-form-item>
    <a-form-item label="User Prompt Template">
      <a-textarea v-model:value="promptTemplate.userPromptTemplate" :rows="6" />
    </a-form-item>
  </template>
</template>
