<script setup lang="ts">
import { computed, watch } from 'vue';
import type { KnowledgeIndexSnapshot, ResourceType, ResourceVersionConfiguration } from '../types';

const props = withDefaults(defineProps<{
  resourceType: ResourceType;
  configuration: ResourceVersionConfiguration;
  knowledgeSnapshots?: KnowledgeIndexSnapshot[];
  snapshotBindingMode?: 'hidden' | 'select';
}>(), {
  knowledgeSnapshots: () => [],
  snapshotBindingMode: 'select',
});

const knowledgeBase = computed(() => props.configuration.knowledgeBase!);
const tool = computed(() => props.configuration.tool!);
const toolOperations = computed(() => tool.value.operations);
const llmModel = computed(() => props.configuration.llmModel!);
const skill = computed(() => props.configuration.skill!);
const readyKnowledgeSnapshots = computed(() => props.knowledgeSnapshots.filter((snapshot) => snapshot.status === 'READY'));
const selectedKnowledgeSnapshot = computed(() =>
  props.knowledgeSnapshots.find((snapshot) => snapshot.id === knowledgeBase.value?.indexSnapshotId) ?? null,
);
const snapshotSelectOptions = computed(() => readyKnowledgeSnapshots.value.map((snapshot) => ({
  label: `${snapshot.id} · 文档 ${snapshot.documentCount} · chunk ${snapshot.chunkCount}`,
  value: snapshot.id,
})));
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

function ensureConfigurationState(configuration: ResourceVersionConfiguration, resourceType: ResourceType) {
  if (resourceType === 'KNOWLEDGE_BASE' && configuration.knowledgeBase) {
    configuration.knowledgeBase.indexSnapshotId = configuration.knowledgeBase.indexSnapshotId || null;
    if (!['LEXICAL', 'VECTOR', 'HYBRID'].includes(configuration.knowledgeBase.retrievalMode)) {
      configuration.knowledgeBase.retrievalMode = 'HYBRID';
    }
    if (!configuration.knowledgeBase.defaultTopK || configuration.knowledgeBase.defaultTopK <= 0) {
      configuration.knowledgeBase.defaultTopK = 5;
    }
    if (configuration.knowledgeBase.minScore === undefined || configuration.knowledgeBase.minScore < 0) {
      configuration.knowledgeBase.minScore = 0.1;
    }
  }

  if (resourceType !== 'TOOL' || !configuration.tool) {
    return;
  }

  if (!configuration.tool.operations) {
    configuration.tool.operations = [];
  }
  if (!configuration.tool.providerType) {
    configuration.tool.providerType = 'HTTP';
  }
  if (!configuration.tool.authType) {
    configuration.tool.authType = 'SERVICE_ACCOUNT';
  }
  if (!configuration.tool.timeoutSeconds || configuration.tool.timeoutSeconds <= 0) {
    configuration.tool.timeoutSeconds = 15;
  }
  if (!configuration.tool.retryPolicy) {
    configuration.tool.retryPolicy = 'NONE';
  }
  if (!configuration.tool.http) {
    configuration.tool.http = {
      endpoint: 'https://tool-gateway.internal/new-tool',
      method: 'POST',
    };
  }
  if (!configuration.tool.mcp) {
    configuration.tool.mcp = {
      serverName: 'new-mcp-server',
      transport: 'STREAMABLE_HTTP',
      connectionUri: 'https://mcp-gateway.internal/new-server',
      namespace: 'default.namespace',
      heartbeatSeconds: 30,
      operationMappings: {},
    };
  }
  if (!configuration.tool.mcp.operationMappings) {
    configuration.tool.mcp.operationMappings = {};
  }
  for (const operation of configuration.tool.operations) {
    const operationName = operation.name?.trim();
    if (!operationName) {
      continue;
    }
    if (!configuration.tool.mcp.operationMappings[operationName]) {
      configuration.tool.mcp.operationMappings[operationName] = operationName;
    }
  }
}

watch(
  () => [props.resourceType, props.configuration] as const,
  ([resourceType, configuration]) => {
    ensureConfigurationState(configuration, resourceType);
  },
  { immediate: true, deep: true },
);

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

function formatDateTime(value: string | null): string {
  if (!value) {
    return '-';
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}
</script>

<template>
  <template v-if="resourceType === 'KNOWLEDGE_BASE' && configuration.knowledgeBase">
    <a-alert
      v-if="snapshotBindingMode === 'select'"
      type="info"
      show-icon
      style="margin-bottom: 16px"
      message="知识库版本按索引快照发布"
      description="这里只能选择 READY 快照，避免把未完成构建的内容误发布到运行时。"
    />
    <a-row :gutter="[16, 16]">
      <a-col v-if="snapshotBindingMode === 'select'" :span="24">
        <a-form-item label="绑定索引快照">
          <a-select
            v-model:value="knowledgeBase.indexSnapshotId"
            :options="snapshotSelectOptions"
            allow-clear
            placeholder="选择一个 READY 快照"
          />
        </a-form-item>
        <a-typography-text type="secondary">
          {{
            selectedKnowledgeSnapshot
              ? `当前绑定 ${selectedKnowledgeSnapshot.id} · 文档 ${selectedKnowledgeSnapshot.documentCount} · chunk ${selectedKnowledgeSnapshot.chunkCount} · 构建于 ${formatDateTime(selectedKnowledgeSnapshot.builtAt)}`
              : readyKnowledgeSnapshots.length
                ? '请选择一个 READY 快照。未绑定快照的知识库版本只能停留在草稿状态。'
                : '当前还没有 READY 快照，请先到内容工作台上传内容并生成快照。'
          }}
        </a-typography-text>
      </a-col>
      <a-col :span="12">
        <a-form-item label="默认召回数">
          <a-input-number v-model:value="knowledgeBase.defaultTopK" :min="1" style="width: 100%" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="检索模式">
          <a-select
            v-model:value="knowledgeBase.retrievalMode"
            :options="[
              { label: 'HYBRID', value: 'HYBRID' },
              { label: 'LEXICAL', value: 'LEXICAL' },
              { label: 'VECTOR', value: 'VECTOR' },
            ]"
          />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="最低得分阈值">
          <a-input-number v-model:value="knowledgeBase.minScore" :min="0" :step="0.1" style="width: 100%" />
        </a-form-item>
      </a-col>
    </a-row>
    <a-card v-if="selectedKnowledgeSnapshot" size="small" style="margin-top: 8px">
      <a-descriptions :column="2" size="small">
        <a-descriptions-item label="快照状态">{{ selectedKnowledgeSnapshot.status }}</a-descriptions-item>
        <a-descriptions-item label="检索后端">{{ selectedKnowledgeSnapshot.retrievalBackend }}</a-descriptions-item>
        <a-descriptions-item label="文档数">{{ selectedKnowledgeSnapshot.documentCount }}</a-descriptions-item>
        <a-descriptions-item label="Chunk 数">{{ selectedKnowledgeSnapshot.chunkCount }}</a-descriptions-item>
        <a-descriptions-item label="构建时间" :span="2">{{ formatDateTime(selectedKnowledgeSnapshot.builtAt) }}</a-descriptions-item>
      </a-descriptions>
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
        <a-form-item label="技能名称">
          <a-input v-model:value="skill.skillName" placeholder="例如：FAQ 技能" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="技能描述">
          <a-input v-model:value="skill.skillDesc" placeholder="一句话描述这个技能的用途" />
        </a-form-item>
      </a-col>
    </a-row>
    <a-form-item label="技能提示">
      <a-textarea v-model:value="skill.skillPrompt" :rows="8" />
    </a-form-item>
  </template>
</template>
