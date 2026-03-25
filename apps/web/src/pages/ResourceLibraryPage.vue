<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import ResourceVersionConfigEditor from '../components/ResourceVersionConfigEditor.vue';
import ResourceVersionConfigSummary from '../components/ResourceVersionConfigSummary.vue';
import type {
  CreateResourceVersionPayload,
  Resource,
  ResourceCenter,
  ResourceType,
} from '../types';

const props = defineProps<{
  resourceCenter: ResourceCenter;
  resources: Resource[];
}>();

const emit = defineEmits<{
  deleteResource: [resourceId: string];
  createResourceVersion: [payload: { resourceId: string; data: CreateResourceVersionPayload }];
  deleteResourceVersion: [payload: { resourceId: string; versionId: string }];
  publishResourceVersion: [payload: { resourceId: string; versionId: string }];
}>();

const selectedResourceId = ref('');
const selectedVersionId = ref('');
const createVersionForm = reactive<CreateResourceVersionPayload>({
  summary: '',
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
});

function defaultConfiguration(type: ResourceType): CreateResourceVersionPayload['configuration'] {
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

const selectedResource = computed(() =>
  props.resources.find((item) => item.id === selectedResourceId.value) ?? props.resources[0],
);

const selectedVersion = computed(() =>
  selectedResource.value?.versions.find((item) => item.id === selectedVersionId.value)
  ?? selectedResource.value?.versions[0],
);
const selectedReferences = computed(() =>
  props.resourceCenter.references.filter((item) => item.resourceId === selectedResource.value?.id),
);
const blockingReferenceCount = computed(() => selectedReferences.value.filter((item) => item.blocksDeletion).length);

watch(
  () => props.resources,
  (resources) => {
    if (!resources.length) {
      selectedResourceId.value = '';
      return;
    }
    if (!resources.some((item) => item.id === selectedResourceId.value)) {
      selectedResourceId.value = resources[0].id;
    }
  },
  { immediate: true },
);

watch(
  selectedResource,
  (resource) => {
    if (!resource) {
      selectedVersionId.value = '';
      return;
    }
    if (!resource.versions.some((item) => item.id === selectedVersionId.value)) {
      selectedVersionId.value = resource.latestVersion?.id ?? resource.versions[0]?.id ?? '';
    }
    createVersionForm.summary = '';
    createVersionForm.configDigest = '';
    createVersionForm.status = 'DRAFT';
    createVersionForm.configuration = JSON.parse(JSON.stringify(
      resource.latestVersion?.configuration
      ?? resource.effectiveVersion?.configuration
      ?? defaultConfiguration(resource.type),
    ));
  },
  { immediate: true },
);

function submitCreateVersion() {
  if (!selectedResource.value) {
    return;
  }
  emit('createResourceVersion', {
    resourceId: selectedResource.value.id,
    data: JSON.parse(JSON.stringify(createVersionForm)),
  });
}
</script>

<template>
  <a-row :gutter="[16, 16]">
    <a-col :span="8">
      <a-card><a-statistic title="资源总数" :value="resourceCenter.totalResources" /></a-card>
    </a-col>
    <a-col :span="8">
      <a-card><a-statistic title="域内共享" :value="resourceCenter.domainSharedResources" /></a-card>
    </a-col>
    <a-col :span="8">
      <a-card><a-statistic title="私有资源" :value="resourceCenter.privateResources" /></a-card>
    </a-col>
  </a-row>

  <a-row :gutter="[16, 16]">
    <a-col :span="8">
      <a-card title="资源目录">
        <a-list :data-source="resources">
          <template #renderItem="{ item }">
            <a-list-item
              class="clickable-item"
              :class="{ 'graph-list-item--active': selectedResourceId === item.id }"
              @click="selectedResourceId = item.id"
            >
              <a-list-item-meta
                :title="item.name"
                :description="`${item.type} · 最新 ${item.latestVersion?.version ?? '-'} · 生效 ${item.effectiveVersion?.version ?? '-'}`"
              />
              <a-tag>{{ item.shareScope }}</a-tag>
            </a-list-item>
          </template>
        </a-list>
      </a-card>

      <a-card title="引用分析">
        <a-table
          :columns="[
            { title: '引用类型', dataIndex: 'referenceKind', key: 'referenceKind' },
            { title: '来源对象', dataIndex: 'sourceName', key: 'sourceName' },
            { title: '版本', dataIndex: 'resourceVersion', key: 'resourceVersion' },
            { title: '阻断删除', dataIndex: 'blocksDeletion', key: 'blocksDeletion' },
          ]"
          :data-source="selectedReferences"
          :pagination="false"
          row-key="(record) => `${record.referenceKind}:${record.sourceId}:${record.resourceVersionId ?? 'none'}`"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'resourceVersion'">
              {{ record.resourceVersion ?? '-' }}
            </template>
            <template v-else-if="column.key === 'blocksDeletion'">
              <a-tag :color="record.blocksDeletion ? 'red' : 'default'">{{ record.blocksDeletion ? '是' : '否' }}</a-tag>
            </template>
          </template>
        </a-table>
      </a-card>
    </a-col>

    <a-col :span="16">
      <a-space direction="vertical" style="width: 100%" size="large">
        <a-card v-if="selectedResource" :title="selectedResource.name">
          <template #extra>
            <a-popconfirm
              title="确认删除该资源？"
              description="只有未被助手默认策略、智能体执行策略或版本绑定引用的资源才允许删除。"
              ok-text="删除"
              cancel-text="取消"
              @confirm="emit('deleteResource', selectedResource.id)"
            >
              <a-button danger size="small">删除资源</a-button>
            </a-popconfirm>
          </template>
          <a-descriptions :column="2" size="small">
            <a-descriptions-item label="资源类型">{{ selectedResource.type }}</a-descriptions-item>
            <a-descriptions-item label="共享范围">{{ selectedResource.shareScope }}</a-descriptions-item>
            <a-descriptions-item label="资源负责人">{{ selectedResource.steward }}</a-descriptions-item>
            <a-descriptions-item label="标签">{{ selectedResource.tags.join(' / ') || '-' }}</a-descriptions-item>
            <a-descriptions-item label="最新版本">{{ selectedResource.latestVersion?.version ?? '-' }}</a-descriptions-item>
            <a-descriptions-item label="生效版本">{{ selectedResource.effectiveVersion?.version ?? '-' }}</a-descriptions-item>
            <a-descriptions-item label="归属">{{ `${selectedResource.ownerType}:${selectedResource.ownerId}` }}</a-descriptions-item>
            <a-descriptions-item label="说明">{{ selectedResource.summary }}</a-descriptions-item>
            <a-descriptions-item label="引用总数">
              {{ selectedReferences.length }}
            </a-descriptions-item>
            <a-descriptions-item label="阻断删除引用">
              {{ blockingReferenceCount }}
            </a-descriptions-item>
          </a-descriptions>
        </a-card>

        <a-row :gutter="[16, 16]">
          <a-col :span="12">
            <a-card v-if="selectedResource" title="版本清单">
              <a-list :data-source="selectedResource.versions">
                <template #renderItem="{ item }">
                  <a-list-item
                    class="clickable-item"
                    :class="{ 'graph-list-item--active': selectedVersionId === item.id }"
                    @click="selectedVersionId = item.id"
                  >
                    <a-list-item-meta
                      :title="`${item.version} · ${item.status}`"
                      :description="`${item.summary} · ${item.configDigest}`"
                    />
                    <a-space>
                      <a-tag v-if="selectedResource.effectiveVersion?.id === item.id" color="green">生效中</a-tag>
                      <a-tag v-if="selectedResource.latestVersion?.id === item.id" color="blue">最新</a-tag>
                      <a-button
                        v-if="selectedResource.effectiveVersion?.id !== item.id"
                        size="small"
                        @click.stop="emit('publishResourceVersion', { resourceId: selectedResource.id, versionId: item.id })"
                      >
                        设为生效版本
                      </a-button>
                      <a-popconfirm
                        v-if="selectedResource.effectiveVersion?.id !== item.id"
                        title="确认删除该版本？"
                        description="只有未生效且未被智能体绑定的版本才允许删除。"
                        ok-text="删除"
                        cancel-text="取消"
                        @confirm="emit('deleteResourceVersion', { resourceId: selectedResource.id, versionId: item.id })"
                      >
                        <a-button danger size="small">删除版本</a-button>
                      </a-popconfirm>
                    </a-space>
                  </a-list-item>
                </template>
              </a-list>
            </a-card>
          </a-col>

          <a-col :span="12">
            <a-card v-if="selectedVersion && selectedResource" title="版本详情">
              <a-descriptions :column="1" size="small">
                <a-descriptions-item label="版本号">{{ selectedVersion.version }}</a-descriptions-item>
                <a-descriptions-item label="状态">{{ selectedVersion.status }}</a-descriptions-item>
                <a-descriptions-item label="变更说明">{{ selectedVersion.summary }}</a-descriptions-item>
                <a-descriptions-item label="配置摘要">{{ selectedVersion.configDigest }}</a-descriptions-item>
              </a-descriptions>
              <ResourceVersionConfigSummary
                :resource-type="selectedResource.type"
                :configuration="selectedVersion.configuration"
              />
            </a-card>
          </a-col>
        </a-row>

        <a-card v-if="selectedResource" title="创建新版本">
          <a-form layout="vertical" :model="createVersionForm" @finish="submitCreateVersion">
            <a-row :gutter="[16, 16]">
              <a-col :span="12">
                <a-form-item label="版本说明">
                  <a-input v-model:value="createVersionForm.summary" placeholder="描述这次资源版本调整的内容" />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item label="配置摘要">
                  <a-input v-model:value="createVersionForm.configDigest" placeholder="例如：digest-kb-v2" />
                </a-form-item>
              </a-col>
            </a-row>
            <a-form-item label="初始状态">
              <a-radio-group v-model:value="createVersionForm.status">
                <a-radio-button value="DRAFT">草稿</a-radio-button>
                <a-radio-button value="PUBLISHED">直接生效</a-radio-button>
              </a-radio-group>
            </a-form-item>
            <ResourceVersionConfigEditor
              :resource-type="selectedResource.type"
              :configuration="createVersionForm.configuration"
            />
            <a-button type="primary" html-type="submit">创建版本</a-button>
          </a-form>
        </a-card>
      </a-space>
    </a-col>
  </a-row>
</template>
