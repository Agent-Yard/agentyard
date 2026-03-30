import { message } from 'ant-design-vue';
import { api } from '../services/api';
import type {
  CreateAssistantPayload,
  CreateAgentPayload,
  CreateKnowledgeBasePayload,
  CreateResourcePayload,
  CreateResourceVersionPayload,
  CreateScenarioPayload,
  CreateDomainPayload,
  UpdateAgentPayload,
  UpdateAssistantPayload,
  UpdateDomainPayload,
  UpdateOrchestrationPayload,
  UpdateResourcePayload,
  UpdateResourceVersionPayload,
  UpdateScenarioPayload,
} from '../types';
import type { PageKey } from '../config/navigation';

interface CatalogActionState {
  activeKey: { value: PageKey };
  knowledgeLibraryPreferredKnowledgeBaseId: { value: string | null };
  resourceLibraryPreferredResourceId: { value: string | null };
  resourceLibraryPreferredVersionId: { value: string | null };
}

export function useCatalogActions(
  state: CatalogActionState,
  refresh: (showLoading?: boolean) => Promise<void>,
  errorMessage: (error: unknown, fallback: string) => string,
) {
  async function handleCreateAssistant(payload: CreateAssistantPayload) {
    try {
      await api.createAssistant(payload);
      await refresh();
      void message.success('助手已创建');
    } catch (error) {
      void message.error(errorMessage(error, '创建助手失败'));
    }
  }

  async function handleCreateDomain(payload: CreateDomainPayload) {
    try {
      await api.createDomain(payload);
      await refresh();
      void message.success('业务域已创建');
    } catch (error) {
      void message.error(errorMessage(error, '创建业务域失败'));
    }
  }

  async function handleUpdateDomain(payload: { domainId: string; data: UpdateDomainPayload }) {
    try {
      await api.updateDomain(payload.domainId, payload.data);
      await refresh();
      void message.success('业务域已更新');
    } catch (error) {
      void message.error(errorMessage(error, '更新业务域失败'));
    }
  }

  async function handleDeleteDomain(domainId: string) {
    try {
      await api.deleteDomain(domainId);
      await refresh();
      void message.success('业务域已删除');
    } catch (error) {
      void message.error(errorMessage(error, '删除业务域失败'));
    }
  }

  async function handleCreateScenario(payload: CreateScenarioPayload) {
    try {
      await api.createScenario(payload);
      await refresh();
      void message.success('业务场景已创建');
    } catch (error) {
      void message.error(errorMessage(error, '创建业务场景失败'));
    }
  }

  async function handleUpdateScenario(payload: { scenarioId: string; data: UpdateScenarioPayload }) {
    try {
      await api.updateScenario(payload.scenarioId, payload.data);
      await refresh();
      void message.success('业务场景已更新');
    } catch (error) {
      void message.error(errorMessage(error, '更新业务场景失败'));
    }
  }

  async function handleDeleteScenario(scenarioId: string) {
    try {
      await api.deleteScenario(scenarioId);
      await refresh();
      void message.success('业务场景已删除');
    } catch (error) {
      void message.error(errorMessage(error, '删除业务场景失败'));
    }
  }

  async function handleUpdateAssistant(payload: { assistantId: string; data: UpdateAssistantPayload }) {
    try {
      await api.updateAssistant(payload.assistantId, payload.data);
      await refresh();
      void message.success('助手已更新');
    } catch (error) {
      void message.error(errorMessage(error, '更新助手失败'));
    }
  }

  async function handleDeleteAssistant(assistantId: string) {
    try {
      await api.deleteAssistant(assistantId);
      await refresh();
      void message.success('助手已删除');
    } catch (error) {
      void message.error(errorMessage(error, '删除助手失败'));
    }
  }

  async function handleCreateAgent(payload: CreateAgentPayload) {
    try {
      await api.createAgent(payload);
      await refresh();
      void message.success('智能体已创建');
    } catch (error) {
      void message.error(errorMessage(error, '创建智能体失败'));
    }
  }

  async function handleDeleteAgent(agentId: string) {
    try {
      await api.deleteAgent(agentId);
      await refresh();
      void message.success('智能体已删除');
    } catch (error) {
      void message.error(errorMessage(error, '删除智能体失败'));
    }
  }

  async function handleSaveAgent(payload: { agentId: string; agent: UpdateAgentPayload }) {
    try {
      await api.updateAgent(payload.agentId, payload.agent);
      await refresh();
      void message.success('智能体配置已保存');
    } catch (error) {
      void message.error(errorMessage(error, '保存智能体失败'));
    }
  }

  async function handleSaveOrchestration(payload: { assistantId: string; data: UpdateOrchestrationPayload }) {
    try {
      await api.saveOrchestration(payload.assistantId, payload.data);
      await refresh();
      void message.success('编排设计已保存');
    } catch (error) {
      void message.error(errorMessage(error, '保存编排失败'));
    }
  }

  async function handleCreateKnowledgeBase(payload: CreateKnowledgeBasePayload) {
    try {
      const created = await api.createKnowledgeBase(payload);
      await refresh();
      state.knowledgeLibraryPreferredKnowledgeBaseId.value = created.id;
      state.activeKey.value = 'knowledge-library';
      void message.success('知识库已创建');
    } catch (error) {
      void message.error(errorMessage(error, '创建知识库失败'));
    }
  }

  async function handleCreateResource(payload: CreateResourcePayload) {
    try {
      const created = await api.createResource(payload);
      await refresh();
      state.resourceLibraryPreferredResourceId.value = created.id;
      state.resourceLibraryPreferredVersionId.value = created.latestVersion?.id ?? null;
      state.activeKey.value = 'resource-library';
      void message.success('资源已创建');
    } catch (error) {
      void message.error(errorMessage(error, '创建资源失败'));
    }
  }

  async function handleDeleteResource(resourceId: string) {
    try {
      await api.deleteResource(resourceId);
      await refresh();
      void message.success('资源已删除');
    } catch (error) {
      void message.error(errorMessage(error, '删除资源失败'));
    }
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
    handleCreateAssistant,
    handleCreateDomain,
    handleUpdateDomain,
    handleDeleteDomain,
    handleCreateScenario,
    handleUpdateScenario,
    handleDeleteScenario,
    handleUpdateAssistant,
    handleDeleteAssistant,
    handleCreateAgent,
    handleDeleteAgent,
    handleSaveAgent,
    handleSaveOrchestration,
    handleCreateKnowledgeBase,
    handleCreateResource,
    handleDeleteResource,
    handleUpdateResource,
    handleCreateResourceVersion,
    handleUpdateResourceVersion,
    handleDeleteResourceVersion,
    handlePublishResourceVersion,
  };
}
