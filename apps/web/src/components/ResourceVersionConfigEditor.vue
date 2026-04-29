<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  createDefaultToolConnector,
  DEFAULT_TOOL_CONNECTOR_TYPE,
  defaultOperationMapping,
  toolConnectorDefinition,
  toolConnectorDescriptorId,
  toolConnectorOptions,
} from '../config/toolConnectors';
import { api } from '../services/api';
import type { ConnectorFieldDefinition } from '../config/toolConnectors';
import type { IntegrationAccount, ResourceType, ResourceVersionConfiguration, ToolConnectorType } from '../types';

const props = defineProps<{
  resourceType: ResourceType;
  configuration: ResourceVersionConfiguration;
  snapshotBindingMode?: 'hidden' | 'select';
}>();

const tool = computed(() => props.configuration.tool!);
const toolOperations = computed(() => tool.value.operations);
const connector = computed(() => tool.value.connector);
const connectorDefinition = computed(() => toolConnectorDefinition(connector.value?.connectorType ?? DEFAULT_TOOL_CONNECTOR_TYPE));
const connectorConfigFields = computed(() => connectorDefinition.value.configFields);
const operationMappingFields = computed(() => connectorDefinition.value.operationMappingFields);
const llmModel = computed(() => props.configuration.llmModel!);
const skill = computed(() => props.configuration.skill!);
const isOpenAiCompatible = computed(() => llmModel.value?.providerType === 'OPENAI_COMPATIBLE');
const integrationAccounts = ref<IntegrationAccount[]>([]);
const accountOptions = computed(() =>
  integrationAccounts.value
    .filter((account) => (
      account.subjectType === 'TOOL_CONNECTOR'
      && account.subjectId === toolConnectorDescriptorId(connector.value?.connectorType ?? DEFAULT_TOOL_CONNECTOR_TYPE)
      && account.status === 'ENABLED'
    ))
    .map((account) => ({
      label: account.credentialConfigured ? account.name : `${account.name}（${account.credentialStatus}）`,
      value: account.id,
    })),
);

function ensureConfigurationState(configuration: ResourceVersionConfiguration, resourceType: ResourceType) {
  if (resourceType === 'TOOL') {
    configuration.tool ??= {
      operations: [],
      connector: createDefaultToolConnector(),
    };
    configuration.tool.operations ??= [];
    configuration.tool.connector ??= createDefaultToolConnector();
    configuration.tool.connector.config ??= {};
    configuration.tool.connector.operationMappings ??= {};
    const definition = toolConnectorDefinition(configuration.tool.connector.connectorType);
    for (const field of definition.configFields) {
      configuration.tool.connector.config[field.key] ??= field.defaultValue;
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

function onConnectorTypeChange(value: ToolConnectorType) {
  if (!connector.value) {
    return;
  }
  connector.value.connectorType = value;
  const definition = toolConnectorDefinition(value);
  connector.value.accountId = definition.accountMode === 'NONE' ? null : connector.value.accountId;
  connector.value.config = Object.fromEntries(definition.configFields.map((field) => [field.key, field.defaultValue]));
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

function connectorFieldValue(field: ConnectorFieldDefinition): unknown {
  return connector.value?.config[field.key] ?? field.defaultValue;
}

function mappingFieldValue(operationName: string, field: ConnectorFieldDefinition): unknown {
  return operationMapping(operationName)[field.key] ?? (field.key === 'tool' ? operationName : field.defaultValue);
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
            :options="toolConnectorOptions"
            @update:value="onConnectorTypeChange"
          />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="Integration Account">
          <a-select
            v-model:value="connector.accountId"
            allow-clear
            :disabled="connectorDefinition.accountMode === 'NONE'"
            :options="accountOptions"
            :placeholder="connectorDefinition.accountPlaceholder"
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

    <a-row :gutter="[16, 16]">
      <a-col v-for="field in connectorConfigFields" :key="field.key" :span="field.span">
        <a-form-item :label="field.label">
          <a-select
            v-if="field.kind === 'select'"
            :value="String(connectorFieldValue(field))"
            :options="field.options"
            @update:value="(value: string) => setConnectorConfigField(field.key, value)"
          />
          <a-switch
            v-else-if="field.kind === 'switch'"
            :checked="Boolean(connectorFieldValue(field))"
            @update:checked="(value: boolean) => setConnectorConfigField(field.key, value)"
          />
          <a-input-number
            v-else-if="field.kind === 'number'"
            :value="Number(connectorFieldValue(field))"
            :min="1"
            style="width: 100%"
            @update:value="(value: number) => setConnectorConfigField(field.key, value)"
          />
          <a-input
            v-else
            :value="String(connectorFieldValue(field))"
            @update:value="(value: string) => setConnectorConfigField(field.key, value)"
          />
        </a-form-item>
      </a-col>
    </a-row>

    <a-card size="small" title="操作映射">
      <a-empty v-if="!toolOperations.length" description="新增操作后配置 Connector 映射。" />
      <a-space v-else direction="vertical" style="width: 100%" size="middle">
        <a-row v-for="operation in toolOperations" :key="operation.name || 'blank-operation'" :gutter="[16, 16]">
          <a-col :span="6">
            <a-form-item label="操作">
              <a-input :value="operation.name" disabled />
            </a-form-item>
          </a-col>
          <a-col v-for="field in operationMappingFields" :key="field.key" :span="field.span">
            <a-form-item :label="field.label">
              <a-select
                v-if="field.kind === 'select'"
                :value="String(mappingFieldValue(operation.name, field))"
                :options="field.options"
                @update:value="(value: string) => setOperationMappingField(operation.name, field.key, value)"
              />
              <a-input-number
                v-else-if="field.kind === 'number'"
                :value="Number(mappingFieldValue(operation.name, field))"
                :min="1"
                style="width: 100%"
                @update:value="(value: number) => setOperationMappingField(operation.name, field.key, value)"
              />
              <a-switch
                v-else-if="field.kind === 'switch'"
                :checked="Boolean(mappingFieldValue(operation.name, field))"
                @update:checked="(value: boolean) => setOperationMappingField(operation.name, field.key, value)"
              />
              <a-input
                v-else
                :value="String(mappingFieldValue(operation.name, field))"
                @update:value="(value: string) => setOperationMappingField(operation.name, field.key, value)"
              />
            </a-form-item>
          </a-col>
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
