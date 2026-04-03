<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { message } from 'ant-design-vue';
import ObjectReferencePanel from '../components/ObjectReferencePanel.vue';
import { hasActiveKnowledgeOperations, knowledgeSourceLabel, knowledgeStatusColor } from './knowledgeWorkspace';
import { api } from '../services/api';
import type {
  CreateKnowledgeReleasePayload,
  KnowledgeBase,
  KnowledgeDocument,
  KnowledgeDocumentDeletionPreview,
  KnowledgeFile,
  KnowledgeImportJob,
  KnowledgeIndexSnapshot,
  KnowledgeRetrievalPreviewResult,
  KnowledgeRelease,
} from '../types';

const props = defineProps<{
  knowledgeBases: KnowledgeBase[];
  preferredKnowledgeBaseId?: string | null;
  catalogRevision: number;
  canManageGovernance: boolean;
}>();

const emit = defineEmits<{
  refreshCatalog: [];
  deleteKnowledgeBase: [knowledgeBaseId: string];
}>();

const selectedKnowledgeBaseId = ref('');
const loadingWorkspace = ref(false);
const fileList = ref<KnowledgeFile[]>([]);
const importJobs = ref<KnowledgeImportJob[]>([]);
const documents = ref<KnowledgeDocument[]>([]);
const snapshots = ref<KnowledgeIndexSnapshot[]>([]);
const releases = ref<KnowledgeRelease[]>([]);
const uploading = ref(false);
const uploadGuideOpen = ref(false);
const creatingSnapshot = ref(false);
const previewingRetrieval = ref(false);
const previewingDocumentDeletion = ref(false);
const deletingDocument = ref(false);
const documentDeletionTargetId = ref('');
const retrievalPreviewSnapshotId = ref('');
const retrievalPreviewQuery = ref('');
const retrievalPreviewResult = ref<KnowledgeRetrievalPreviewResult | null>(null);
const documentDeletionPreview = ref<KnowledgeDocumentDeletionPreview | null>(null);
const documentDeletionOpen = ref(false);
let workspacePollingTimer: number | null = null;
const releaseForm = reactive<CreateKnowledgeReleasePayload>({
  summary: '',
  status: 'DRAFT',
  snapshotId: '',
  retrievalProfile: {
    defaultTopK: 5,
    retrievalMode: 'HYBRID',
    minScore: 0.1,
  },
});
const urlImportForm = reactive({
  url: '',
  title: '',
});
const knowledgeUploadAccept = '.pdf,.docx,.md,.txt,.html,.csv';
const uploadFormatCards = [
  {
    title: '推荐优先',
    formats: '.md / .txt / .html',
    description: '纯文本最稳定，标题层级和段落结构越清晰，切分和检索效果越好。',
  },
  {
    title: '表格数据',
    formats: '.csv',
    description: '首行应为表头，每行表达一条完整记录，避免把多类主题塞进同一张表。',
  },
  {
    title: '办公文档',
    formats: '.docx / .pdf',
    description: '支持导入，但更依赖源文档排版质量；扫描件、双栏、复杂水印会降低解析效果。',
  },
] as const;
const uploadContentRecommendations = [
  '一份文件聚焦一个主题或流程，文件名直接表达内容，例如“退款规则.md”。',
  'Markdown 使用明确标题层级，例如 # 一级主题、## 子主题；FAQ 采用“问题 + 回答”连续结构。',
  'CSV 第一行写清字段名，每一行字段含义完整，不要留大量空列或混入说明段落。',
  'PDF / DOCX 尽量提供可复制文本，减少截图、扫描页、页眉页脚重复内容和多栏排版。',
  '文本、HTML、CSV 优先使用 UTF-8 编码，避免乱码导致导入后文本不可读。',
] as const;
const uploadContentWarnings = [
  '仅包含图片的 PDF、截图拼接文档、拍照扫描件，通常无法提取有效文本。',
  '把多个不相关制度、FAQ、表格混在同一文件里，会让切分结果和召回边界变差。',
  '依赖颜色、批注、形状连线表达关键信息的文档，导入后这些语义可能丢失。',
] as const;

const selectedKnowledgeBase = computed(() =>
  props.knowledgeBases.find((item) => item.id === selectedKnowledgeBaseId.value) ?? props.knowledgeBases[0] ?? null,
);
const readySnapshots = computed(() => snapshots.value.filter((item) => item.status === 'READY'));
const hasPendingOperations = computed(() => hasActiveKnowledgeOperations(importJobs.value, snapshots.value));

async function loadWorkspace() {
  if (!selectedKnowledgeBase.value) {
    fileList.value = [];
    importJobs.value = [];
    documents.value = [];
    snapshots.value = [];
    releases.value = [];
    retrievalPreviewResult.value = null;
    retrievalPreviewSnapshotId.value = '';
    return;
  }
  loadingWorkspace.value = true;
  try {
    const knowledgeBaseId = selectedKnowledgeBase.value.id;
    const [files, jobs, docs, snapshotList, releaseList] = await Promise.all([
      api.listKnowledgeFiles(knowledgeBaseId),
      api.listKnowledgeImportJobs(knowledgeBaseId),
      api.listKnowledgeDocuments(knowledgeBaseId),
      api.listKnowledgeIndexSnapshots(knowledgeBaseId),
      api.listKnowledgeReleases(knowledgeBaseId),
    ]);
    fileList.value = files;
    importJobs.value = jobs;
    documents.value = docs;
    snapshots.value = snapshotList;
    releases.value = releaseList;
    if (!readySnapshots.value.some((item) => item.id === releaseForm.snapshotId)) {
      releaseForm.snapshotId = readySnapshots.value[0]?.id ?? '';
    }
    if (!readySnapshots.value.some((item) => item.id === retrievalPreviewSnapshotId.value)) {
      retrievalPreviewSnapshotId.value = readySnapshots.value[0]?.id ?? '';
    }
  } finally {
    loadingWorkspace.value = false;
  }
}

function startWorkspacePolling() {
  if (typeof window === 'undefined' || workspacePollingTimer !== null) {
    return;
  }
  workspacePollingTimer = window.setInterval(() => {
    if (!selectedKnowledgeBase.value || !hasPendingOperations.value || loadingWorkspace.value) {
      return;
    }
    void loadWorkspace();
  }, 3000);
}

function stopWorkspacePolling() {
  if (workspacePollingTimer !== null && typeof window !== 'undefined') {
    window.clearInterval(workspacePollingTimer);
    workspacePollingTimer = null;
  }
}

function formatBytes(sizeBytes: number): string {
  if (sizeBytes < 1024) {
    return `${sizeBytes} B`;
  }
  if (sizeBytes < 1024 * 1024) {
    return `${(sizeBytes / 1024).toFixed(1)} KB`;
  }
  return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
}

watch(
  () => [props.knowledgeBases, props.preferredKnowledgeBaseId] as const,
  ([knowledgeBases, preferredKnowledgeBaseId]) => {
    if (!knowledgeBases.length) {
      selectedKnowledgeBaseId.value = '';
      return;
    }
    if (preferredKnowledgeBaseId && knowledgeBases.some((item) => item.id === preferredKnowledgeBaseId)) {
      selectedKnowledgeBaseId.value = preferredKnowledgeBaseId;
      return;
    }
    if (!knowledgeBases.some((item) => item.id === selectedKnowledgeBaseId.value)) {
      selectedKnowledgeBaseId.value = knowledgeBases[0].id;
    }
  },
  { immediate: true },
);

watch(selectedKnowledgeBaseId, () => {
  retrievalPreviewResult.value = null;
  void loadWorkspace();
});

onMounted(() => {
  startWorkspacePolling();
  void loadWorkspace();
});

onBeforeUnmount(() => {
  stopWorkspacePolling();
});

async function handleUpload(file: File) {
  if (!selectedKnowledgeBase.value) {
    return false;
  }
  uploading.value = true;
  try {
    const session = await api.createKnowledgeUploadSession(selectedKnowledgeBase.value.id);
    await api.completeKnowledgeUpload(selectedKnowledgeBase.value.id, session.id, file);
    await loadWorkspace();
    emit('refreshCatalog');
    void message.success('文件已提交导入，后台处理中');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '文件导入失败');
  } finally {
    uploading.value = false;
  }
  return false;
}

async function handleImportUrl() {
  if (!selectedKnowledgeBase.value || !urlImportForm.url.trim()) {
    return;
  }
  try {
    await api.importKnowledgeUrl(selectedKnowledgeBase.value.id, urlImportForm.url, urlImportForm.title);
    urlImportForm.url = '';
    urlImportForm.title = '';
    await loadWorkspace();
    void message.success('URL 已提交导入，后台处理中');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : 'URL 导入失败');
  }
}

async function handleCreateSnapshot() {
  if (!selectedKnowledgeBase.value) {
    return;
  }
  creatingSnapshot.value = true;
  try {
    await api.createKnowledgeIndexSnapshot(
      selectedKnowledgeBase.value.id,
      documents.value.filter((item) => item.status === 'READY').map((item) => item.id),
    );
    await loadWorkspace();
    void message.success('索引快照已提交构建');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '创建索引快照失败');
  } finally {
    creatingSnapshot.value = false;
  }
}

async function handleOpenDocumentDeletion(document: KnowledgeDocument) {
  if (!selectedKnowledgeBase.value) {
    return;
  }
  documentDeletionTargetId.value = document.id;
  previewingDocumentDeletion.value = true;
  try {
    documentDeletionPreview.value = await api.previewKnowledgeDocumentDeletion(selectedKnowledgeBase.value.id, document.id);
    documentDeletionOpen.value = true;
  } catch (error) {
    documentDeletionPreview.value = null;
    documentDeletionOpen.value = false;
    documentDeletionTargetId.value = '';
    void message.error(error instanceof Error ? error.message : '加载文档删除预览失败');
  } finally {
    previewingDocumentDeletion.value = false;
  }
}

function closeDocumentDeletionModal() {
  if (deletingDocument.value) {
    return;
  }
  documentDeletionOpen.value = false;
  documentDeletionPreview.value = null;
  documentDeletionTargetId.value = '';
}

async function handleConfirmDeleteDocument() {
  if (!selectedKnowledgeBase.value || !documentDeletionPreview.value?.canDelete) {
    return;
  }
  deletingDocument.value = true;
  try {
    await api.deleteKnowledgeDocument(selectedKnowledgeBase.value.id, documentDeletionPreview.value.documentId);
    documentDeletionOpen.value = false;
    documentDeletionPreview.value = null;
    documentDeletionTargetId.value = '';
    await loadWorkspace();
    void message.success('导入文档已删除');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '删除导入文档失败');
  } finally {
    deletingDocument.value = false;
  }
}

async function handleCreateRelease() {
  if (!selectedKnowledgeBase.value || !releaseForm.snapshotId) {
    return;
  }
  try {
    await api.createKnowledgeRelease(selectedKnowledgeBase.value.id, JSON.parse(JSON.stringify(releaseForm)));
    releaseForm.summary = '';
    releaseForm.status = 'DRAFT';
    await loadWorkspace();
    emit('refreshCatalog');
    void message.success('发布版本已创建');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '创建发布版本失败');
  }
}

async function handlePublishRelease(releaseId: string) {
  if (!selectedKnowledgeBase.value) {
    return;
  }
  try {
    await api.publishKnowledgeRelease(selectedKnowledgeBase.value.id, releaseId);
    await loadWorkspace();
    emit('refreshCatalog');
    void message.success('知识发布已生效');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '发布知识版本失败');
  }
}

async function handleDeleteRelease(releaseId: string) {
  if (!selectedKnowledgeBase.value) {
    return;
  }
  try {
    await api.deleteKnowledgeRelease(selectedKnowledgeBase.value.id, releaseId);
    await loadWorkspace();
    emit('refreshCatalog');
    void message.success('知识发布版本已删除');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '删除知识发布失败');
  }
}

async function handleDeleteKnowledgeBase() {
  if (!selectedKnowledgeBase.value) {
    return;
  }
  emit('deleteKnowledgeBase', selectedKnowledgeBase.value.id);
}

async function handleRetryImportJob(jobId: string) {
  if (!selectedKnowledgeBase.value) {
    return;
  }
  try {
    await api.retryKnowledgeImportJob(selectedKnowledgeBase.value.id, jobId);
    await loadWorkspace();
    void message.success('导入任务已重新排队');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '重试导入任务失败');
  }
}

async function handleRetrySnapshot(snapshotId: string) {
  if (!selectedKnowledgeBase.value) {
    return;
  }
  try {
    await api.retryKnowledgeIndexSnapshot(selectedKnowledgeBase.value.id, snapshotId);
    await loadWorkspace();
    void message.success('索引快照已重新排队构建');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '重试索引快照失败');
  }
}

async function handlePreviewRetrieval() {
  if (!selectedKnowledgeBase.value || !retrievalPreviewSnapshotId.value || !retrievalPreviewQuery.value.trim()) {
    return;
  }
  previewingRetrieval.value = true;
  try {
    retrievalPreviewResult.value = await api.previewKnowledgeRetrieval(selectedKnowledgeBase.value.id, {
      snapshotId: retrievalPreviewSnapshotId.value,
      query: retrievalPreviewQuery.value.trim(),
      topK: 5,
    });
  } catch (error) {
    retrievalPreviewResult.value = null;
    void message.error(error instanceof Error ? error.message : '检索验证失败');
  } finally {
    previewingRetrieval.value = false;
  }
}
</script>

<template>
  <a-row :gutter="[16, 16]">
    <a-col :span="7">
      <a-card title="知识库目录">
        <template #extra>
          <a-tag color="blue">{{ knowledgeBases.length }} 个知识库</a-tag>
        </template>
        <a-list :data-source="knowledgeBases">
          <template #renderItem="{ item }">
            <a-list-item class="clickable-item" @click="selectedKnowledgeBaseId = item.id">
              <a-list-item-meta :title="item.name" :description="item.summary || item.domainId" />
              <a-space>
                <a-tag v-if="selectedKnowledgeBaseId === item.id" color="blue">当前</a-tag>
                <a-tag :color="item.effectiveRelease ? 'green' : 'gold'">
                  {{ item.effectiveRelease ? item.effectiveRelease.version : '未发布' }}
                </a-tag>
              </a-space>
            </a-list-item>
          </template>
        </a-list>
      </a-card>
    </a-col>

    <a-col :span="17">
      <a-card v-if="selectedKnowledgeBase" :title="selectedKnowledgeBase.name" :loading="loadingWorkspace">
        <template #extra>
          <a-space>
            <a-tag>{{ selectedKnowledgeBase.shareScope }}</a-tag>
            <a-tag v-if="hasPendingOperations" color="processing">后台任务运行中</a-tag>
            <a-button v-if="canManageGovernance" danger ghost @click="handleDeleteKnowledgeBase">删除知识库</a-button>
          </a-space>
        </template>

        <a-tabs>
          <a-tab-pane key="overview" tab="概览">
            <a-descriptions :column="2" size="small">
              <a-descriptions-item label="负责人">{{ selectedKnowledgeBase.steward || '-' }}</a-descriptions-item>
              <a-descriptions-item label="归属">{{ selectedKnowledgeBase.ownerType }} / {{ selectedKnowledgeBase.ownerId }}</a-descriptions-item>
              <a-descriptions-item label="最新发布">{{ selectedKnowledgeBase.latestRelease?.version || '-' }}</a-descriptions-item>
              <a-descriptions-item label="当前生效">{{ selectedKnowledgeBase.effectiveRelease?.version || '-' }}</a-descriptions-item>
              <a-descriptions-item label="摘要" :span="2">{{ selectedKnowledgeBase.summary || '-' }}</a-descriptions-item>
            </a-descriptions>
          </a-tab-pane>

          <a-tab-pane key="imports" tab="内容导入">
            <a-space direction="vertical" style="width: 100%" size="large">
              <a-row :gutter="[16, 16]">
                <a-col :span="12">
                  <a-card size="small" title="文件上传">
                    <template #extra>
                      <a-button type="link" size="small" @click="uploadGuideOpen = true">格式说明</a-button>
                    </template>
                    <a-space direction="vertical" style="width: 100%">
                      <a-upload
                        v-if="canManageGovernance"
                        :before-upload="handleUpload"
                        :show-upload-list="false"
                        :accept="knowledgeUploadAccept"
                      >
                        <a-button type="primary" :loading="uploading">上传文件并导入</a-button>
                      </a-upload>
                      <a-alert
                        v-else
                        type="info"
                        show-icon
                        message="当前角色仅可查看知识内容"
                        description="文件导入需要治理写权限。"
                      />
                      <a-space wrap :size="[0, 8]">
                        <a-tag v-for="card in uploadFormatCards" :key="card.title" color="blue">{{ card.formats }}</a-tag>
                      </a-space>
                      <a-typography-text type="secondary">提交后立即返回，后台异步解析并更新状态。</a-typography-text>
                    </a-space>
                  </a-card>
                </a-col>
                <a-col :span="12">
                  <a-card size="small" title="URL 导入">
                    <a-form layout="vertical" :model="urlImportForm" @finish="handleImportUrl">
                      <a-form-item label="URL">
                        <a-input v-model:value="urlImportForm.url" placeholder="https://example.com/faq" />
                      </a-form-item>
                      <a-form-item label="标题">
                        <a-input v-model:value="urlImportForm.title" placeholder="可选" />
                      </a-form-item>
                      <a-button v-if="canManageGovernance" type="primary" html-type="submit">提交 URL 导入</a-button>
                    </a-form>
                  </a-card>
                </a-col>
              </a-row>

              <a-card size="small" title="源对象">
                <a-list :data-source="fileList" size="small">
                  <template #renderItem="{ item }">
                    <a-list-item>
                      <a-list-item-meta :title="item.fileName" :description="item.sourceUri" />
                      <a-space>
                        <a-tag>{{ knowledgeSourceLabel(item.sourceType) }}</a-tag>
                        <a-tag :color="knowledgeStatusColor(item.status)">{{ item.status }}</a-tag>
                        <a-tag>{{ formatBytes(item.sizeBytes) }}</a-tag>
                      </a-space>
                    </a-list-item>
                  </template>
                </a-list>
              </a-card>

              <a-card size="small" title="导入任务">
                <a-list :data-source="importJobs" size="small">
                  <template #renderItem="{ item }">
                    <a-list-item>
                      <a-space direction="vertical" style="width: 100%">
                        <a-space align="start" style="justify-content: space-between; width: 100%">
                          <a-space wrap>
                            <a-typography-text strong>{{ item.fileName }}</a-typography-text>
                            <a-tag>{{ knowledgeSourceLabel(item.sourceType) }}</a-tag>
                            <a-tag :color="knowledgeStatusColor(item.status)">{{ item.status }}</a-tag>
                            <a-tag>{{ item.stage }}</a-tag>
                            <a-tag>retry {{ item.retryCount }}</a-tag>
                          </a-space>
                          <a-button
                            v-if="canManageGovernance && item.status === 'FAILED' && item.retryable"
                            type="primary"
                            ghost
                            size="small"
                            @click="handleRetryImportJob(item.id)"
                          >
                            重试
                          </a-button>
                        </a-space>
                        <a-progress :percent="item.progressPercent" size="small" />
                        <a-typography-text type="secondary">
                          {{ item.failureReason || item.sourceUri }}
                        </a-typography-text>
                      </a-space>
                    </a-list-item>
                  </template>
                </a-list>
              </a-card>
            </a-space>
          </a-tab-pane>

          <a-tab-pane key="documents" tab="文档">
            <a-list :data-source="documents">
              <template #renderItem="{ item }">
                <a-list-item>
                  <a-list-item-meta :title="item.title" :description="item.sourceUri || item.fileId" />
                  <a-space>
                    <a-tag>{{ item.status }}</a-tag>
                    <a-tag color="blue">{{ item.chunkCount }} chunks</a-tag>
                    <a-button
                      v-if="canManageGovernance"
                      danger
                      ghost
                      size="small"
                      :loading="previewingDocumentDeletion && documentDeletionTargetId === item.id"
                      @click="handleOpenDocumentDeletion(item)"
                    >
                      删除导入文档
                    </a-button>
                  </a-space>
                </a-list-item>
              </template>
            </a-list>
          </a-tab-pane>

          <a-tab-pane key="snapshots" tab="索引快照">
            <a-space direction="vertical" style="width: 100%">
              <a-button
                v-if="canManageGovernance"
                type="primary"
                :loading="creatingSnapshot"
                @click="handleCreateSnapshot"
              >
                基于 READY 文档构建快照
              </a-button>
              <a-list :data-source="snapshots">
                <template #renderItem="{ item }">
                  <a-list-item>
                    <a-space direction="vertical" style="width: 100%">
                      <a-space align="start" style="justify-content: space-between; width: 100%">
                        <a-list-item-meta :title="item.id" :description="`${item.retrievalBackend} / ${item.retrievalMode}`" />
                        <a-space>
                          <a-tag :color="knowledgeStatusColor(item.status)">{{ item.status }}</a-tag>
                          <a-tag>{{ item.stage }}</a-tag>
                          <a-tag>{{ item.documentCount }} docs</a-tag>
                          <a-tag>{{ item.chunkCount }} chunks</a-tag>
                          <a-tag>retry {{ item.retryCount }}</a-tag>
                          <a-button
                            v-if="canManageGovernance && item.status === 'FAILED' && item.retryable"
                            type="primary"
                            ghost
                            size="small"
                            @click="handleRetrySnapshot(item.id)"
                          >
                            重试
                          </a-button>
                        </a-space>
                      </a-space>
                      <a-progress :percent="item.progressPercent" size="small" />
                      <a-typography-text type="secondary">{{ item.failureReason || '索引快照已受理，后台持续推进。' }}</a-typography-text>
                    </a-space>
                  </a-list-item>
                </template>
              </a-list>
            </a-space>
          </a-tab-pane>

          <a-tab-pane key="retrieval-preview" tab="检索验证">
            <a-space direction="vertical" style="width: 100%" size="large">
              <a-card size="small" title="预览查询">
                <a-form layout="vertical" @finish="handlePreviewRetrieval">
                  <a-row :gutter="[16, 16]">
                    <a-col :span="10">
                      <a-form-item label="READY 快照">
                        <a-select
                          v-model:value="retrievalPreviewSnapshotId"
                          :options="readySnapshots.map((item) => ({ label: `${item.id} · ${item.documentCount} docs`, value: item.id }))"
                        />
                      </a-form-item>
                    </a-col>
                    <a-col :span="14">
                      <a-form-item label="查询词">
                        <a-input v-model:value="retrievalPreviewQuery" placeholder="例如：支付失败怎么办" />
                      </a-form-item>
                    </a-col>
                  </a-row>
                  <a-button type="primary" html-type="submit" :loading="previewingRetrieval">执行检索验证</a-button>
                </a-form>
              </a-card>

              <a-alert
                v-if="retrievalPreviewResult?.lowConfidence"
                type="warning"
                show-icon
                message="本次检索结果置信度较低"
                description="没有命中高置信内容，请检查查询词、导入结果或目标快照。"
              />

              <a-list v-if="retrievalPreviewResult && retrievalPreviewResult.hits.length" :data-source="retrievalPreviewResult.hits">
                <template #renderItem="{ item }">
                  <a-list-item>
                    <a-space direction="vertical" style="width: 100%">
                      <a-space wrap>
                        <a-typography-text strong>{{ item.documentTitle }}</a-typography-text>
                        <a-tag color="blue">{{ item.score.toFixed(2) }}</a-tag>
                        <a-tag v-if="item.headingPath">{{ item.headingPath }}</a-tag>
                      </a-space>
                      <a-typography-paragraph :ellipsis="{ rows: 3 }" style="margin-bottom: 0">
                        {{ item.snippet }}
                      </a-typography-paragraph>
                      <a-typography-text type="secondary">{{ item.sourceUri }}</a-typography-text>
                    </a-space>
                  </a-list-item>
                </template>
              </a-list>

              <a-empty
                v-else-if="retrievalPreviewResult"
                description="当前没有可展示的检索命中。"
              />
            </a-space>
          </a-tab-pane>

          <a-tab-pane key="releases" tab="发布版本">
            <a-space direction="vertical" style="width: 100%" size="large">
              <a-card v-if="canManageGovernance" size="small" title="创建发布版本">
                <a-form layout="vertical" :model="releaseForm" @finish="handleCreateRelease">
                  <a-form-item label="发布说明">
                    <a-input v-model:value="releaseForm.summary" placeholder="例如：FAQ 与售后规则更新" />
                  </a-form-item>
                  <a-row :gutter="[16, 16]">
                    <a-col :span="12">
                      <a-form-item label="索引快照">
                        <a-select
                          v-model:value="releaseForm.snapshotId"
                          :options="readySnapshots.map((item) => ({ label: `${item.id} · ${item.documentCount} docs`, value: item.id }))"
                        />
                      </a-form-item>
                    </a-col>
                    <a-col :span="12">
                      <a-form-item label="状态">
                        <a-select
                          v-model:value="releaseForm.status"
                          :options="[
                            { label: '草稿', value: 'DRAFT' },
                            { label: '直接发布', value: 'PUBLISHED' },
                          ]"
                        />
                      </a-form-item>
                    </a-col>
                  </a-row>
                  <a-row :gutter="[16, 16]">
                    <a-col :span="8">
                      <a-form-item label="默认 topK">
                        <a-input-number v-model:value="releaseForm.retrievalProfile.defaultTopK" :min="1" style="width: 100%" />
                      </a-form-item>
                    </a-col>
                    <a-col :span="8">
                      <a-form-item label="检索模式">
                        <a-select
                          v-model:value="releaseForm.retrievalProfile.retrievalMode"
                          :options="[
                            { label: 'HYBRID', value: 'HYBRID' },
                            { label: 'LEXICAL', value: 'LEXICAL' },
                            { label: 'VECTOR', value: 'VECTOR' },
                          ]"
                        />
                      </a-form-item>
                    </a-col>
                    <a-col :span="8">
                      <a-form-item label="最低分数">
                        <a-input-number v-model:value="releaseForm.retrievalProfile.minScore" :min="0" :step="0.1" style="width: 100%" />
                      </a-form-item>
                    </a-col>
                  </a-row>
                  <a-button type="primary" html-type="submit">创建发布版本</a-button>
                </a-form>
              </a-card>

              <a-list :data-source="releases">
                <template #renderItem="{ item }">
                  <a-list-item>
                    <a-list-item-meta :title="`v${item.version}`" :description="item.summary || item.snapshotId" />
                    <a-space>
                      <a-tag :color="item.status === 'PUBLISHED' ? 'green' : 'gold'">{{ item.status }}</a-tag>
                      <a-button
                        v-if="canManageGovernance && item.status !== 'PUBLISHED'"
                        type="primary"
                        ghost
                        @click="handlePublishRelease(item.id)"
                      >
                        发布
                      </a-button>
                      <a-button v-if="canManageGovernance" danger ghost @click="handleDeleteRelease(item.id)">删除</a-button>
                    </a-space>
                  </a-list-item>
                </template>
              </a-list>
            </a-space>
          </a-tab-pane>

          <a-tab-pane key="references" tab="引用分析">
            <ObjectReferencePanel
              :object-id="selectedKnowledgeBase?.id"
              object-type="KNOWLEDGE_BASE"
              :reload-key="catalogRevision"
            />
          </a-tab-pane>
        </a-tabs>
      </a-card>
    </a-col>
  </a-row>

  <a-modal
    :open="documentDeletionOpen"
    :title="documentDeletionPreview?.canDelete ? '删除导入文档' : '文档删除已被阻断'"
    :confirm-loading="deletingDocument"
    :ok-button-props="{ danger: true, disabled: !documentDeletionPreview?.canDelete }"
    :ok-text="documentDeletionPreview?.canDelete ? '确认删除' : '无法删除'"
    cancel-text="取消"
    @ok="handleConfirmDeleteDocument"
    @cancel="closeDocumentDeletionModal"
  >
    <a-space direction="vertical" style="width: 100%" size="middle">
      <a-alert
        v-if="documentDeletionPreview?.canDelete"
        type="warning"
        show-icon
        message="删除后会同步清理导入源对象和解析后的知识内容"
        :description="`会删除源文件、导入任务、文档正文和 ${documentDeletionPreview.chunkCount} 个 chunk。`"
      />
      <a-alert
        v-else-if="documentDeletionPreview"
        type="error"
        show-icon
        message="当前文档已被快照引用，不能删除"
        description="根据当前策略，只要文档已经进入任何快照，就必须先处理快照再删除文档。"
      />

      <a-descriptions v-if="documentDeletionPreview" :column="1" bordered size="small">
        <a-descriptions-item label="标题">{{ documentDeletionPreview.title }}</a-descriptions-item>
        <a-descriptions-item label="源文件">{{ documentDeletionPreview.fileName }}</a-descriptions-item>
        <a-descriptions-item label="来源">{{ documentDeletionPreview.sourceUri }}</a-descriptions-item>
        <a-descriptions-item label="Chunk 数">{{ documentDeletionPreview.chunkCount }}</a-descriptions-item>
      </a-descriptions>

      <a-card
        v-if="documentDeletionPreview && documentDeletionPreview.blockers.length"
        size="small"
        title="阻断删除的快照"
      >
        <a-list :data-source="documentDeletionPreview.blockers" size="small">
          <template #renderItem="{ item }">
            <a-list-item>
              <a-space direction="vertical" style="width: 100%">
                <a-space wrap>
                  <a-typography-text strong>{{ item.snapshotId }}</a-typography-text>
                  <a-tag :color="knowledgeStatusColor(item.status)">{{ item.status }}</a-tag>
                  <a-tag>{{ item.stage }}</a-tag>
                  <a-tag>{{ item.retrievalMode }}</a-tag>
                </a-space>
                <a-typography-text type="secondary">{{ item.reason }}</a-typography-text>
              </a-space>
            </a-list-item>
          </template>
        </a-list>
      </a-card>
    </a-space>
  </a-modal>

  <a-modal
    :open="uploadGuideOpen"
    title="文档格式说明"
    width="760px"
    @cancel="uploadGuideOpen = false"
  >
    <a-space direction="vertical" size="large" style="width: 100%">
      <a-alert
        type="info"
        show-icon
        message="当前支持上传 .pdf、.docx、.md、.txt、.html、.csv"
        description="系统会先抽取文本，再按标题、段落或表格行切分为知识片段。结构越清晰，后续检索效果越稳定。"
      />

      <a-row :gutter="[16, 16]">
        <a-col v-for="card in uploadFormatCards" :key="card.title" :span="8">
          <a-card size="small" :title="card.title">
            <a-space direction="vertical" size="small">
              <a-typography-text strong>{{ card.formats }}</a-typography-text>
              <a-typography-text type="secondary">{{ card.description }}</a-typography-text>
            </a-space>
          </a-card>
        </a-col>
      </a-row>

      <a-card size="small" title="文件内容格式建议">
        <a-list :data-source="uploadContentRecommendations" size="small" bordered>
          <template #renderItem="{ item }">
            <a-list-item>{{ item }}</a-list-item>
          </template>
        </a-list>
      </a-card>

      <a-card size="small" title="不建议上传的内容">
        <a-list :data-source="uploadContentWarnings" size="small" bordered>
          <template #renderItem="{ item }">
            <a-list-item>{{ item }}</a-list-item>
          </template>
        </a-list>
      </a-card>

      <a-card size="small" title="推荐结构示例">
        <a-typography-paragraph type="secondary">
          例如 Markdown 可以按“主题 / 子主题 / FAQ”组织，方便系统按标题层级切分：
        </a-typography-paragraph>
        <pre style="margin: 0; padding: 12px; background: #f7f8fa; border-radius: 8px; overflow: auto"><code># 退款规则
## 适用范围
说明适用于哪些订单、渠道和时间限制。

## 常见问题
### 未发货能否退款
可以，提交申请后原路退回。

### 已发货如何处理
说明退货地址、时限和审核条件。</code></pre>
      </a-card>
    </a-space>

    <template #footer>
      <a-button type="primary" @click="uploadGuideOpen = false">我知道了</a-button>
    </template>
  </a-modal>
</template>
