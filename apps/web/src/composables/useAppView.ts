import { computed, type ComputedRef } from 'vue';
import DomainPage from '../pages/DomainPage.vue';
import ScenarioPage from '../pages/ScenarioPage.vue';
import AssistantPage from '../pages/AssistantPage.vue';
import AgentPage from '../pages/AgentPage.vue';
import OrchestrationPage from '../pages/OrchestrationPage.vue';
import KnowledgeLibraryPage from '../pages/KnowledgeLibraryPage.vue';
import KnowledgeCreatePage from '../pages/KnowledgeCreatePage.vue';
import ResourceLibraryPage from '../pages/ResourceLibraryPage.vue';
import ResourceCreatePage from '../pages/ResourceCreatePage.vue';
import RuntimeConversationPage from '../pages/RuntimeConversationPage.vue';
import WorkflowPage from '../pages/WorkflowPage.vue';
import type { PageKey } from '../config/navigation';
import type { useAppState } from './useAppState';
import type { useCatalogActions } from './useCatalogActions';
import type { useRuntimeActions } from './useRuntimeActions';

type AppState = ReturnType<typeof useAppState>;
type CatalogActions = ReturnType<typeof useCatalogActions>;
type RuntimeActions = ReturnType<typeof useRuntimeActions>;

export function useAppView(state: AppState, catalogActions: CatalogActions, runtimeActions: RuntimeActions) {
  const pageRegistry = {
    domain: {
      component: DomainPage,
      props: computed(() => ({
        domains: state.catalog.value!.domains,
        catalogRevision: state.catalogRevision.value,
        canManageGovernance: state.canManageGovernance.value,
      })),
      handlers: {
        createDomain: catalogActions.handleCreateDomain,
        updateDomain: catalogActions.handleUpdateDomain,
        deleteDomain: catalogActions.handleDeleteDomain,
      },
    },
    scenario: {
      component: ScenarioPage,
      props: computed(() => ({
        domains: state.catalog.value!.domains,
        scenarios: state.catalog.value!.scenarios,
        catalogRevision: state.catalogRevision.value,
        canManageGovernance: state.canManageGovernance.value,
      })),
      handlers: {
        createScenario: catalogActions.handleCreateScenario,
        updateScenario: catalogActions.handleUpdateScenario,
        deleteScenario: catalogActions.handleDeleteScenario,
      },
    },
    assistant: {
      component: AssistantPage,
      props: computed(() => ({
        assistants: state.catalog.value!.assistants,
        scenarios: state.catalog.value!.scenarios,
        resources: state.catalog.value!.resources,
        knowledgeBases: state.catalog.value!.knowledgeBases,
        workflows: state.workflows.value,
        catalogRevision: state.catalogRevision.value,
        canManageGovernance: state.canManageGovernance.value,
      })),
      handlers: {
        createAssistant: catalogActions.handleCreateAssistant,
        updateAssistant: catalogActions.handleUpdateAssistant,
        deleteAssistant: catalogActions.handleDeleteAssistant,
        openWorkflow: (workflowId: string) => {
          state.selectedWorkflowId.value = workflowId;
          state.activeKey.value = 'workflow';
        },
      },
    },
    agent: {
      component: AgentPage,
      props: computed(() => ({
        assistants: state.catalog.value!.assistants,
        agents: state.catalog.value!.agents,
        resources: state.catalog.value!.resources,
        knowledgeBases: state.catalog.value!.knowledgeBases,
        catalogRevision: state.catalogRevision.value,
        canManageGovernance: state.canManageGovernance.value,
      })),
      handlers: {
        createAgent: catalogActions.handleCreateAgent,
        saveAgent: catalogActions.handleSaveAgent,
        deleteAgent: catalogActions.handleDeleteAgent,
      },
    },
    orchestration: {
      component: OrchestrationPage,
      props: computed(() => ({
        assistants: state.catalog.value!.assistants,
        orchestrations: state.catalog.value!.orchestrations,
        resources: state.catalog.value!.resources,
        canManageGovernance: state.canManageGovernance.value,
      })),
      handlers: { saveOrchestration: catalogActions.handleSaveOrchestration },
    },
    'knowledge-library': {
      component: KnowledgeLibraryPage,
      props: computed(() => ({
        knowledgeBases: state.catalog.value!.knowledgeBases,
        preferredKnowledgeBaseId: state.knowledgeLibraryPreferredKnowledgeBaseId.value,
        catalogRevision: state.catalogRevision.value,
        canManageGovernance: state.canManageGovernance.value,
      })),
      handlers: {
        refreshCatalog: state.refresh,
        deleteKnowledgeBase: catalogActions.handleDeleteKnowledgeBase,
      },
    },
    'knowledge-create': {
      component: KnowledgeCreatePage,
      props: computed(() => ({
        domains: state.catalog.value!.domains,
        assistants: state.catalog.value!.assistants,
        canManageGovernance: state.canManageGovernance.value,
      })),
      handlers: { createKnowledgeBase: catalogActions.handleCreateKnowledgeBase },
    },
    'resource-library': {
      component: ResourceLibraryPage,
      props: computed(() => ({
        domains: state.catalog.value!.domains,
        resourceCenter: state.catalog.value!.resourceCenter,
        resources: state.catalog.value!.resources,
        preferredResourceId: state.resourceLibraryPreferredResourceId.value,
        preferredVersionId: state.resourceLibraryPreferredVersionId.value,
        catalogRevision: state.catalogRevision.value,
        canManageGovernance: state.canManageGovernance.value,
      })),
      handlers: {
        deleteResource: catalogActions.handleDeleteResource,
        updateResource: catalogActions.handleUpdateResource,
        createResourceVersion: catalogActions.handleCreateResourceVersion,
        updateResourceVersion: catalogActions.handleUpdateResourceVersion,
        deleteResourceVersion: catalogActions.handleDeleteResourceVersion,
        publishResourceVersion: catalogActions.handlePublishResourceVersion,
      },
    },
    'resource-create': {
      component: ResourceCreatePage,
      props: computed(() => ({
        domains: state.catalog.value!.domains,
        assistants: state.catalog.value!.assistants,
        resourceBlueprints: state.catalog.value!.resourceBlueprints,
        canManageGovernance: state.canManageGovernance.value,
      })),
      handlers: { createResource: catalogActions.handleCreateResource },
    },
    runtime: {
      component: RuntimeConversationPage,
      props: computed(() => ({
        scenarios: state.catalog.value!.scenarios,
        assistants: state.catalog.value!.assistants,
        sessions: state.conversationSessions.value,
        tasks: state.tasks.value,
        workflows: state.workflows.value,
        creatingSession: state.creatingSession.value,
        sendingSessionId: state.sendingSessionId.value,
        preferredSessionId: state.runtimePreferredSessionId.value,
        selectedSessionId: state.runtimeSelectedSessionId.value,
        currentCustomerId: state.session.value?.userId ?? null,
      })),
      handlers: {
        selectSession: runtimeActions.handleSelectRuntimeSession,
        createSession: runtimeActions.handleCreateSession,
        sendMessage: runtimeActions.handleSendMessage,
      },
    },
    workflow: {
      component: WorkflowPage,
      props: computed(() => ({
        workflow: state.currentWorkflow.value,
        workflows: state.workflows.value,
        selectedWorkflowId: state.selectedWorkflowId.value,
        currentUserId: state.session.value?.userId ?? null,
      })),
      handlers: {
        selectWorkflow: runtimeActions.handleSelectWorkflow,
        resumeAction: runtimeActions.handleResumeAction,
      },
    },
  } satisfies Record<PageKey, { component: object; props: ComputedRef<object>; handlers: Record<string, (...args: any[]) => any> }>;

  const currentView = computed(() => pageRegistry[state.activeKey.value]);

  return { currentView };
}
