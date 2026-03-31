<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { message } from 'ant-design-vue';
import ObjectReferencePanel from '../components/ObjectReferencePanel.vue';
import { api } from '../services/api';
import type {
  CreateKnowledgeReleasePayload,
  KnowledgeBase,
  KnowledgeDocument,
  KnowledgeFile,
  KnowledgeImportJob,
  KnowledgeIndexSnapshot,
  KnowledgeRelease,
} from '../types';

const props = defineProps<{
  knowledgeBases: KnowledgeBase[];
  preferredKnowledgeBaseId?: string | null;
  catalogRevision: number;
}>();

const emit = defineEmits<{
  refreshCatalog: [];
}>();

const selectedKnowledgeBaseId = ref('');
const loadingWorkspace = ref(false);
const fileList = ref<KnowledgeFile[]>([]);
const importJobs = ref<KnowledgeImportJob[]>([]);
const documents = ref<KnowledgeDocument[]>([]);
const snapshots = ref<KnowledgeIndexSnapshot[]>([]);
const releases = ref<KnowledgeRelease[]>([]);
const uploading = ref(false);
const creatingSnapshot = ref(false);
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

const selectedKnowledgeBase = computed(() =>
  props.knowledgeBases.find((item) => item.id === selectedKnowledgeBaseId.value) ?? props.knowledgeBases[0] ?? null,
);
const readySnapshots = computed(() => snapshots.value.filter((item) => item.status === 'READY'));

async function loadWorkspace() {
  if (!selectedKnowledgeBase.value) {
    fileList.value = [];
    importJobs.value = [];
    documents.value = [];
    snapshots.value = [];
    releases.value = [];
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
  } finally {
    loadingWorkspace.value = false;
  }
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
  void loadWorkspace();
});

onMounted(() => {
  void loadWorkspace();
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
    void message.success('文件已提交导入');
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
    void message.success('URL 已提交导入');
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
    void message.success('索引快照构建已触发');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '创建索引快照失败');
  } finally {
    creatingSnapshot.value = false;
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
  try {
    await api.deleteKnowledgeBase(selectedKnowledgeBase.value.id);
    emit('refreshCatalog');
    void message.success('知识库已删除');
  } catch (error) {
    void message.error(error instanceof Error ? error.message : '删除知识库失败');
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
            <a-button danger ghost @click="handleDeleteKnowledgeBase">删除知识库</a-button>
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
              <a-upload :before-upload="handleUpload" :show-upload-list="false">
                <a-button type="primary" :loading="uploading">上传文件并导入</a-button>
              </a-upload>

              <a-card size="small" title="URL 导入">
                <a-form layout="vertical" :model="urlImportForm" @finish="handleImportUrl">
                  <a-form-item label="URL">
                    <a-input v-model:value="urlImportForm.url" placeholder="https://example.com/faq" />
                  </a-form-item>
                  <a-form-item label="标题">
                    <a-input v-model:value="urlImportForm.title" placeholder="可选" />
                  </a-form-item>
                  <a-button type="primary" html-type="submit">提交 URL 导入</a-button>
                </a-form>
              </a-card>

              <a-card size="small" title="导入任务">
                <a-list :data-source="importJobs" size="small">
                  <template #renderItem="{ item }">
                    <a-list-item>
                      <a-space direction="vertical" style="width: 100%">
                        <a-space>
                          <a-typography-text strong>{{ item.id }}</a-typography-text>
                          <a-tag>{{ item.status }}</a-tag>
                        </a-space>
                        <a-typography-text type="secondary">{{ item.failureReason || '等待知识服务处理。' }}</a-typography-text>
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
                  </a-space>
                </a-list-item>
              </template>
            </a-list>
          </a-tab-pane>

          <a-tab-pane key="snapshots" tab="索引快照">
            <a-space direction="vertical" style="width: 100%">
              <a-button type="primary" :loading="creatingSnapshot" @click="handleCreateSnapshot">基于 READY 文档构建快照</a-button>
              <a-list :data-source="snapshots">
                <template #renderItem="{ item }">
                  <a-list-item>
                    <a-list-item-meta :title="item.id" :description="`${item.retrievalBackend} / ${item.retrievalMode}`" />
                    <a-space>
                      <a-tag :color="item.status === 'READY' ? 'green' : item.status === 'FAILED' ? 'red' : 'gold'">{{ item.status }}</a-tag>
                      <a-tag>{{ item.documentCount }} docs</a-tag>
                      <a-tag>{{ item.chunkCount }} chunks</a-tag>
                    </a-space>
                  </a-list-item>
                </template>
              </a-list>
            </a-space>
          </a-tab-pane>

          <a-tab-pane key="releases" tab="发布版本">
            <a-space direction="vertical" style="width: 100%" size="large">
              <a-card size="small" title="创建发布版本">
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
                      <a-button v-if="item.status !== 'PUBLISHED'" type="primary" ghost @click="handlePublishRelease(item.id)">发布</a-button>
                      <a-button danger ghost @click="handleDeleteRelease(item.id)">删除</a-button>
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
</template>
