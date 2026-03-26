<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { message } from 'ant-design-vue';
import ResourceVersionConfigEditor from '../components/ResourceVersionConfigEditor.vue';
import ResourceVersionConfigSummary from '../components/ResourceVersionConfigSummary.vue';
import { api } from '../services/api';
import type {
  CreateResourceVersionPayload,
  KnowledgeDocument,
  KnowledgeFile,
  KnowledgeImportJob,
  KnowledgeIndexSnapshot,
  Resource,
  ResourceCenter,
  ResourceType,
  ResourceVersion,
} from '../types';

const props = defineProps<{
  resourceCenter: ResourceCenter;
  resources: Resource[];
  preferredResourceId?: string | null;
  preferredVersionId?: string | null;
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
  status: 'DRAFT',
  configuration: {
    type: 'KNOWLEDGE_BASE',
    knowledgeBase: {
      indexSnapshotId: null,
      defaultTopK: 5,
      retrievalMode: 'HYBRID',
      minScore: 0.1,
    },
  },
});

function defaultConfiguration(type: ResourceType): CreateResourceVersionPayload['configuration'] {
  if (type === 'KNOWLEDGE_BASE') {
    return {
      type,
      knowledgeBase: {
        indexSnapshotId: null,
        defaultTopK: 5,
        retrievalMode: 'HYBRID',
        minScore: 0.1,
      },
    };
  }
  if (type === 'TOOL') {
    return {
      type,
      tool: {
        operations: [
          {
            name: 'invoke',
            description: '执行通用工具动作',
            inputSchema: '{"input":"string"}',
            outputSchema: '{"output":"string"}',
          },
        ],
        providerType: 'HTTP',
        authType: 'SERVICE_ACCOUNT',
        timeoutSeconds: 15,
        retryPolicy: 'NONE',
        http: {
          endpoint: 'https://tool-gateway.internal/new-tool',
          method: 'POST',
        },
        mcp: null,
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
    skill: {
      skillName: '新技能',
      skillDesc: '请填写技能用途说明。',
      skillPrompt: '请填写技能行为说明。',
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
const knowledgeFiles = ref<KnowledgeFile[]>([]);
const knowledgeImportJobs = ref<KnowledgeImportJob[]>([]);
const knowledgeDocuments = ref<KnowledgeDocument[]>([]);
const knowledgeSnapshots = ref<KnowledgeIndexSnapshot[]>([]);
const knowledgeGuideVisible = ref(false);
const uploading = ref(false);
const importingUrl = ref(false);
const buildingSnapshot = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);
const appliedPreferredResourceId = ref<string | null>(null);
const appliedPreferredVersionId = ref<string | null>(null);
const urlImportForm = reactive({
  url: '',
  title: '',
});
const selectedReferences = computed(() =>
  props.resourceCenter.references.filter((item) => item.resourceId === selectedResource.value?.id),
);
const blockingReferenceCount = computed(() => selectedReferences.value.filter((item) => item.blocksDeletion).length);
const readyKnowledgeSnapshots = computed(() => knowledgeSnapshots.value.filter((item) => item.status === 'READY'));
const latestReadyKnowledgeSnapshot = computed(() => readyKnowledgeSnapshots.value[0] ?? null);
const selectedVersionSnapshot = computed(() => {
  const snapshotId = selectedVersion.value?.configuration.knowledgeBase?.indexSnapshotId;
  return snapshotId ? findKnowledgeSnapshot(snapshotId) : null;
});
const selectedVersionKnowledgeBinding = computed(() => {
  if (selectedResource.value?.type !== 'KNOWLEDGE_BASE' || !selectedVersion.value) {
    return null;
  }
  const snapshotId = selectedVersion.value.configuration.knowledgeBase?.indexSnapshotId;
  if (!snapshotId) {
    return {
      type: 'warning' as const,
      message: '当前版本还没有绑定快照',
      description: '知识库版本只有在绑定 READY 快照后才能发布到运行时。',
    };
  }
  if (!selectedVersionSnapshot.value) {
    return {
      type: 'warning' as const,
      message: '当前未取到快照列表数据',
      description: `当前版本已绑定 ${snapshotId}，但本页这次没有拿到对应的快照详情，暂时无法判断它是否可发布。`,
    };
  }
  if (selectedVersionSnapshot.value.status !== 'READY') {
    return {
      type: 'warning' as const,
      message: '当前版本绑定的快照尚未就绪',
      description: `${selectedVersionSnapshot.value.id} 目前是 ${selectedVersionSnapshot.value.status}，还不能发布。`,
    };
  }
  return {
    type: 'success' as const,
    message: '当前版本已绑定可发布快照',
    description: `${selectedVersionSnapshot.value.id} · 文档 ${selectedVersionSnapshot.value.documentCount} · chunk ${selectedVersionSnapshot.value.chunkCount}`,
  };
});

watch(
  () => [props.resources, props.preferredResourceId] as const,
  ([resources, preferredResourceId]) => {
    if (!resources.length) {
      selectedResourceId.value = '';
      return;
    }
    if (
      preferredResourceId
      && preferredResourceId !== appliedPreferredResourceId.value
      && resources.some((item) => item.id === preferredResourceId)
    ) {
      selectedResourceId.value = preferredResourceId;
      appliedPreferredResourceId.value = preferredResourceId;
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
  async (resource) => {
    if (!resource) {
      selectedVersionId.value = '';
      return;
    }
    if (
      props.preferredVersionId
      && props.preferredVersionId !== appliedPreferredVersionId.value
      && resource.versions.some((item) => item.id === props.preferredVersionId)
    ) {
      selectedVersionId.value = props.preferredVersionId;
      appliedPreferredVersionId.value = props.preferredVersionId;
    } else if (!resource.versions.some((item) => item.id === selectedVersionId.value)) {
      selectedVersionId.value = resource.latestVersion?.id ?? resource.versions[0]?.id ?? '';
    }
    createVersionForm.summary = '';
    createVersionForm.status = 'DRAFT';
    if (resource.type === 'KNOWLEDGE_BASE') {
      createVersionForm.configuration = defaultConfiguration(resource.type);
      await refreshKnowledgeWorkspace(resource.id);
      createVersionForm.configuration = buildCreateVersionConfiguration(resource);
    } else {
      knowledgeFiles.value = [];
      knowledgeImportJobs.value = [];
      knowledgeDocuments.value = [];
      knowledgeSnapshots.value = [];
      createVersionForm.configuration = JSON.parse(JSON.stringify(
        resource.latestVersion?.configuration
        ?? resource.effectiveVersion?.configuration
        ?? defaultConfiguration(resource.type),
      ));
    }
  },
  { immediate: true },
);

function submitCreateVersion() {
  if (!selectedResource.value) {
    return;
  }
  if (selectedResource.value.type === 'KNOWLEDGE_BASE' && createVersionForm.status === 'PUBLISHED') {
    const snapshotId = createVersionForm.configuration.knowledgeBase?.indexSnapshotId;
    const snapshot = snapshotId ? findKnowledgeSnapshot(snapshotId) : null;
    if (!snapshotId || !snapshot || snapshot.status !== 'READY') {
      message.error('发布知识库版本前，请先绑定一个 READY 快照。');
      return;
    }
  }
  emit('createResourceVersion', {
    resourceId: selectedResource.value.id,
    data: JSON.parse(JSON.stringify(createVersionForm)),
  });
}

async function refreshKnowledgeWorkspace(resourceId: string) {
  const [files, jobs, documents, snapshots] = await Promise.all([
    api.listKnowledgeFiles(resourceId),
    api.listKnowledgeImportJobs(resourceId),
    api.listKnowledgeDocuments(resourceId),
    api.listKnowledgeIndexSnapshots(resourceId),
  ]);
  knowledgeFiles.value = files;
  knowledgeImportJobs.value = jobs;
  knowledgeDocuments.value = documents;
  knowledgeSnapshots.value = snapshots.slice().sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt));
}

async function handleFileSelection(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file || !selectedResource.value || selectedResource.value.type !== 'KNOWLEDGE_BASE') {
    return;
  }
  uploading.value = true;
  try {
    const session = await api.createKnowledgeUploadSession(selectedResource.value.id);
    await api.completeKnowledgeUpload(selectedResource.value.id, session.id, file);
    await refreshKnowledgeWorkspace(selectedResource.value.id);
    message.success('文件已上传并触发导入任务。');
  } finally {
    uploading.value = false;
    input.value = '';
  }
}

async function handleCreateSnapshot() {
  if (!selectedResource.value || selectedResource.value.type !== 'KNOWLEDGE_BASE') {
    return;
  }
  buildingSnapshot.value = true;
  try {
    await api.createKnowledgeIndexSnapshot(selectedResource.value.id, knowledgeDocuments.value.map((item) => item.id));
    await refreshKnowledgeWorkspace(selectedResource.value.id);
    message.success('已创建索引快照并触发构建。');
  } finally {
    buildingSnapshot.value = false;
  }
}

async function handleUrlImport() {
  if (!selectedResource.value || selectedResource.value.type !== 'KNOWLEDGE_BASE' || !urlImportForm.url.trim()) {
    return;
  }
  importingUrl.value = true;
  try {
    await api.importKnowledgeUrl(selectedResource.value.id, urlImportForm.url.trim(), urlImportForm.title.trim());
    urlImportForm.url = '';
    urlImportForm.title = '';
    await refreshKnowledgeWorkspace(selectedResource.value.id);
    message.success('URL 已加入导入队列。');
  } finally {
    importingUrl.value = false;
  }
}

function findKnowledgeSnapshot(snapshotId: string) {
  return knowledgeSnapshots.value.find((snapshot) => snapshot.id === snapshotId) ?? null;
}

function buildCreateVersionConfiguration(resource: Resource): CreateResourceVersionPayload['configuration'] {
  const configuration = JSON.parse(JSON.stringify(
    resource.latestVersion?.configuration
    ?? resource.effectiveVersion?.configuration
    ?? defaultConfiguration(resource.type),
  )) as CreateResourceVersionPayload['configuration'];
  if (resource.type !== 'KNOWLEDGE_BASE' || !configuration.knowledgeBase) {
    return configuration;
  }
  const snapshotId = configuration.knowledgeBase.indexSnapshotId;
  const snapshotReady = snapshotId ? readyKnowledgeSnapshots.value.some((item) => item.id === snapshotId) : false;
  if (!snapshotReady) {
    configuration.knowledgeBase.indexSnapshotId = latestReadyKnowledgeSnapshot.value?.id ?? null;
  }
  return configuration;
}

function describeVersion(version: ResourceVersion): string {
  if (selectedResource.value?.type !== 'KNOWLEDGE_BASE') {
    return version.summary;
  }
  const snapshotId = version.configuration.knowledgeBase?.indexSnapshotId;
  if (!snapshotId) {
    return `${version.summary} · 未绑定快照`;
  }
  const snapshot = findKnowledgeSnapshot(snapshotId);
  if (!snapshot) {
    return `${version.summary} · 快照 ${snapshotId}`;
  }
  return `${version.summary} · ${snapshot.id} · ${snapshot.status} · 文档 ${snapshot.documentCount} · chunk ${snapshot.chunkCount}`;
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

        <a-card v-if="selectedResource?.type === 'KNOWLEDGE_BASE'" title="知识内容与上传">
          <template #extra>
            <a-space>
              <a-button type="link" @click="knowledgeGuideVisible = true">切分与检索说明</a-button>
              <input ref="fileInput" type="file" style="display: none" @change="handleFileSelection" />
              <a-button :loading="uploading" @click="fileInput?.click()">上传文件</a-button>
              <a-button type="primary" :loading="buildingSnapshot" @click="handleCreateSnapshot">生成索引快照</a-button>
            </a-space>
          </template>
          <a-alert
            type="info"
            show-icon
            style="margin-bottom: 16px"
            message="在这里上传知识库文件并生成可发布快照"
            description="支持本地文件上传和 URL 导入。文件解析完成后，生成 READY 快照，再到下方版本区把快照绑定到版本。"
          />
          <a-row :gutter="[16, 16]" style="margin-bottom: 16px">
            <a-col :span="8">
              <a-card size="small"><a-statistic title="已解析文档" :value="knowledgeDocuments.length" /></a-card>
            </a-col>
            <a-col :span="8">
              <a-card size="small"><a-statistic title="READY 快照" :value="readyKnowledgeSnapshots.length" /></a-card>
            </a-col>
            <a-col :span="8">
              <a-card size="small">
                <a-statistic
                  title="最新 READY 快照"
                  :value="latestReadyKnowledgeSnapshot?.id || '待生成'"
                  :value-style="{ fontSize: '16px' }"
                />
              </a-card>
            </a-col>
          </a-row>
          <a-card size="small" style="margin-bottom: 16px" title="URL 导入">
            <a-row :gutter="[12, 12]">
              <a-col :span="14">
                <a-input v-model:value="urlImportForm.url" placeholder="https://example.com/knowledge/article" />
              </a-col>
              <a-col :span="6">
                <a-input v-model:value="urlImportForm.title" placeholder="可选标题覆盖" />
              </a-col>
              <a-col :span="4">
                <a-button type="default" block :loading="importingUrl" @click="handleUrlImport">抓取 URL</a-button>
              </a-col>
            </a-row>
          </a-card>
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-card size="small" title="文件">
                <a-list :data-source="knowledgeFiles" size="small">
                  <template #renderItem="{ item }">
                    <a-list-item>
                      <a-list-item-meta :title="item.fileName" :description="`${item.status} · ${item.contentType}`" />
                    </a-list-item>
                  </template>
                </a-list>
              </a-card>
            </a-col>
            <a-col :span="12">
              <a-card size="small" title="导入任务">
                <a-list :data-source="knowledgeImportJobs" size="small">
                  <template #renderItem="{ item }">
                    <a-list-item>
                      <a-list-item-meta :title="item.id" :description="item.failureReason || item.status" />
                    </a-list-item>
                  </template>
                </a-list>
              </a-card>
            </a-col>
            <a-col :span="12">
              <a-card size="small" title="文档">
                <a-list :data-source="knowledgeDocuments" size="small">
                  <template #renderItem="{ item }">
                    <a-list-item>
                      <a-list-item-meta :title="item.title" :description="`${item.documentType} · chunk ${item.chunkCount}`" />
                    </a-list-item>
                  </template>
                </a-list>
              </a-card>
            </a-col>
            <a-col :span="12">
              <a-card size="small" title="索引快照">
                <a-list :data-source="knowledgeSnapshots" size="small">
                  <template #renderItem="{ item }">
                    <a-list-item>
                      <a-list-item-meta :title="item.id" :description="`${item.status} · 文档 ${item.documentCount} · chunk ${item.chunkCount}`" />
                    </a-list-item>
                  </template>
                </a-list>
              </a-card>
            </a-col>
          </a-row>
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
                      :description="describeVersion(item)"
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
              <a-alert
                v-if="selectedVersionKnowledgeBinding"
                :type="selectedVersionKnowledgeBinding.type"
                show-icon
                style="margin-bottom: 16px"
                :message="selectedVersionKnowledgeBinding.message"
                :description="selectedVersionKnowledgeBinding.description"
              />
              <a-descriptions :column="1" size="small">
                <a-descriptions-item label="版本号">{{ selectedVersion.version }}</a-descriptions-item>
                <a-descriptions-item label="状态">{{ selectedVersion.status }}</a-descriptions-item>
                <a-descriptions-item label="变更说明">{{ selectedVersion.summary }}</a-descriptions-item>
                <a-descriptions-item label="发布时间">{{ selectedVersion.publishedAt || '-' }}</a-descriptions-item>
              </a-descriptions>
              <ResourceVersionConfigSummary
                :resource-type="selectedResource.type"
                :configuration="selectedVersion.configuration"
                :knowledge-snapshots="knowledgeSnapshots"
              />
            </a-card>
          </a-col>
        </a-row>

        <a-modal
          v-model:open="knowledgeGuideVisible"
          title="知识库文档切分与检索说明"
          width="760px"
          :footer="null"
        >
          <a-space direction="vertical" size="middle" style="width: 100%">
            <a-alert
              type="info"
              show-icon
              message="当前实现是快照化文本检索"
              description="文件先导入并解析成文档与 chunk，生成索引快照后，运行时始终按资源版本绑定的 snapshot 检索。"
            />

            <a-card size="small" title="支持的文档类型">
              <p style="margin-bottom: 8px">当前支持 `pdf / docx / md / txt / html / csv`。</p>
              <p style="margin: 0">OCR、扫描件增强解析这类复杂处理当前未做；如果 PDF 无法直接抽出文本，导入任务会失败并在任务列表里显示原因。</p>
            </a-card>

            <a-card size="small" title="当前切分规则">
              <p style="margin-bottom: 8px">Markdown / HTML：优先按标题层级切分，chunk 会保留标题和标题路径。</p>
              <p style="margin-bottom: 8px">CSV：按“表头 + 最多 5 行数据”切成表格块。</p>
              <p style="margin-bottom: 8px">TXT / DOCX / PDF：先按空行分段，再进一步处理。</p>
              <p style="margin: 0">如果识别到 `Q/A` 或 `问/答` 形式，会按单条 FAQ 生成 chunk；超长内容会继续按句子拆分，目标块大小约 400 tokens，单块上限约 600 tokens，并保留少量句子重叠。</p>
            </a-card>

            <a-card size="small" title="索引与检索过程">
              <p style="margin-bottom: 8px">“生成索引快照”会把当前已成功解析的文档冻结成一个不可变 snapshot。</p>
              <p style="margin-bottom: 8px">快照构建完成后，知识服务会把 chunk 写入检索索引；版本发布时需要绑定一个 `READY` 的 snapshot。</p>
              <p style="margin: 0">运行时检索返回的是结构化命中结果，包含标题、来源、片段、分数等信息，低置信度时不会注入知识正文。</p>
            </a-card>

            <a-card size="small" title="使用建议">
              <p style="margin-bottom: 8px">文档里尽量保留清晰标题、稳定 FAQ 格式和较短段落，这样命中率会更稳定。</p>
              <p style="margin-bottom: 8px">更新文件内容后，需要重新生成索引快照；旧版本仍然继续绑定旧 snapshot，不会自动漂移。</p>
              <p style="margin: 0">如果要让某次发布只包含部分文档，可以先确认文档列表和快照列表，再将目标 snapshot 绑定到版本配置。</p>
            </a-card>
          </a-space>
        </a-modal>

        <a-card v-if="selectedResource" title="创建新版本">
          <a-form layout="vertical" :model="createVersionForm" @finish="submitCreateVersion">
            <a-form-item label="版本说明">
              <a-input v-model:value="createVersionForm.summary" placeholder="描述这次资源版本调整的内容" />
            </a-form-item>
            <a-form-item label="初始状态">
              <a-radio-group v-model:value="createVersionForm.status">
                <a-radio-button value="DRAFT">草稿</a-radio-button>
                <a-radio-button value="PUBLISHED">直接生效</a-radio-button>
              </a-radio-group>
            </a-form-item>
            <ResourceVersionConfigEditor
              :resource-type="selectedResource.type"
              :configuration="createVersionForm.configuration"
              :knowledge-snapshots="knowledgeSnapshots"
              :snapshot-binding-mode="selectedResource.type === 'KNOWLEDGE_BASE' ? 'select' : 'hidden'"
            />
            <a-button type="primary" html-type="submit">创建版本</a-button>
          </a-form>
        </a-card>
      </a-space>
    </a-col>
  </a-row>
</template>
