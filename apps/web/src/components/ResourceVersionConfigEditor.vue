<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  accountOptionsForConnector,
  createEmptyToolConnector,
  resetConnectorForDefinition,
  schemaDrivenUiSchemaWithFallback,
  syncOperationMappings,
  toolConnectorDefinitionOptions,
} from './toolConnectorDefinitionForms';
import SchemaDrivenForm from './SchemaDrivenForm.vue';
import { api } from '../services/api';
import type { IntegrationAccount, ResourceType, ResourceVersionConfiguration, ToolConnectorDefinition } from '../types';
import type { JsonObject } from './schemaDrivenForm';

const props = defineProps<{
  resourceType: ResourceType;
  configuration: ResourceVersionConfiguration;
  snapshotBindingMode?: 'hidden' | 'select';
}>();

const tool = computed(() => props.configuration.tool!);
const toolOperations = computed(() => tool.value.operations);
const connector = computed(() => tool.value.connector);
const toolConnectorDefinitions = ref<ToolConnectorDefinition[]>([]);
const connectorDefinition = computed(() => (
  toolConnectorDefinitions.value.find((definition) => definition.connectorType === connector.value?.connectorType) ?? null
));
const connectorOptions = computed(() => toolConnectorDefinitionOptions(toolConnectorDefinitions.value));
const connectorConfigSchema = computed(() => connectorDefinition.value?.configSchema ?? {});
const connectorConfigUiSchema = computed(() => schemaDrivenUiSchemaWithFallback(
  connectorDefinition.value?.configSchema,
  connectorDefinition.value?.configUiSchema,
  'Connector 配置 JSON',
));
const operationMappingSchema = computed(() => connectorDefinition.value?.operationMappingSchema ?? {});
const operationMappingUiSchema = computed(() => schemaDrivenUiSchemaWithFallback(
  connectorDefinition.value?.operationMappingSchema,
  connectorDefinition.value?.operationMappingUiSchema,
  '操作映射 JSON',
));
const llmModel = computed(() => props.configuration.llmModel!);
const skill = computed(() => props.configuration.skill!);
const isOpenAiCompatible = computed(() => llmModel.value?.providerType === 'OPENAI_COMPATIBLE');
const integrationAccounts = ref<IntegrationAccount[]>([]);
const definitionsLoading = ref(true);
const definitionLoadFailed = ref(false);
const accountOptions = computed(() => accountOptionsForConnector(integrationAccounts.value, connector.value?.connectorType));

function ensureConfigurationState(configuration: ResourceVersionConfiguration, resourceType: ResourceType) {
  if (resourceType === 'TOOL') {
    configuration.tool ??= {
      operations: [],
      connector: createEmptyToolConnector(),
    };
    configuration.tool.operations ??= [];
    configuration.tool.connector ??= createEmptyToolConnector();
    configuration.tool.connector.config ??= {};
    configuration.tool.connector.operationMappings ??= {};
    syncOperationMappings(configuration.tool.connector, configuration.tool.operations.map((operation) => operation.name));
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
  definitionsLoading.value = true;
  try {
    const [definitions, accounts] = await Promise.all([
      api.listToolConnectorDefinitions(),
      api.listIntegrationAccounts(),
    ]);
    toolConnectorDefinitions.value = definitions;
    integrationAccounts.value = accounts;
    if (props.resourceType === 'TOOL' && connector.value && !connector.value.connectorType && definitions[0]) {
      resetConnectorForDefinition(connector.value, definitions[0].connectorType, toolOperations.value.map((operation) => operation.name ?? ''));
    }
  } catch {
    definitionLoadFailed.value = true;
    toolConnectorDefinitions.value = [];
    integrationAccounts.value = [];
  } finally {
    definitionsLoading.value = false;
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
  const [removed] = toolOperations.value.splice(index, 1);
  const removedName = removed?.name?.trim();
  if (removedName && connector.value?.operationMappings) {
    delete connector.value.operationMappings[removedName];
  }
}

function onConnectorTypeChange(value: string) {
  if (!connector.value) {
    return;
  }
  resetConnectorForDefinition(connector.value, value, toolOperations.value.map((operation) => operation.name ?? ''));
}

function operationMapping(operationName: string): JsonObject {
  const normalizedName = operationName?.trim();
  if (!connector.value || !normalizedName) {
    return {};
  }
  connector.value.operationMappings[normalizedName] ??= {};
  return connector.value.operationMappings[normalizedName] as JsonObject;
}

function setOperationMapping(operationName: string, value: JsonObject) {
  const normalizedName = operationName?.trim();
  if (!connector.value || !normalizedName) {
    return;
  }
  connector.value.operationMappings[normalizedName] = value;
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
            :options="connectorOptions"
            :loading="definitionsLoading"
            @update:value="onConnectorTypeChange"
          />
        </a-form-item>
      </a-col>
      <a-col :span="12">
        <a-form-item label="Integration Account">
          <a-select
            v-model:value="connector.accountId"
            allow-clear
            :options="accountOptions"
            placeholder="选择已启用且匹配当前 Connector 的账号"
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

    <a-alert
      v-if="definitionLoadFailed"
      type="error"
      show-icon
      message="Connector definitions 加载失败"
      style="margin-bottom: 16px"
    />
    <a-alert
      v-else-if="!connectorDefinition"
      type="warning"
      show-icon
      message="请选择当前 registry 中的 Connector"
      description="已保存的 Connector 类型不在当前 definition endpoint 返回列表中。"
      style="margin-bottom: 16px"
    />
    <SchemaDrivenForm
      v-else
      v-model="connector.config"
      :schema="connectorConfigSchema"
      :ui-schema="connectorConfigUiSchema"
      mode="config"
    />

    <a-card size="small" title="操作映射">
      <a-empty v-if="!toolOperations.length" description="新增操作后配置 Connector 映射。" />
      <a-space v-else direction="vertical" style="width: 100%" size="middle">
        <a-row v-for="(operation, index) in toolOperations" :key="index" :gutter="[16, 16]">
          <a-col :span="24">
            <a-form-item label="操作">
              <a-input :value="operation.name" disabled />
            </a-form-item>
          </a-col>
          <a-col :span="24">
            <a-alert
              v-if="!operation.name?.trim()"
              type="warning"
              show-icon
              message="请先填写操作名，再配置映射。"
            />
            <SchemaDrivenForm
              v-else-if="connectorDefinition"
              :model-value="operationMapping(operation.name)"
              :schema="operationMappingSchema"
              :ui-schema="operationMappingUiSchema"
              mode="config"
              @update:model-value="(value) => setOperationMapping(operation.name, value)"
            />
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
