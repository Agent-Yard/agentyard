package com.lynxus.platform.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.runtime.WorkflowContracts.ResourceType;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
import com.lynxus.platform.knowledge.KnowledgeServiceClient;
import com.lynxus.platform.knowledge.KnowledgeWorkflowGateway;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogServiceTest {
    private final CatalogService service = catalogService();

    @Test
    void shouldExposeSeededCatalogSummaryWithIndependentKnowledgeBases() {
        CatalogDtos.CatalogSummaryDto summary = service.summary();

        assertFalse(summary.domains().isEmpty());
        assertFalse(summary.resources().isEmpty());
        assertFalse(summary.knowledgeBases().isEmpty());
        assertEquals("客服知识库", summary.knowledgeBases().getFirst().name());
        assertTrue(summary.resources().stream().noneMatch(resource -> "客服知识库".equals(resource.name())));
    }

    @Test
    void shouldSupportKnowledgeBaseCrudAndReleaseLifecycle() {
        CatalogService catalogService = new CatalogService(
            new InMemoryCatalogRepository(),
            false,
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway()
        );
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("知识运营域", "承载知识沉淀"));

        CatalogDtos.KnowledgeBaseDto knowledgeBase = catalogService.createKnowledgeBase(
            new CatalogDtos.CreateKnowledgeBaseRequest(
                domain.id(),
                "人工知识库",
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "用于手工导入 FAQ",
                "知识运营",
                List.of("FAQ")
            )
        );

        assertEquals("人工知识库", catalogService.getKnowledgeBase(knowledgeBase.id()).name());

        CatalogDtos.KnowledgeBaseDto updated = catalogService.updateKnowledgeBase(
            knowledgeBase.id(),
            new CatalogDtos.UpdateKnowledgeBaseRequest(
                "人工知识库升级版",
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "补充退款和售后说明",
                "知识运营二组",
                List.of("FAQ", "售后")
            )
        );
        assertEquals("人工知识库升级版", updated.name());

        CatalogDtos.KnowledgeReleaseDto draftRelease = catalogService.createKnowledgeRelease(
            knowledgeBase.id(),
            new CatalogDtos.CreateKnowledgeReleaseRequest(
                "导入后的首个知识发布",
                VersionStatus.DRAFT,
                "snapshot-kb-manual-v1",
                new CatalogDtos.KnowledgeRetrievalProfileDto(6, "HYBRID", 0.2)
            )
        );
        assertEquals(VersionStatus.DRAFT, draftRelease.status());

        CatalogDtos.KnowledgeReleaseDto publishedRelease = catalogService.publishKnowledgeRelease(knowledgeBase.id(), draftRelease.id());
        assertEquals(VersionStatus.PUBLISHED, publishedRelease.status());
        assertEquals("snapshot-kb-manual-v1", publishedRelease.snapshotId());
        assertNotNull(publishedRelease.publishedAt());

        CatalogDtos.KnowledgeBaseDto reloaded = catalogService.getKnowledgeBase(knowledgeBase.id());
        assertEquals(publishedRelease.id(), reloaded.effectiveRelease().id());
        assertThrows(IllegalStateException.class, () -> catalogService.deleteKnowledgeRelease(knowledgeBase.id(), publishedRelease.id()));
        assertThrows(IllegalStateException.class, () -> catalogService.deleteKnowledgeBase(knowledgeBase.id()));
    }

    @Test
    void shouldExposeKnowledgeReferencesAndBlockDeletionWhenAssistantsUseKnowledgeBase() {
        CatalogDtos.KnowledgeBaseDto knowledgeBase = service.getKnowledgeBase("knowledge-base-support");

        List<CatalogDtos.KnowledgeReferenceDto> references = service.listKnowledgeReferences(knowledgeBase.id());

        assertTrue(references.stream().anyMatch(reference -> reference.referenceKind().equals("ASSISTANT_DEFAULT_KNOWLEDGE_BASE")));
        assertTrue(references.stream().anyMatch(reference -> reference.referenceKind().equals("RELEASE_ASSISTANT_KNOWLEDGE")));
        assertThrows(IllegalStateException.class, () -> service.deleteKnowledgeBase(knowledgeBase.id()));
    }

    @Test
    void shouldStillFreezeToolVersionsWhenPublishingAssistant() {
        CatalogService catalogService = new CatalogService(new InMemoryCatalogRepository(), false);
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("交付域", "承载交付流程"));
        CatalogDtos.ScenarioDto scenario = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "交付跟进", "跟进交付流程")
        );
        CatalogDtos.AssistantDto assistant = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(scenario.id(), "交付助手", "处理交付跟进", null, null, null)
        );
        CatalogDtos.ResourceDto tool = catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "交付 Tool",
                ResourceType.TOOL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "处理交付回调",
                "交付团队",
                List.of("交付"),
                null
            )
        );
        catalogService.createAgent(new CatalogDtos.CreateAgentRequest(
            assistant.id(),
            "交付执行智能体",
            "executor",
            "调用交付工具",
            new CatalogDtos.AgentExecutionPolicyDto(true, null, "", false, false, null, 8, List.of(), List.of(tool.id()))
        ));

        CatalogDtos.AssistantDto published = catalogService.updateAssistant(
            assistant.id(),
            new CatalogDtos.UpdateAssistantRequest("交付助手", "处理交付跟进", VersionStatus.PUBLISHED, assistant.modelPolicy(), assistant.ragPolicy(), assistant.memoryPolicy())
        );
        assertEquals(VersionStatus.PUBLISHED, published.version().status());
        assertFalse(published.currentRelease().agents().getFirst().toolResourceVersionIds().isEmpty());
    }

    @Test
    void shouldDeleteUnusedResourceAndProtectBoundResources() {
        CatalogService catalogService = new CatalogService(new InMemoryCatalogRepository(), false);
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("质检域", "承载质检流程"));
        CatalogDtos.ResourceDto removable = catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "质检 Tool",
                ResourceType.TOOL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "质检工具",
                "质检管理员",
                List.of("质检"),
                null
            )
        );

        CatalogDtos.ResourceDto deleted = catalogService.deleteResource(removable.id());
        assertEquals(removable.id(), deleted.id());
        assertThrows(IllegalStateException.class, () -> service.deleteResource("resource-tool-refund"));
    }

    @Test
    void shouldUpdateResourceMetadataIncludingName() {
        CatalogService catalogService = new CatalogService(new InMemoryCatalogRepository(), false);
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("客服域", "承载客服资源"));
        CatalogDtos.ScenarioDto scenario = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "客服场景", "处理客服问题")
        );
        CatalogDtos.AssistantDto assistant = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(scenario.id(), "客服助手", "处理客服问题", null, null, null)
        );
        CatalogDtos.ResourceDto resource = catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "原始资源名",
                ResourceType.SKILL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "原始摘要",
                "客服运营",
                List.of("客服"),
                null
            )
        );

        CatalogDtos.ResourceDto updated = catalogService.updateResource(
            resource.id(),
            new CatalogDtos.UpdateResourceRequest(
                "更新后的资源名",
                ShareScope.PRIVATE,
                "ASSISTANT",
                assistant.id(),
                "更新后的摘要",
                "客服运营二组",
                List.of("客服", "升级")
            )
        );

        assertEquals("更新后的资源名", updated.name());
        assertEquals(ShareScope.PRIVATE, updated.shareScope());
        assertEquals("ASSISTANT", updated.ownerType());
        assertEquals(assistant.id(), updated.ownerId());
        assertEquals("更新后的摘要", updated.summary());
    }

    @Test
    void shouldUpdateDraftResourceVersionInPlaceAndAllowDirectPublish() {
        CatalogService catalogService = new CatalogService(new InMemoryCatalogRepository(), false);
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("运营域", "承载运营资源"));
        CatalogDtos.ResourceDto resource = catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "运营 Tool",
                ResourceType.TOOL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "运营工具",
                "运营团队",
                List.of("运营"),
                new CatalogDtos.CreateResourceVersionRequest("初始草稿", VersionStatus.DRAFT, null)
            )
        );
        CatalogDtos.ResourceVersionDto draftVersion = resource.versions().getFirst();

        CatalogDtos.ResourceVersionDto updatedDraft = catalogService.updateResourceVersion(
            resource.id(),
            draftVersion.id(),
            new CatalogDtos.UpdateResourceVersionRequest(
                "更新后的草稿",
                VersionStatus.DRAFT,
                draftVersion.configuration()
            )
        );
        assertEquals("更新后的草稿", updatedDraft.summary());
        assertEquals(VersionStatus.DRAFT, updatedDraft.status());

        CatalogDtos.ResourceVersionDto published = catalogService.updateResourceVersion(
            resource.id(),
            draftVersion.id(),
            new CatalogDtos.UpdateResourceVersionRequest(
                "直接发布的版本",
                VersionStatus.PUBLISHED,
                updatedDraft.configuration()
            )
        );
        assertEquals(VersionStatus.PUBLISHED, published.status());
        assertNotNull(published.publishedAt());

        CatalogDtos.ResourceDto reloaded = catalogService.listResources().stream()
            .filter(item -> item.id().equals(resource.id()))
            .findFirst()
            .orElseThrow();
        assertEquals("直接发布的版本", reloaded.effectiveVersion().summary());
    }

    private static KnowledgeServiceClient readySnapshotKnowledgeClient() {
        return new KnowledgeServiceClient("http://localhost:8091") {
            @Override
            public CatalogDtos.KnowledgeIndexSnapshotDto getIndexSnapshot(String snapshotId) {
                return new CatalogDtos.KnowledgeIndexSnapshotDto(
                    snapshotId,
                    "knowledge-base-support",
                    "OPENSEARCH",
                    "HYBRID",
                    "READY",
                    1,
                    2,
                    null,
                    Instant.now(),
                    Instant.now(),
                    Instant.now()
                );
            }
        };
    }

    private static KnowledgeWorkflowGateway noopKnowledgeWorkflowGateway() {
        return new KnowledgeWorkflowGateway() {
            @Override
            public void startImport(String knowledgeBaseId, String importJobId) {
            }

            @Override
            public void startIndexBuild(String knowledgeBaseId, String indexSnapshotId) {
            }
        };
    }

    private static CatalogService catalogService() {
        return new CatalogService(
            new InMemoryCatalogRepository(),
            true,
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway()
        );
    }
}
