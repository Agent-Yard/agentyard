<script setup lang="ts">
import { computed } from 'vue';
import type { ResourceType, ResourceVersionConfiguration } from '../types';

const props = defineProps<{
  resourceType: ResourceType;
  configuration: ResourceVersionConfiguration;
}>();

const knowledgeBase = computed(() => props.configuration.knowledgeBase!);
if (props.resourceType === 'KNOWLEDGE_BASE' && props.configuration.knowledgeBase && !props.configuration.knowledgeBase.documents) {
  props.configuration.knowledgeBase.documents = [];
}
const knowledgeDocuments = computed(() => knowledgeBase.value.documents);
const knowledgeDocumentCount = computed(() => knowledgeDocuments.value.filter((document) => document.content.trim()).length);
const tool = computed(() => props.configuration.tool!);
if (props.resourceType === 'TOOL' && props.configuration.tool && !props.configuration.tool.operations) {
  props.configuration.tool.operations = [];
}
if (props.resourceType === 'TOOL' && props.configuration.tool && !props.configuration.tool.http) {
  props.configuration.tool.http = {
    endpoint: 'https://tool-gateway.internal/new-tool',
    method: 'POST',
  };
}
if (props.resourceType === 'TOOL' && props.configuration.tool && !props.configuration.tool.mcp) {
  props.configuration.tool.mcp = {
    serverName: 'new-mcp-server',
    transport: 'STREAMABLE_HTTP',
    connectionUri: 'https://mcp-gateway.internal/new-server',
    namespace: 'default.namespace',
    heartbeatSeconds: 30,
    operationMappings: {},
  };
}
const toolOperations = computed(() => tool.value.operations);
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

function addKnowledgeDocument() {
  knowledgeDocuments.value.push({
    id: `kb-doc-${Math.random().toString(16).slice(2, 10)}`,
    title: '',
    content: '',
    sourceUri: '',
  });
}

function removeKnowledgeDocument(index: number) {
  knowledgeDocuments.value.splice(index, 1);
}

function addToolOperation() {
  toolOperations.value.push({
    name: '',
    description: '',
    inputSchema: '',
    outputSchema: '',
  });
}

function removeToolOperation(index: number) {
  toolOperations.value.splice(index, 1);
}
</script>

<template>
  <template v-if="resourceType === 'KNOWLEDGE_BASE'">
    <a-alert
      type="info"
      show-icon
      style="margin-bottom: 16px"
      message="当前知识库按已发布文档集合直接检索"
      description="版本里维护的文档内容会进入发布快照并被运行时直接使用。"
    />
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="默认召回数">
          <a-input-number v-model:value="knowledgeBase.defaultTopK" :min="1" style="width: 100%" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="文档规模">
          <a-input-number :value="knowledgeDocumentCount" :min="0" style="width: 100%" disabled />
        </a-form-item>
      </a-col>
    </a-row>

    <a-card size="small" title="导入文档">
      <template #extra>
        <a-button size="small" type="primary" ghost @click="addKnowledgeDocument">新增文档</a-button>
      </template>
      <a-empty v-if="!knowledgeDocuments.length" description="还没有导入文档，发布前请至少补充一条知识内容。" />
      <a-space v-else direction="vertical" style="width: 100%" size="middle">
        <a-card v-for="(document, index) in knowledgeDocuments" :key="document.id || index" size="small">
          <template #title>文档 {{ index + 1 }}</template>
          <template #extra>
            <a-button danger size="small" @click="removeKnowledgeDocument(index)">删除</a-button>
          </template>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="标题">
                <a-input v-model:value="document.title" placeholder="例如：退款处理规则" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="来源 URI">
                <a-input v-model:value="document.sourceUri" placeholder="例如：manual://kb/refund-policy" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="正文">
            <a-textarea
              v-model:value="document.content"
              :rows="6"
              placeholder="输入可被检索与召回的知识正文，建议一条文档聚焦一个主题。"
            />
          </a-form-item>
        </a-card>
      </a-space>
    </a-card>
  </template>

  <template v-else-if="resourceType === 'TOOL'">
    <a-alert
      type="info"
      show-icon
      style="margin-bottom: 16px"
      message="Tool 负责定义能力契约，Provider 负责定义接入方式"
      description="建议先稳定操作名和输入输出 Schema，再补齐 HTTP 或 MCP provider 的连接信息。"
    />
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="Provider 类型">
          <a-select
            v-model:value="tool.providerType"
            :options="[
              { label: 'HTTP', value: 'HTTP' },
              { label: 'MCP', value: 'MCP' },
            ]"
          />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="鉴权方式">
          <a-select
            v-model:value="tool.authType"
            :options="[
              { label: '无鉴权', value: 'NONE' },
              { label: 'API Key', value: 'API_KEY' },
              { label: '服务账号', value: 'SERVICE_ACCOUNT' },
              { label: 'OAuth', value: 'OAUTH' },
            ]"
          />
        </a-form-item>
      </a-col>
    </a-row>
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="超时秒数">
          <a-input-number v-model:value="tool.timeoutSeconds" :min="1" style="width: 100%" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="重试策略">
          <a-input v-model:value="tool.retryPolicy" placeholder="例如：NONE / EXPONENTIAL_BACKOFF" />
        </a-form-item>
      </a-col>
    </a-row>

    <a-card size="small" title="操作定义">
      <template #extra>
        <a-button size="small" type="primary" ghost @click="addToolOperation">新增操作</a-button>
      </template>
      <a-empty v-if="!toolOperations.length" description="至少定义一个操作，智能体才能理解这个 Tool 可做什么。" />
      <a-space v-else direction="vertical" style="width: 100%" size="middle">
        <a-card v-for="(operation, index) in toolOperations" :key="index" size="small">
          <template #title>操作 {{ index + 1 }}</template>
          <template #extra>
            <a-button danger size="small" @click="removeToolOperation(index)">删除</a-button>
          </template>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="操作名">
                <a-input v-model:value="operation.name" placeholder="例如：evaluate_refund / create_ticket" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="说明">
                <a-input v-model:value="operation.description" placeholder="描述业务动作，不要写成协议细节" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="输入 Schema">
                <a-textarea v-model:value="operation.inputSchema" :rows="4" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="输出 Schema">
                <a-textarea v-model:value="operation.outputSchema" :rows="4" />
              </a-form-item>
            </a-col>
          </a-row>
        </a-card>
      </a-space>
    </a-card>

    <a-card v-if="tool.providerType === 'HTTP' && tool.http" size="small" title="HTTP Provider" style="margin-top: 16px">
      <a-row :gutter="[16, 16]">
        <a-col :span="12">
          <a-form-item label="请求方法">
            <a-select
              v-model:value="tool.http.method"
              :options="[
                { label: 'POST', value: 'POST' },
                { label: 'GET', value: 'GET' },
                { label: 'PUT', value: 'PUT' },
                { label: 'PATCH', value: 'PATCH' },
                { label: 'DELETE', value: 'DELETE' },
                { label: 'RPC', value: 'RPC' },
              ]"
            />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="调用端点">
            <a-input v-model:value="tool.http.endpoint" placeholder="例如：https://tool-gateway.internal/refund-policy" />
          </a-form-item>
        </a-col>
      </a-row>
    </a-card>

    <a-card v-else-if="tool.providerType === 'MCP' && tool.mcp" size="small" title="MCP Provider" style="margin-top: 16px">
      <a-row :gutter="[16, 16]">
        <a-col :span="12">
          <a-form-item label="服务名称">
            <a-input v-model:value="tool.mcp.serverName" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="传输协议">
            <a-select
              v-model:value="tool.mcp.transport"
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
        <a-input v-model:value="tool.mcp.connectionUri" placeholder="例如：https://mcp-gateway.internal/ticketing" />
      </a-form-item>
      <a-row :gutter="[16, 16]">
        <a-col :span="12">
          <a-form-item label="命名空间">
            <a-input v-model:value="tool.mcp.namespace" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="心跳秒数">
            <a-input-number v-model:value="tool.mcp.heartbeatSeconds" :min="5" style="width: 100%" />
          </a-form-item>
        </a-col>
      </a-row>
      <a-divider orientation="left">操作映射</a-divider>
      <a-space direction="vertical" style="width: 100%" size="middle">
        <a-row v-for="(operation, index) in toolOperations" :key="`${operation.name}-${index}`" :gutter="[16, 16]">
          <a-col :span="10">
            <a-form-item :label="`Tool 操作 ${index + 1}`">
              <a-input :value="operation.name" disabled />
            </a-form-item>
          </a-col>
          <a-col :span="14">
            <a-form-item label="MCP Tool 名">
              <a-input
                v-model:value="tool.mcp.operationMappings[operation.name]"
                :placeholder="operation.name || '请输入远端工具名'"
              />
            </a-form-item>
          </a-col>
        </a-row>
      </a-space>
    </a-card>
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
