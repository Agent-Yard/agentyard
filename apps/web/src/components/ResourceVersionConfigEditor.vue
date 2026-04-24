<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { api } from '../services/api';
import type { IntegrationAccount, ResourceType, ResourceVersionConfiguration, ToolConnectorType } from '../types';

const props = defineProps<{
  resourceType: ResourceType;
  configuration: ResourceVersionConfiguration;
  snapshotBindingMode?: 'hidden' | 'select';
}>();

const tool = computed(() => props.configuration.tool!);
const toolOperations = computed(() => tool.value.operations);
const connector = computed(() => tool.value.connector);
const llmModel = computed(() => props.configuration.llmModel!);
const skill = computed(() => props.configuration.skill!);
const isOpenAiCompatible = computed(() => llmModel.value?.providerType === 'OPENAI_COMPATIBLE');
const integrationAccounts = ref<IntegrationAccount[]>([]);
const accountOptions = computed(() =>
  integrationAccounts.value
    .filter((account) => account.connectorType === connector.value?.connectorType && account.status === 'ACTIVE')
    .map((account) => ({
      label: account.credentialConfigured ? account.name : `${account.name}（未配置凭证）`,
      value: account.id,
    })),
);

function ensureConfigurationState(configuration: ResourceVersionConfiguration, resourceType: ResourceType) {
  if (resourceType === 'TOOL') {
    configuration.tool ??= {
      operations: [],
      connector: {
        connectorType: 'SIMPLE_HTTP',
        accountId: null,
        timeoutSeconds: 15,
        retryPolicy: 'NONE',
        config: {
          baseUrl: 'https://tool-gateway.internal',
        },
        operationMappings: {},
      },
    };
    configuration.tool.operations ??= [];
    configuration.tool.connector ??= {
      connectorType: 'SIMPLE_HTTP',
      accountId: null,
      timeoutSeconds: 15,
      retryPolicy: 'NONE',
      config: {
        baseUrl: 'https://tool-gateway.internal',
      },
      operationMappings: {},
    };
    configuration.tool.connector.config ??= {};
    configuration.tool.connector.operationMappings ??= {};
    if (configuration.tool.connector.connectorType === 'MCP') {
      configuration.tool.connector.config.internalAuthEnabled ??= false;
    }
    for (const operation of configuration.tool.operations) {
      const operationName = operation.name?.trim();
      if (operationName && !configuration.tool.connector.operationMappings[operationName]) {
        configuration.tool.connector.operationMappings[operationName] = defaultOperationMapping(configuration.tool.connector.connectorType, operationName);
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
      temperature: 0.2,
      maxTokens: 1200,
      privateDeployment: false,
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

onMounted(async () => {
  try {
    integrationAccounts.value = await api.listIntegrationAccounts();
  } catch {
    integrationAccounts.value = [];
  }
});

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

function defaultOperationMapping(connectorType: ToolConnectorType, operationName: string): Record<string, unknown> {
  if (connectorType === 'MCP') {
    return { tool: operationName };
  }
  return {
    method: 'POST',
    path: `/tools/${operationName || 'invoke'}`,
    requestPlacement: 'JSON_BODY',
  };
}

function onConnectorTypeChange(value: ToolConnectorType) {
  if (!connector.value) {
    return;
  }
  connector.value.connectorType = value;
  connector.value.accountId = value === 'MCP' ? null : connector.value.accountId;
  connector.value.config = value === 'MCP'
    ? {
        serverName: 'new-mcp-server',
        transport: 'STREAMABLE_HTTP',
        connectionUri: 'https://mcp-gateway.internal/new-server',
        namespace: 'default.namespace',
        heartbeatSeconds: 30,
        internalAuthEnabled: false,
      }
    : {
        baseUrl: 'https://tool-gateway.internal',
      };
  connector.value.operationMappings = Object.fromEntries(
    toolOperations.value
      .map((operation) => operation.name?.trim())
      .filter(Boolean)
      .map((operationName) => [operationName, defaultOperationMapping(value, operationName!)]),
  );
}

function operationMapping(operationName: string) {
  const normalizedName = operationName?.trim();
  if (!connector.value || !normalizedName) {
    return {};
  }
  connector.value.operationMappings[normalizedName] ??= defaultOperationMapping(connector.value.connectorType, normalizedName);
  return connector.value.operationMappings[normalizedName];
}

function setOperationMappingField(operationName: string, key: string, value: unknown) {
  const normalizedName = operationName?.trim();
  if (!connector.value || !normalizedName) {
    return;
  }
  const mapping = operationMapping(normalizedName);
  mapping[key] = value;
}

function setConnectorConfigField(key: string, value: unknown) {
  if (!connector.value) {
    return;
  }
  connector.value.config[key] = value;
}
</script>

<template>
  <template v-if="resourceType === 'TOOL'">
    <a-alert
      type="info"
      show-icon
      style="margin-bottom: 16px"
      message="Tool 负责定义能力契约，Connector 负责定义接入方式"
      description="建议先稳定操作名和输入输出 Schema，再补齐 Connector 的账号、连接信息和操作映射。"
    />
    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="Connector 类型">
          <a-select
            :value="connector.connectorType"
            :options="[
              { label: 'Simple HTTP', value: 'SIMPLE_HTTP' },
              { label: 'Business Code Secret HTTP', value: 'BUSINESS_CODE_SECRET_HTTP' },
              { label: 'MCP', value: 'MCP' },
            ]"
            @update:value="onConnectorTypeChange"
          />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="Integration Account">
          <a-select
            v-model:value="connector.accountId"
            allow-clear
            :disabled="connector.connectorType === 'MCP'"
            :options="accountOptions"
            :placeholder="connector.connectorType === 'SIMPLE_HTTP' ? '可选 Bearer Token 账号' : '选择账号'"
          />
        </a-form-item>
      </a-col>
    </a-row>

    <a-row :gutter="[16, 16]">
      <a-col :span="12">
        <a-form-item label="超时秒数">
          <a-input-number v-model:value="connector.timeoutSeconds" :min="1" style="width: 100%" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="重试策略">
          <a-input v-model:value="connector.retryPolicy" placeholder="例如：NONE / EXPONENTIAL_BACKOFF" />
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

    <a-divider>Connector 配置</a-divider>

    <template v-if="connector.connectorType !== 'MCP'">
      <a-row :gutter="[16, 16]">
        <a-col :span="12">
          <a-form-item label="Base URL">
            <a-input
              :value="String(connector.config.baseUrl ?? '')"
              @update:value="(value: string) => setConnectorConfigField('baseUrl', value)"
            />
          </a-form-item>
        </a-col>
        <a-col v-if="connector.connectorType === 'SIMPLE_HTTP'" :span="12">
          <a-form-item label="Bearer Header">
            <a-input
              :value="String(connector.config.authorizationHeader ?? 'Authorization')"
              @update:value="(value: string) => setConnectorConfigField('authorizationHeader', value)"
            />
          </a-form-item>
        </a-col>
        <a-col v-if="connector.connectorType === 'BUSINESS_CODE_SECRET_HTTP'" :span="6">
          <a-form-item label="Business Code 字段">
            <a-input
              :value="String(connector.config.businessCodeField ?? 'businessCode')"
              @update:value="(value: string) => setConnectorConfigField('businessCodeField', value)"
            />
          </a-form-item>
        </a-col>
        <a-col v-if="connector.connectorType === 'BUSINESS_CODE_SECRET_HTTP'" :span="6">
          <a-form-item label="Encrypted 字段">
            <a-input
              :value="String(connector.config.encryptedField ?? 'encrypted')"
              @update:value="(value: string) => setConnectorConfigField('encryptedField', value)"
            />
          </a-form-item>
        </a-col>
      </a-row>
    </template>

    <template v-else>
      <a-row :gutter="[16, 16]">
        <a-col :span="12">
          <a-form-item label="MCP 服务名">
            <a-input
              :value="String(connector.config.serverName ?? '')"
              @update:value="(value: string) => setConnectorConfigField('serverName', value)"
            />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="Transport">
            <a-select
              :value="String(connector.config.transport ?? 'STREAMABLE_HTTP')"
              :options="[
                { label: 'STREAMABLE_HTTP', value: 'STREAMABLE_HTTP' },
                { label: 'SSE', value: 'SSE' },
              ]"
              @update:value="(value: string) => setConnectorConfigField('transport', value)"
            />
          </a-form-item>
        </a-col>
      </a-row>
      <a-row :gutter="[16, 16]">
        <a-col :span="16">
          <a-form-item label="连接地址">
            <a-input
              :value="String(connector.config.connectionUri ?? '')"
              @update:value="(value: string) => setConnectorConfigField('connectionUri', value)"
            />
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item label="命名空间">
            <a-input
              :value="String(connector.config.namespace ?? '')"
              @update:value="(value: string) => setConnectorConfigField('namespace', value)"
            />
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item label="内部鉴权">
            <a-switch
              :checked="Boolean(connector.config.internalAuthEnabled)"
              @update:checked="(value: boolean) => setConnectorConfigField('internalAuthEnabled', value)"
            />
          </a-form-item>
        </a-col>
      </a-row>
    </template>

    <a-card size="small" title="操作映射">
      <a-empty v-if="!toolOperations.length" description="新增操作后配置 Connector 映射。" />
      <a-space v-else direction="vertical" style="width: 100%" size="middle">
        <a-row v-for="operation in toolOperations" :key="operation.name || 'blank-operation'" :gutter="[16, 16]">
          <a-col :span="6">
            <a-form-item label="操作">
              <a-input :value="operation.name" disabled />
            </a-form-item>
          </a-col>
          <template v-if="connector.connectorType === 'MCP'">
            <a-col :span="18">
              <a-form-item label="Remote Tool">
                <a-input
                  :value="String(operationMapping(operation.name).tool ?? operation.name)"
                  @update:value="(value: string) => setOperationMappingField(operation.name, 'tool', value)"
                />
              </a-form-item>
            </a-col>
          </template>
          <template v-else>
            <a-col :span="5">
              <a-form-item label="Method">
                <a-select
                  :value="String(operationMapping(operation.name).method ?? 'POST')"
                  :options="['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((item) => ({ label: item, value: item }))"
                  @update:value="(value: string) => setOperationMappingField(operation.name, 'method', value)"
                />
              </a-form-item>
            </a-col>
            <a-col :span="8">
              <a-form-item label="Path">
                <a-input
                  :value="String(operationMapping(operation.name).path ?? '')"
                  @update:value="(value: string) => setOperationMappingField(operation.name, 'path', value)"
                />
              </a-form-item>
            </a-col>
            <a-col :span="5">
              <a-form-item label="入参位置">
                <a-select
                  :value="String(operationMapping(operation.name).requestPlacement ?? 'JSON_BODY')"
                  :options="[
                    { label: 'JSON_BODY', value: 'JSON_BODY' },
                    { label: 'QUERY', value: 'QUERY' },
                  ]"
                  @update:value="(value: string) => setOperationMappingField(operation.name, 'requestPlacement', value)"
                />
              </a-form-item>
            </a-col>
          </template>
        </a-row>
      </a-space>
    </a-card>
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
        <a-form-item label="Temperature">
          <a-input-number v-model:value="llmModel.temperature" :min="0" :max="2" :step="0.1" style="width: 100%" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="Max Tokens">
          <a-input-number v-model:value="llmModel.maxTokens" :min="1" style="width: 100%" />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="Private Deployment">
          <a-switch v-model:checked="llmModel.privateDeployment" />
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
