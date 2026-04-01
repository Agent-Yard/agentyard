import { ref } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useCatalogActions } from './useCatalogActions';

const { messageSuccess, messageError, routerPush, apiMock } = vi.hoisted(() => ({
  messageSuccess: vi.fn(),
  messageError: vi.fn(),
  routerPush: vi.fn(),
  apiMock: {
    getDeletionImpactPreview: vi.fn(),
    deleteDomain: vi.fn(),
    deleteKnowledgeBase: vi.fn(),
    createKnowledgeBase: vi.fn(),
  },
}));

vi.mock('ant-design-vue', () => ({
  message: {
    success: messageSuccess,
    error: messageError,
  },
}));

vi.mock('../services/api', () => ({
  api: apiMock,
}));

vi.mock('../router', () => ({
  router: {
    push: routerPush,
  },
}));

function makeState() {
  return {
    knowledgeLibraryPreferredKnowledgeBaseId: ref<string | null>('knowledge-1'),
    resourceLibraryPreferredResourceId: ref<string | null>(null),
    resourceLibraryPreferredVersionId: ref<string | null>(null),
  };
}

function blockedPreview() {
  return {
    objectType: 'DOMAIN' as const,
    objectId: 'domain-1',
    objectName: '客服域',
    canDelete: false,
    blockers: [
      {
        relationKind: 'DOMAIN_SCENARIO',
        relationRole: 'CONTAINS',
        relationMode: 'DIRECT' as const,
        impactLevel: 'BLOCKS_DELETION' as const,
        targetType: 'SCENARIO',
        targetId: 'scenario-1',
        targetName: '客服场景',
        releaseId: null,
        releaseVersion: null,
        resourceVersionId: null,
        resourceVersion: null,
        knowledgeReleaseId: null,
        knowledgeReleaseVersion: null,
      },
    ],
    advisories: [],
    cascadeDeletes: [],
  };
}

function allowedPreview(objectType: 'DOMAIN' | 'KNOWLEDGE_BASE', objectId: string, objectName: string) {
  return {
    objectType,
    objectId,
    objectName,
    canDelete: true,
    blockers: [],
    advisories: [],
    cascadeDeletes: [],
  };
}

describe('useCatalogActions deletion flow', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('opens deletion preview before deleting a domain', async () => {
    apiMock.getDeletionImpactPreview.mockResolvedValue(blockedPreview());
    const state = makeState();
    const refresh = vi.fn();
    const actions = useCatalogActions(state, refresh, (_, fallback) => fallback);

    await actions.handleDeleteDomain('domain-1');

    expect(apiMock.getDeletionImpactPreview).toHaveBeenCalledWith('DOMAIN', 'domain-1');
    expect(apiMock.deleteDomain).not.toHaveBeenCalled();
    expect(actions.deletionPreviewOpen.value).toBe(true);
    expect(actions.deletionPreview.value?.objectId).toBe('domain-1');
  });

  it('does not execute delete when preview is blocked', async () => {
    apiMock.getDeletionImpactPreview.mockResolvedValue(blockedPreview());
    const state = makeState();
    const refresh = vi.fn();
    const actions = useCatalogActions(state, refresh, (_, fallback) => fallback);

    await actions.handleDeleteDomain('domain-1');
    await actions.confirmDeletionPreview();

    expect(apiMock.deleteDomain).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
    expect(actions.deletionPreviewOpen.value).toBe(true);
  });

  it('executes delete after confirmation when preview allows deletion', async () => {
    apiMock.getDeletionImpactPreview.mockResolvedValue(allowedPreview('DOMAIN', 'domain-1', '客服域'));
    apiMock.deleteDomain.mockResolvedValue({ id: 'domain-1' });
    const state = makeState();
    const refresh = vi.fn().mockResolvedValue(undefined);
    const actions = useCatalogActions(state, refresh, (_, fallback) => fallback);

    await actions.handleDeleteDomain('domain-1');
    await actions.confirmDeletionPreview();

    expect(apiMock.deleteDomain).toHaveBeenCalledWith('domain-1');
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(actions.deletionPreviewOpen.value).toBe(false);
    expect(messageSuccess).toHaveBeenCalledWith('业务域已删除');
  });

  it('clears preferred knowledge base after confirmed deletion', async () => {
    apiMock.getDeletionImpactPreview.mockResolvedValue(allowedPreview('KNOWLEDGE_BASE', 'knowledge-1', '客服知识库'));
    apiMock.deleteKnowledgeBase.mockResolvedValue({ id: 'knowledge-1' });
    const state = makeState();
    const refresh = vi.fn().mockResolvedValue(undefined);
    const actions = useCatalogActions(state, refresh, (_, fallback) => fallback);

    await actions.handleDeleteKnowledgeBase('knowledge-1');
    await actions.confirmDeletionPreview();

    expect(apiMock.deleteKnowledgeBase).toHaveBeenCalledWith('knowledge-1');
    expect(state.knowledgeLibraryPreferredKnowledgeBaseId.value).toBeNull();
  });

  it('navigates to knowledge library after creation', async () => {
    apiMock.createKnowledgeBase.mockResolvedValue({ id: 'knowledge-9' });
    const state = makeState();
    const refresh = vi.fn().mockResolvedValue(undefined);
    const actions = useCatalogActions(state, refresh, (_, fallback) => fallback);

    await actions.handleCreateKnowledgeBase({
      domainId: 'domain-1',
      name: '售后知识库',
      shareScope: 'DOMAIN_SHARED',
      ownerType: 'DOMAIN',
      ownerId: 'domain-1',
      summary: 'desc',
      steward: 'ops',
      tags: [],
    });

    expect(state.knowledgeLibraryPreferredKnowledgeBaseId.value).toBe('knowledge-9');
    expect(routerPush).toHaveBeenCalledWith('/console/knowledge');
  });
});
