package com.lynxus.platform.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.runtime.WorkflowContracts.ResourceType;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
import org.junit.jupiter.api.Test;

class CatalogServiceTest {
    private final CatalogService service = new CatalogService();

    @Test
    void shouldExposeSeededCatalogSummary() {
        CatalogDtos.CatalogSummaryDto summary = service.summary();

        assertFalse(summary.domains().isEmpty());
        assertFalse(summary.scenarios().isEmpty());
        assertFalse(summary.resources().isEmpty());
        assertFalse(summary.orchestrations().isEmpty());
        assertEquals("智能客服协同处理", summary.scenarios().getFirst().name());
        assertTrue(summary.resourceCenter().totalResources() >= 2);
        assertTrue(summary.orchestrations().getFirst().nodes().stream().anyMatch(node -> node.nodeType().name().equals("HUMAN")));
    }

    @Test
    void shouldSeedCatalogOnlyOnceForEmptyRepository() {
        InMemoryCatalogRepository repository = new InMemoryCatalogRepository();
        CatalogService seededService = new CatalogService(repository, false);

        assertTrue(seededService.initializeDemoDataIfEmpty());
        int assistantCount = seededService.listAssistants().size();

        assertFalse(seededService.initializeDemoDataIfEmpty());
        assertEquals(assistantCount, seededService.listAssistants().size());
        assertEquals(assistantCount, repository.load().assistants().size());
    }

    @Test
    void shouldSupportBusinessDomainCrudAndProtectDependencies() {
        CatalogService catalogService = new CatalogService(new InMemoryCatalogRepository(), false);

        CatalogDtos.BusinessDomainDto created = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("企业服务域", "统一承载企业服务流程"));
        assertEquals("企业服务域", catalogService.getDomain(created.id()).name());

        CatalogDtos.BusinessDomainDto updated = catalogService.updateDomain(
            created.id(),
            new CatalogDtos.UpdateDomainRequest("企业服务中台域", "统一承载企业服务流程与资产")
        );
        assertEquals("企业服务中台域", updated.name());

        CatalogDtos.ScenarioDto scenario = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(created.id(), "合同审批", "完成合同流转审批")
        );
        assertThrows(IllegalStateException.class, () -> catalogService.deleteDomain(created.id()));

        CatalogDtos.ScenarioDto deletedScenario = catalogService.deleteScenario(scenario.id());
        assertEquals(scenario.id(), deletedScenario.id());

        catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                created.id(),
                "合同知识库",
                ResourceType.KNOWLEDGE_BASE,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                created.id(),
                "合同文档知识资产",
                "法务知识管理员",
                java.util.List.of("合同"),
                null
            )
        );
        assertThrows(IllegalStateException.class, () -> catalogService.deleteDomain(created.id()));

        CatalogDtos.BusinessDomainDto removable = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("销售域", "承载销售协同"));
        CatalogDtos.BusinessDomainDto deleted = catalogService.deleteDomain(removable.id());
        assertEquals(removable.id(), deleted.id());
        assertTrue(catalogService.listDomains().stream().noneMatch(item -> item.id().equals(removable.id())));
    }

    @Test
    void shouldSupportScenarioCrudAndRejectDeletingScenarioWithAssistants() {
        CatalogService catalogService = new CatalogService(new InMemoryCatalogRepository(), false);
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("运营域", "承载运营协同"));

        CatalogDtos.ScenarioDto created = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "工单协同", "统一处理工单流转")
        );
        CatalogDtos.ScenarioDto updated = catalogService.updateScenario(
            created.id(),
            new CatalogDtos.UpdateScenarioRequest("工单协同升级", "统一处理工单流转与升级")
        );
        assertEquals("工单协同升级", updated.name());

        catalogService.createAssistant(new CatalogDtos.CreateAssistantRequest(
            created.id(),
            "工单助手",
            "处理工单分派",
            null,
            null,
            null
        ));
        assertThrows(IllegalStateException.class, () -> catalogService.deleteScenario(created.id()));

        CatalogDtos.ScenarioDto removable = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "新客回访", "处理新客回访流程")
        );
        CatalogDtos.ScenarioDto deleted = catalogService.deleteScenario(removable.id());
        assertEquals(removable.id(), deleted.id());
        assertTrue(catalogService.listScenarios().stream().noneMatch(item -> item.id().equals(removable.id())));
    }

    @Test
    void shouldProtectAssistantDeletionAndRecycleInternalSnapshots() {
        CatalogService catalogService = new CatalogService();
        CatalogDtos.AssistantDto seeded = catalogService.listAssistants().getFirst();

        assertThrows(IllegalStateException.class, () -> catalogService.deleteAssistant(seeded.id()));

        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("风控域", "承载风控流程"));
        CatalogDtos.ScenarioDto scenario = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "异常检测", "识别异常风险")
        );
        CatalogDtos.AssistantDto removable = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(scenario.id(), "风控助手", "负责异常检测闭环", null, null, null)
        );

        CatalogDtos.AssistantDto deleted = catalogService.deleteAssistant(removable.id());
        assertEquals(removable.id(), deleted.id());
        assertTrue(catalogService.listAssistants().stream().noneMatch(item -> item.id().equals(removable.id())));
        assertTrue(catalogService.listOrchestrations().stream().noneMatch(item -> item.assistantId().equals(removable.id())));
    }

    @Test
    void shouldDeleteAgentAndRecycleAssistantOrchestration() {
        CatalogService catalogService = new CatalogService();
        CatalogDtos.AssistantDto assistant = catalogService.listAssistants().getFirst();
        CatalogDtos.AssistantOrchestrationDto before = catalogService.getOrchestration(assistant.id());
        int nodeCountBefore = before.nodes().size();

        CatalogDtos.AgentDto deleted = catalogService.deleteAgent("agent-faq");
        assertEquals("agent-faq", deleted.id());
        assertTrue(catalogService.listAgents().stream().noneMatch(item -> item.id().equals("agent-faq")));

        CatalogDtos.AssistantOrchestrationDto after = catalogService.getOrchestration(assistant.id());
        assertTrue(after.nodes().size() < nodeCountBefore);
        assertTrue(after.nodes().stream().noneMatch(node -> "agent-faq".equals(node.agentId())));
    }

    @Test
    void shouldProtectResourceDeletionAndAllowDeletingUnusedVersions() {
        CatalogService catalogService = new CatalogService();

        assertThrows(IllegalStateException.class, () -> catalogService.deleteResource("resource-kb-support"));

        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("质检域", "承载质检流程"));
        CatalogDtos.ResourceDto resource = catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "质检知识库",
                ResourceType.KNOWLEDGE_BASE,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "质检文档知识资产",
                "质检管理员",
                java.util.List.of("质检"),
                null
            )
        );

        CatalogDtos.ResourceVersionDto draftVersion = catalogService.createResourceVersion(
            resource.id(),
            new CatalogDtos.CreateResourceVersionRequest("补充案例", "digest-quality-v2", com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus.DRAFT, null)
        );
        CatalogDtos.ResourceVersionDto deletedVersion = catalogService.deleteResourceVersion(resource.id(), draftVersion.id());
        assertEquals(draftVersion.id(), deletedVersion.id());

        CatalogDtos.ResourceDto deletedResource = catalogService.deleteResource(resource.id());
        assertEquals(resource.id(), deletedResource.id());
        assertTrue(catalogService.listResources().stream().noneMatch(item -> item.id().equals(resource.id())));
    }

    @Test
    void shouldExposeStructuredResourceReferencesAndMatchDeletionBlockers() {
        CatalogService catalogService = new CatalogService();

        CatalogDtos.ResourceCenterDto resourceCenter = catalogService.resourceCenter();
        assertTrue(resourceCenter.references().stream()
            .anyMatch(reference -> reference.resourceId().equals("resource-kb-support")
                && reference.referenceKind().equals("ASSISTANT_DEFAULT_KNOWLEDGE_BASE")
                && reference.blocksDeletion()));
        assertTrue(resourceCenter.references().stream()
            .anyMatch(reference -> reference.resourceId().equals("resource-skill-refund")
                && reference.referenceKind().equals("AGENT_TOOL_VERSION_PIN")
                && reference.resourceVersionId() != null
                && reference.blocksDeletion()));
        assertTrue(resourceCenter.references().stream()
            .anyMatch(reference -> reference.referenceKind().equals("RELEASE_FROZEN")
                && !reference.blocksDeletion()));
        assertThrows(IllegalStateException.class, () -> catalogService.deleteResource("resource-skill-refund"));
    }

    @Test
    void shouldValidateResourceOwnerWithinBusinessDomain() {
        CatalogService catalogService = new CatalogService(new InMemoryCatalogRepository(), false);
        CatalogDtos.BusinessDomainDto orderDomain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("订单域", "承载订单流程"));
        CatalogDtos.BusinessDomainDto serviceDomain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("服务域", "承载服务流程"));
        CatalogDtos.ScenarioDto serviceScenario = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(serviceDomain.id(), "服务跟进", "处理服务跟进")
        );
        CatalogDtos.AssistantDto serviceAssistant = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(serviceScenario.id(), "服务助手", "处理服务跟进", null, null, null)
        );

        assertThrows(IllegalArgumentException.class, () -> catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                orderDomain.id(),
                "非法域归属资源",
                ResourceType.KNOWLEDGE_BASE,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                serviceDomain.id(),
                "ownerId 与 domainId 不一致",
                "平台治理",
                java.util.List.of(),
                null
            )
        ));

        assertThrows(IllegalArgumentException.class, () -> catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                orderDomain.id(),
                "非法助手归属资源",
                ResourceType.SKILL,
                ShareScope.PRIVATE,
                "ASSISTANT",
                serviceAssistant.id(),
                "助手不属于当前业务域",
                "平台治理",
                java.util.List.of(),
                null
            )
        ));
    }

    @Test
    void shouldRequirePinnedToolVersionsBeforePublishingAssistant() {
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
                "交付 Skill",
                ResourceType.SKILL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "处理交付回调",
                "交付团队",
                java.util.List.of("交付"),
                null
            )
        );
        CatalogDtos.AgentDto agent = catalogService.createAgent(new CatalogDtos.CreateAgentRequest(
            assistant.id(),
            "交付执行智能体",
            "executor",
            "调用交付工具",
            new CatalogDtos.AgentExecutionPolicyDto(true, null, null, "", false, null, 8, java.util.List.of(tool.id()))
        ));

        assertThrows(IllegalStateException.class, () -> catalogService.updateAssistant(
            assistant.id(),
            new CatalogDtos.UpdateAssistantRequest("交付助手", "处理交付跟进", VersionStatus.PUBLISHED, assistant.modelPolicy(), assistant.ragPolicy(), assistant.memoryPolicy())
        ));

        CatalogDtos.ResourceVersionDto effectiveToolVersion = catalogService.listResourceVersions(tool.id()).getFirst();
        catalogService.updateAgentToolVersionPins(agent.id(), new CatalogDtos.UpdateAgentToolVersionPinsRequest(
            java.util.List.of(new CatalogDtos.ToolVersionPinTarget(tool.id(), effectiveToolVersion.id()))
        ));

        CatalogDtos.AssistantDto published = catalogService.updateAssistant(
            assistant.id(),
            new CatalogDtos.UpdateAssistantRequest("交付助手", "处理交付跟进", VersionStatus.PUBLISHED, assistant.modelPolicy(), assistant.ragPolicy(), assistant.memoryPolicy())
        );
        assertEquals(VersionStatus.PUBLISHED, published.version().status());
    }
}
