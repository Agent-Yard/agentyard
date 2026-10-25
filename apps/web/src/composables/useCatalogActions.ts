import { ref } from 'vue';
import { message } from 'ant-design-vue';
import { api } from '../services/api';
import { pagePathByKey } from '../config/navigation';
import { router } from '../router';
import type {
  CreateKnowledgeBasePayload,
  CreateResourcePayload,
  CreateResourceVersionPayload,
  DeletionImpactPreview,
  ReferenceObjectType,
  UpdateKnowledgeBasePayload,
  UpdateResourcePayload,
  UpdateResourceVersionPayload,
} from '../types';

interface CatalogActionState {
  knowledgeLibraryPreferredKnowledgeBaseId: { value: string | null };
  resourceLibraryPreferredResourceId: { value: string | null };
  resourceLibraryPreferredVersionId: { value: string | null };
}

interface DeletionFlowConfig {
  objectType: ReferenceObjectType;
  objectId: string;
  successMessage: string;
  failureMessage: string;
  execute: () => Promise<void>;
  afterSuccess?: () => void;
}

export function useCatalogActions(
  state: CatalogActionState,
  refresh: (showLoading?: boolean) => Promise<void>,
  errorMessage: (error: unknown, fallback: string) => string,
) {
  const deletionPreviewOpen = ref(false);
  const deletionPreviewConfirming = ref(false);
  const deletionPreview = ref<DeletionImpactPreview | null>(null);
  const pendingDeletion = ref<DeletionFlowConfig | null>(null);

  function resetDeletionPreview() {
    deletionPreviewOpen.value = false;
    deletionPreview.value = null;
    pendingDeletion.value = null;
  }

  async function openDeletionPreview(config: DeletionFlowConfig) {
    try {
      const preview = await api.getDeletionImpactPreview(config.objectType, config.objectId);
      pendingDeletion.value = config;
      deletionPreview.value = preview;
      deletionPreviewOpen.value = true;
    } catch (error) {
      void message.error(errorMessage(error, `加载删除预览失败：${config.failureMessage}`));
    }
  }

  async function confirmDeletionPreview() {
    const config = pendingDeletion.value;
    const preview = deletionPreview.value;
    if (!config || !preview || !preview.canDelete) {
      return;
    }

    deletionPreviewConfirming.value = true;
    try {
      await config.execute();
      config.afterSuccess?.();
      resetDeletionPreview();
      await refresh();
      void message.success(config.successMessage);
    } catch (error) {
      void message.error(errorMessage(error, config.failureMessage));
    } finally {
      deletionPreviewConfirming.value = false;
    }
  }

  function closeDeletionPreview() {
    if (deletionPreviewConfirming.value) {
      return;
    }
    resetDeletionPreview();
  }

  async function handleCreateKnowledgeBase(payload: CreateKnowledgeBasePayload) {
    try {
      const created = await api.createKnowledgeBase(payload);
      await refresh();
      state.knowledgeLibraryPreferredKnowledgeBaseId.value = created.id;
      void router.push(pagePathByKey['knowledge-library']);
      void message.success('知识库已创建');
    } catch (error) {
      void message.error(errorMessage(error, '创建知识库失败'));
    }
  }

  async function handleUpdateKnowledgeBase(payload: { knowledgeBaseId: string; data: UpdateKnowledgeBasePayload }) {
    try {
      await api.updateKnowledgeBase(payload.knowledgeBaseId, payload.data);
      await refresh();
      state.knowledgeLibraryPreferredKnowledgeBaseId.value = payload.knowledgeBaseId;
      void message.success('知识库已更新');
    } catch (error) {
      void message.error(errorMessage(error, '更新知识库失败'));
    }
  }

  async function handleDeleteKnowledgeBase(knowledgeBaseId: string) {
    await openDeletionPreview({
      objectType: 'KNOWLEDGE_BASE',
      objectId: knowledgeBaseId,
      successMessage: '知识库已删除',
      failureMessage: '删除知识库失败',
      execute: () => api.deleteKnowledgeBase(knowledgeBaseId).then(() => undefined),
      afterSuccess: () => {
        state.knowledgeLibraryPreferredKnowledgeBaseId.value = null;
      },
    });
  }

  async function handleCreateResource(payload: CreateResourcePayload) {
    try {
      const created = await api.createResource(payload);
      await refresh();
      state.resourceLibraryPreferredResourceId.value = created.id;
      state.resourceLibraryPreferredVersionId.value = created.latestVersion?.id ?? null;
      void router.push(pagePathByKey['resource-library']);
      void message.success('资源已创建');
    } catch (error) {
      void message.error(errorMessage(error, '创建资源失败'));
    }
  }

  async function handleDeleteResource(resourceId: string) {
    await openDeletionPreview({
      objectType: 'RESOURCE',
      objectId: resourceId,
      successMessage: '资源已删除',
      failureMessage: '删除资源失败',
      execute: () => api.deleteResource(resourceId).then(() => undefined),
      afterSuccess: () => {
        state.resourceLibraryPreferredResourceId.value = null;
        state.resourceLibraryPreferredVersionId.value = null;
      },
    });
  }

  async function handleUpdateResource(payload: { resourceId: string; resource: UpdateResourcePayload }) {
    try {
      await api.updateResource(payload.resourceId, payload.resource);
      await refresh();
      state.resourceLibraryPreferredResourceId.value = payload.resourceId;
      void message.success('资源信息已保存');
    } catch (error) {
      void message.error(errorMessage(error, '保存资源信息失败'));
    }
  }

  async function handleCreateResourceVersion(payload: { resourceId: string; data: CreateResourceVersionPayload }) {
    try {
      const created = await api.createResourceVersion(payload.resourceId, payload.data);
      await refresh();
      state.resourceLibraryPreferredResourceId.value = payload.resourceId;
      state.resourceLibraryPreferredVersionId.value = created.id;
      void message.success('资源版本已创建');
    } catch (error) {
      void message.error(errorMessage(error, '创建资源版本失败'));
    }
  }

  async function handleUpdateResourceVersion(payload: {
    resourceId: string;
    versionId: string;
    version: UpdateResourceVersionPayload;
  }) {
    try {
      await api.updateResourceVersion(payload.resourceId, payload.versionId, payload.version);
      await refresh();
      state.resourceLibraryPreferredResourceId.value = payload.resourceId;
      state.resourceLibraryPreferredVersionId.value = payload.versionId;
      void message.success(payload.version.status === 'PUBLISHED' ? '草稿版本已保存并发布' : '草稿版本已保存');
    } catch (error) {
      void message.error(errorMessage(error, '保存资源版本失败'));
    }
  }

  async function handleDeleteResourceVersion(payload: { resourceId: string; versionId: string }) {
    try {
      await api.deleteResourceVersion(payload.resourceId, payload.versionId);
      await refresh();
      void message.success('资源版本已删除');
    } catch (error) {
      void message.error(errorMessage(error, '删除资源版本失败'));
    }
  }

  async function handlePublishResourceVersion(payload: { resourceId: string; versionId: string }) {
    try {
      await api.publishResourceVersion(payload.resourceId, payload.versionId);
      await refresh();
      void message.success('资源版本已发布');
    } catch (error) {
      void message.error(errorMessage(error, '发布资源版本失败'));
    }
  }

  return {
    deletionPreviewOpen,
    deletionPreviewConfirming,
    deletionPreview,
    confirmDeletionPreview,
    closeDeletionPreview,
    handleCreateKnowledgeBase,
    handleUpdateKnowledgeBase,
    handleDeleteKnowledgeBase,
    handleCreateResource,
    handleDeleteResource,
    handleUpdateResource,
    handleCreateResourceVersion,
    handleUpdateResourceVersion,
    handleDeleteResourceVersion,
    handlePublishResourceVersion,
  };
}
