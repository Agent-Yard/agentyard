<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import ResourceVersionConfigEditor from '../components/ResourceVersionConfigEditor.vue';
import type {
  Assistant,
  BusinessDomain,
  CreateResourcePayload,
  ResourceBlueprint,
  ResourceType,
} from '../types';

const props = defineProps<{
  domains: BusinessDomain[];
  assistants: Assistant[];
  resourceBlueprints: ResourceBlueprint[];
}>();

const emit = defineEmits<{
  createResource: [payload: CreateResourcePayload];
}>();

const createForm = reactive<CreateResourcePayload>({
  domainId: '',
  name: '',
  type: 'KNOWLEDGE_BASE',
  shareScope: 'DOMAIN_SHARED',
  ownerType: 'DOMAIN',
  ownerId: '',
  summary: '',
  steward: '',
  tags: [],
  initialVersion: {
    summary: '初始版本',
    configDigest: '',
    status: 'DRAFT',
    configuration: {
      type: 'KNOWLEDGE_BASE',
      knowledgeBase: {
        sourceType: 'OBJECT_STORAGE',
        sourceLocation: 'minio://knowledge/new-resource',
        syncMode: 'MANUAL',
        retrievalMode: 'HYBRID',
        embeddingModel: 'text-embedding-3-large',
        chunkStrategy: 'markdown-512-overlap-80',
        defaultTopK: 5,
        documentCount: 0,
      },
    },
  },
});

const currentBlueprint = computed(() =>
  props.resourceBlueprints.find((item) => item.type === createForm.type) ?? props.resourceBlueprints[0],
);

function defaultConfiguration(type: ResourceType) {
  const blueprint = props.resourceBlueprints.find((item) => item.type === type);
  if (blueprint) {
    return JSON.parse(JSON.stringify(blueprint.defaultConfiguration));
  }
  if (type === 'KNOWLEDGE_BASE') {
    return {
      type,
      knowledgeBase: {
        sourceType: 'OBJECT_STORAGE',
        sourceLocation: 'minio://knowledge/new-resource',
        syncMode: 'MANUAL',
        retrievalMode: 'HYBRID',
        embeddingModel: 'text-embedding-3-large',
        chunkStrategy: 'markdown-512-overlap-80',
        defaultTopK: 5,
        documentCount: 0,
      },
    };
  }
  if (type === 'SKILL') {
    return {
      type,
      skill: {
        runtime: 'HTTP',
        endpoint: 'https://skill-gateway.internal/new-skill',
        method: 'POST',
        authType: 'SERVICE_ACCOUNT',
        timeoutSeconds: 15,
        retryPolicy: 'EXPONENTIAL_BACKOFF',
        inputSchema: '{input}',
        outputSchema: '{output}',
      },
    };
  }
  if (type === 'MCP') {
    return {
      type,
      mcp: {
        serverName: 'new-mcp-server',
        transport: 'STREAMABLE_HTTP',
        connectionUri: 'https://mcp-gateway.internal/new-server',
        namespace: 'default.namespace',
        authType: 'API_KEY',
        heartbeatSeconds: 30,
        exposedTools: ['tool_a', 'tool_b'],
      },
    };
  }
  if (type === 'LLM_MODEL') {
    return {
      type,
      llmModel: {
        providerType: 'OPENAI',
        modelId: 'gpt-4.1-mini',
        baseUrl: 'https://api.openai.com/v1',
        apiKeyEnvVar: 'OPENAI_API_KEY',
        organization: 'lynxus-demo',
        project: 'default-project',
        region: 'global',
        temperature: 0.2,
        maxTokens: 1200,
      },
    };
  }
  return {
    type,
    promptTemplate: {
      templateType: 'CHAT',
      systemPrompt: '你是企业智能体，请输出清晰、可执行的结果。',
      userPromptTemplate: '用户问题：{{question}}\n知识上下文：{{knowledge_context}}',
      responseFormat: 'markdown',
    },
  };
}

const ownerOptions = computed(() => {
  if (createForm.ownerType === 'ASSISTANT') {
    return props.domains
      .find((domain) => domain.id === createForm.domainId)
      ?.scenarios.flatMap((scenario) => scenario.assistants.map((assistant) => ({ label: assistant.name, value: assistant.id })))
      ?? [];
  }
  return props.domains.map((domain) => ({ label: domain.name, value: domain.id }));
});

watch(
  () => props.domains,
  (domains) => {
    if (!createForm.domainId && domains.length) {
      createForm.domainId = domains[0].id;
    }
    if (!createForm.ownerId && domains.length && createForm.ownerType === 'DOMAIN') {
      createForm.ownerId = domains[0].id;
    }
  },
  { immediate: true },
);

watch(
  () => createForm.ownerType,
  (ownerType) => {
    createForm.ownerId = ownerType === 'ASSISTANT'
      ? ownerOptions.value[0]?.value ?? ''
      : (createForm.domainId || props.domains[0]?.id || '');
  },
  { immediate: true },
);

watch(
  () => createForm.domainId,
  (domainId) => {
    if (!domainId) {
      return;
    }
    if (createForm.ownerType === 'DOMAIN') {
      createForm.ownerId = domainId;
      return;
    }
    if (!ownerOptions.value.some((option) => option.value === createForm.ownerId)) {
      createForm.ownerId = ownerOptions.value[0]?.value ?? '';
    }
  },
  { immediate: true },
);

watch(
  () => createForm.type,
  (type) => {
    createForm.initialVersion.configuration = defaultConfiguration(type);
  },
  { immediate: true },
);

function applyBlueprint(type: ResourceBlueprint['type']) {
  createForm.type = type;
}

function submitCreate() {
  emit('createResource', JSON.parse(JSON.stringify(createForm)));
  createForm.name = '';
  createForm.summary = '';
  createForm.steward = '';
  createForm.tags = [];
  createForm.initialVersion.summary = '初始版本';
  createForm.initialVersion.configDigest = '';
  createForm.initialVersion.status = 'DRAFT';
  createForm.initialVersion.configuration = defaultConfiguration(createForm.type);
}
</script>

<template>
  <a-row :gutter="[16, 16]">
    <a-col :span="8">
      <a-card title="资源类型蓝图">
        <a-space direction="vertical" style="width: 100%">
          <a-card
            v-for="blueprint in resourceBlueprints"
            :key="blueprint.type"
            size="small"
            class="clickable-item"
            :class="{ 'graph-list-item--active': createForm.type === blueprint.type }"
            @click="applyBlueprint(blueprint.type)"
          >
            <a-typography-title :level="5" style="margin: 0 0 8px 0">{{ blueprint.label }}</a-typography-title>
            <a-typography-text type="secondary">{{ blueprint.description }}</a-typography-text>
          </a-card>
        </a-space>
      </a-card>

      <a-card v-if="currentBlueprint" title="当前类型需维护">
        <a-list :data-source="currentBlueprint.maintainedFields" size="small">
          <template #renderItem="{ item }">
            <a-list-item>{{ item }}</a-list-item>
          </template>
        </a-list>
      </a-card>
    </a-col>

    <a-col :span="16">
      <a-card title="创建资源">
        <a-form layout="vertical" :model="createForm" @finish="submitCreate">
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="资源名称">
                <a-input v-model:value="createForm.name" placeholder="例如：售后知识库" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="资源类型">
                <a-select
                  v-model:value="createForm.type"
                  :options="resourceBlueprints.map((item) => ({ label: item.label, value: item.type }))"
                />
              </a-form-item>
            </a-col>
          </a-row>

          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="所属业务域">
                <a-select
                  v-model:value="createForm.domainId"
                  :options="domains.map((item) => ({ label: item.name, value: item.id }))"
                />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="共享范围">
                <a-select
                  v-model:value="createForm.shareScope"
                  :options="[
                    { label: '域内共享', value: 'DOMAIN_SHARED' },
                    { label: '私有', value: 'PRIVATE' },
                  ]"
                />
              </a-form-item>
            </a-col>
          </a-row>

          <a-row :gutter="[16, 16]">
            <a-col :span="8">
              <a-form-item label="归属类型">
                <a-select
                  v-model:value="createForm.ownerType"
                  :options="[
                    { label: '业务域', value: 'DOMAIN' },
                    { label: '助手', value: 'ASSISTANT' },
                  ]"
                />
              </a-form-item>
            </a-col>
            <a-col :span="8">
              <a-form-item label="归属对象">
                <a-select v-model:value="createForm.ownerId" :options="ownerOptions" />
              </a-form-item>
            </a-col>
            <a-col :span="8">
              <a-form-item label="资源负责人">
                <a-input v-model:value="createForm.steward" placeholder="例如：客服知识运营" />
              </a-form-item>
            </a-col>
          </a-row>

          <a-form-item label="标签">
            <a-select v-model:value="createForm.tags" mode="tags" style="width: 100%" placeholder="输入标签后回车" />
          </a-form-item>

          <a-form-item label="资源说明">
            <a-textarea v-model:value="createForm.summary" :rows="4" />
          </a-form-item>

          <a-divider orientation="left">初始版本</a-divider>

          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-form-item label="版本说明">
                <a-input v-model:value="createForm.initialVersion.summary" />
              </a-form-item>
            </a-col>
            <a-col :span="12">
              <a-form-item label="配置摘要">
                <a-input v-model:value="createForm.initialVersion.configDigest" placeholder="例如：digest-kb-v1" />
              </a-form-item>
            </a-col>
          </a-row>

          <a-form-item label="初始状态">
            <a-radio-group v-model:value="createForm.initialVersion.status">
              <a-radio-button value="DRAFT">草稿</a-radio-button>
              <a-radio-button value="PUBLISHED">直接生效</a-radio-button>
            </a-radio-group>
          </a-form-item>

          <ResourceVersionConfigEditor
            :resource-type="createForm.type"
            :configuration="createForm.initialVersion.configuration"
          />

          <a-button type="primary" html-type="submit">创建资源</a-button>
        </a-form>
      </a-card>
    </a-col>
  </a-row>
</template>
