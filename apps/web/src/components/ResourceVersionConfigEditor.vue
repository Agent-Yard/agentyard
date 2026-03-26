<script setup lang="ts">
import { computed, watch } from 'vue';
import type { ResourceType, ResourceVersionConfiguration } from '../types';

const props = defineProps<{
  resourceType: ResourceType;
  configuration: ResourceVersionConfiguration;
  snapshotBindingMode?: 'hidden' | 'select';
}>();

const tool = computed(() => props.configuration.tool!);
const toolOperations = computed(() => tool.value.operations);
const llmModel = computed(() => props.configuration.llmModel!);
const skill = computed(() => props.configuration.skill!);
const isOpenAiCompatible = computed(() => llmModel.value?.providerType === 'OPENAI_COMPATIBLE');

function ensureConfigurationState(configuration: ResourceVersionConfiguration, resourceType: ResourceType) {
  if (resourceType === 'TOOL') {
    configuration.tool ??= {
      operations: [],
      providerType: 'HTTP',
      authType: 'SERVICE_ACCOUNT',
      timeoutSeconds: 15,
      retryPolicy: 'NONE',
      http: {
        endpoint: 'https://tool-gateway.internal/new-tool',
        method: 'POST',
      },
      mcp: {
        serverName: 'new-mcp-server',
        transport: 'STREAMABLE_HTTP',
        connectionUri: 'https://mcp-gateway.internal/new-server',
        namespace: 'default.namespace',
        heartbeatSeconds: 30,
        operationMappings: {},
      },
    };
    configuration.tool.operations ??= [];
    configuration.tool.mcp ??= {
      serverName: 'new-mcp-server',
      transport: 'STREAMABLE_HTTP',
      connectionUri: 'https://mcp-gateway.internal/new-server',
      namespace: 'default.namespace',
      heartbeatSeconds: 30,
      operationMappings: {},
    };
    configuration.tool.http ??= {
      endpoint: 'https://tool-gateway.internal/new-tool',
      method: 'POST',
    };
    configuration.tool.mcp.operationMappings ??= {};
    for (const operation of configuration.tool.operations) {
      const operationName = operation.name?.trim();
      if (operationName && !configuration.tool.mcp.operationMappings[operationName]) {
        configuration.tool.mcp.operationMappings[operationName] = operationName;
      }
    }
    return;
  }

  if (resourceType === 'LLM_MODEL') {
    configuration.llmModel ??= {
      providerType: 'OPENAI_COMPATIBLE',
      modelId: 'custom-compatible-model',
      baseUrl: 'http://localhost:11434/v1',
      apiKeyEnvVar: 'OPENAI_COMPATIBLE_API_KEY',
      organization: 'compatible-lab',
      project: 'default-project',
      region: 'local',
      temperature: 0.2,
      maxTokens: 1200,
    };
    return;
  }

  configuration.skill ??= {
    skillName: '新技能',
    skillDesc: '请填写技能用途说明。',
    skillPrompt: '请填写技能行为说明。',
  };
}

watch(
  () => [props.resourceType, props.configuration] as const,
  ([resourceType, configuration]) => ensureConfigurationState(configuration, resourceType),
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
</script>

<template>
  <template v-if="resourceType === 'TOOL'">
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
                <a-input v-model:value="operation.description" placeholder="描述动作职责" />
              </a-form-item>
            </a-col>
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

    <a-divider>Provider 配置</a-divider>

    <template v-if="tool.providerType === 'HTTP'">
      <a-row :gutter="[16, 16]">
        <a-col :span="16">
          <a-form-item label="HTTP Endpoint">
            <a-input v-model:value="tool.http!.endpoint" />
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item label="Method">
            <a-select
              v-model:value="tool.http!.method"
              :options="['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((item) => ({ label: item, value: item }))"
            />
          </a-form-item>
        </a-col>
      </a-row>
    </template>

    <template v-else>
      <a-row :gutter="[16, 16]">
        <a-col :span="12">
          <a-form-item label="MCP 服务名">
            <a-input v-model:value="tool.mcp!.serverName" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="Transport">
            <a-select
              v-model:value="tool.mcp!.transport"
              :options="[
                { label: 'STREAMABLE_HTTP', value: 'STREAMABLE_HTTP' },
                { label: 'SSE', value: 'SSE' },
              ]"
            />
          </a-form-item>
        </a-col>
      </a-row>
      <a-row :gutter="[16, 16]">
        <a-col :span="16">
          <a-form-item label="连接地址">
            <a-input v-model:value="tool.mcp!.connectionUri" />
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item label="命名空间">
            <a-input v-model:value="tool.mcp!.namespace" />
          </a-form-item>
        </a-col>
      </a-row>
    </template>
  </template>

  <template v-else-if="resourceType === 'LLM_MODEL'">
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="供应商类型">
          <a-select
            v-model:value="llmModel.providerType"
            :options="[
              { label: 'OPENAI', value: 'OPENAI' },
              { label: 'OPENAI_COMPATIBLE', value: 'OPENAI_COMPATIBLE' },
            ]"
          />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="模型 ID">
          <a-input
            v-model:value="llmModel.modelId"
            :placeholder="isOpenAiCompatible ? '例如：qwen2.5-72b-instruct' : '例如：gpt-4.1-mini'"
          />
        </a-form-item>
      </a-col>
      <a-col :span="24">
        <a-form-item label="Base URL">
          <a-input
            v-model:value="llmModel.baseUrl"
            :placeholder="isOpenAiCompatible ? '例如：http://localhost:11434/v1' : '例如：https://api.openai.com/v1'"
          />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="API Key 环境变量">
          <a-input
            v-model:value="llmModel.apiKeyEnvVar"
            :placeholder="isOpenAiCompatible ? '例如：OPENAI_COMPATIBLE_API_KEY' : '例如：OPENAI_API_KEY'"
          />
        </a-form-item>
      </a-col>
      <a-col :span="12">
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
      <a-col :span="4">
        <a-form-item label="Temperature">
          <a-input-number v-model:value="llmModel.temperature" :min="0" :max="2" :step="0.1" style="width: 100%" />
        </a-form-item>
      </a-col>
      <a-col :span="4">
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
          <a-input v-model:value="skill.skillName" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="技能描述">
          <a-input v-model:value="skill.skillDesc" />
        </a-form-item>
      </a-col>
      <a-col :span="24">
        <a-form-item label="技能提示">
          <a-textarea v-model:value="skill.skillPrompt" :rows="6" />
        </a-form-item>
      </a-col>
    </a-row>
  </template>
</template>
