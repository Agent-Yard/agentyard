package com.lynxus.platform.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.runtime.WorkflowContracts.ResourceType;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
import com.lynxus.platform.auth.AuthModels.AuthSource;
import com.lynxus.platform.auth.AuthModels.PlatformUser;
import com.lynxus.platform.auth.AuthModels.Role;
import com.lynxus.platform.auth.AuthModels.UserStatus;
import com.lynxus.platform.auth.CurrentUserResolver;
import com.lynxus.platform.event.PlatformEventDtos.PlatformAggregateType;
import com.lynxus.platform.event.PlatformEventRepository;
import com.lynxus.platform.event.PlatformEventService;
import com.lynxus.platform.knowledge.KnowledgeServiceClient;
import com.lynxus.platform.knowledge.KnowledgeWorkflowGateway;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogServiceTest {
    @Test
    void shouldRecordAssistantAndKnowledgeLifecycleEventsIntoPlatformLog() {
        PlatformEventService platformEventService = new PlatformEventService(
            new PlatformEventRepository.InMemoryPlatformEventRepository(),
            testCurrentUserResolver()
        );
        CustomerOpsFixture fixture = customerOpsFixture(platformEventService);

        List<String> assistantEventTypes = platformEventService.listEvents(
            PlatformAggregateType.ASSISTANT,
            fixture.assistantId(),
            null,
            50,
            null
        ).items().stream().map(item -> item.eventType()).toList();
        List<String> knowledgeEventTypes = platformEventService.listEvents(
            PlatformAggregateType.KNOWLEDGE_BASE,
            fixture.knowledgeBaseId(),
            null,
            50,
            null
        ).items().stream().map(item -> item.eventType()).toList();

        assertTrue(assistantEventTypes.contains("ASSISTANT_CREATED"));
        assertTrue(assistantEventTypes.contains("ASSISTANT_UPDATED"));
        assertTrue(assistantEventTypes.contains("ASSISTANT_RELEASE_PUBLISHED"));
        assertTrue(knowledgeEventTypes.contains("KNOWLEDGE_BASE_CREATED"));
        assertTrue(knowledgeEventTypes.contains("KNOWLEDGE_RELEASE_CREATED"));
        assertTrue(knowledgeEventTypes.contains("KNOWLEDGE_RELEASE_PUBLISHED"));
        assertTrue(platformEventService.listEvents(PlatformAggregateType.ASSISTANT, fixture.assistantId(), null, 1, null)
            .items()
            .stream()
            .allMatch(item -> "user-test".equals(item.actorId())));
    }

    @Test
    void shouldExposeEmptyCatalogSummaryWithoutSeededData() {
        CatalogService service = new CatalogService(
            new InMemoryCatalogRepository(),
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway()
        );
        CatalogDtos.CatalogSummaryDto summary = service.summary();

        assertTrue(summary.domains().isEmpty());
        assertTrue(summary.resources().isEmpty());
        assertTrue(summary.knowledgeBases().isEmpty());
    }

    @Test
    void shouldSupportKnowledgeBaseCrudAndReleaseLifecycle() {
        CatalogService catalogService = new CatalogService(
            new InMemoryCatalogRepository(),
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
        CustomerOpsFixture fixture = customerOpsFixture();
        CatalogDtos.KnowledgeBaseDto knowledgeBase = fixture.service().getKnowledgeBase(fixture.knowledgeBaseId());

        List<CatalogDtos.KnowledgeReferenceDto> references = fixture.service().listKnowledgeReferences(knowledgeBase.id());

        assertTrue(references.stream().anyMatch(reference -> reference.referenceKind().equals("ASSISTANT_DEFAULT_KNOWLEDGE_BASE")));
        assertTrue(references.stream().anyMatch(reference -> reference.referenceKind().equals("RELEASE_ASSISTANT_KNOWLEDGE")));
        assertThrows(IllegalStateException.class, () -> fixture.service().deleteKnowledgeBase(knowledgeBase.id()));
    }

    @Test
    void shouldExposeUnifiedObjectReferenceAnalysisAcrossCatalogObjects() {
        CustomerOpsFixture fixture = customerOpsFixture();

        CatalogDtos.ObjectReferenceAnalysisDto domainAnalysis = fixture.service().objectReferences("DOMAIN", fixture.domainId());
        assertTrue(domainAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("DOMAIN_SCENARIO")));
        assertTrue(domainAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("DOMAIN_RESOURCE")));
        assertTrue(domainAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("DOMAIN_KNOWLEDGE_BASE")));

        CatalogDtos.ObjectReferenceAnalysisDto scenarioAnalysis = fixture.service().objectReferences("SCENARIO", fixture.scenarioId());
        assertTrue(scenarioAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("SCENARIO_ASSISTANT")));

        CatalogDtos.ObjectReferenceAnalysisDto assistantAnalysis = fixture.service().objectReferences("ASSISTANT", fixture.assistantId());
        assertTrue(assistantAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("ASSISTANT_AGENT")));
        assertTrue(assistantAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("ASSISTANT_PLAYBOOK")));
        assertTrue(assistantAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("ASSISTANT_PRIVATE_RESOURCE")));
        assertTrue(assistantAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("ASSISTANT_RELEASE")));

        CatalogDtos.ObjectReferenceAnalysisDto playbookAnalysis = fixture.service().objectReferences("PLAYBOOK", fixture.playbookId());
        assertTrue(playbookAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("PLAYBOOK_ASSISTANT")));
        assertTrue(playbookAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("PLAYBOOK_AGENT_ENABLED")));
        assertTrue(playbookAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("PLAYBOOK_RELEASE_FROZEN")));

        CatalogDtos.ObjectReferenceAnalysisDto agentAnalysis = fixture.service().objectReferences("AGENT", fixture.agentId());
        assertTrue(agentAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("AGENT_TOOL_ENABLED")));
        assertTrue(agentAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("AGENT_RELEASE_FROZEN")));

        CatalogDtos.ObjectReferenceAnalysisDto resourceAnalysis = fixture.service().objectReferences("RESOURCE", fixture.toolResourceId());
        assertTrue(resourceAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("AGENT_TOOL_ENABLED")));
        assertTrue(resourceAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("RELEASE_FROZEN")));

        CatalogDtos.ObjectReferenceAnalysisDto knowledgeAnalysis = fixture.service().objectReferences("KNOWLEDGE_BASE", fixture.knowledgeBaseId());
        assertTrue(knowledgeAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("ASSISTANT_DEFAULT_KNOWLEDGE_BASE")));
        assertTrue(knowledgeAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("KNOWLEDGE_BASE_EFFECTIVE_RELEASE")));
        assertTrue(knowledgeAnalysis.relations().stream().anyMatch(relation -> relation.relationKind().equals("RELEASE_ASSISTANT_KNOWLEDGE")));
    }

    @Test
    void shouldRejectStepNodeWithoutExplicitScriptIdentity() {
        CustomerOpsFixture fixture = customerOpsFixture();

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> fixture.service().createPlaybook(
                new CatalogDtos.CreatePlaybookRequest(
                    fixture.assistantId(),
                    "无版本 STEP",
                    "缺少版本标识",
                    "{\"type\":\"object\"}",
                    "{\"type\":\"object\"}",
                    new CatalogDtos.PlaybookExecutionPolicyDto("PT5M", "PT30S"),
                    true,
                    true,
                    "start",
                    List.of(
                        new CatalogDtos.PlaybookNodeDto(
                            "start",
                            "开始",
                            com.lynxus.contracts.session.SessionContracts.PlaybookNodeType.STEP,
                            "",
                            null,
                            null,
                            null,
                            null,
                            Map.of("code", "result = {'statePatch': {}, 'routeKey': None}")
                        ),
                        new CatalogDtos.PlaybookNodeDto(
                            "finish",
                            "结束",
                            com.lynxus.contracts.session.SessionContracts.PlaybookNodeType.END,
                            "",
                            null,
                            null,
                            null,
                            null,
                            Map.of()
                        )
                    ),
                    List.of(new CatalogDtos.PlaybookEdgeDto("start-to-finish", "start", "finish", null, null, true))
                )
            )
        );

        assertTrue(error.getMessage().contains("STEP node must define scriptRef and scriptVersion"));
    }

    @Test
    void shouldRejectStepNodeWithoutVersionedScriptConfig() {
        CustomerOpsFixture fixture = customerOpsFixture();

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> fixture.service().createPlaybook(
                new CatalogDtos.CreatePlaybookRequest(
                    fixture.assistantId(),
                    "缺少版本脚本",
                    "缺少 config.scriptVersions",
                    "{\"type\":\"object\"}",
                    "{\"type\":\"object\"}",
                    new CatalogDtos.PlaybookExecutionPolicyDto("PT5M", "PT30S"),
                    true,
                    true,
                    "start",
                    List.of(
                        new CatalogDtos.PlaybookNodeDto(
                            "start",
                            "开始",
                            com.lynxus.contracts.session.SessionContracts.PlaybookNodeType.STEP,
                            "",
                            "refund.start",
                            "2026.04.20",
                            null,
                            null,
                            Map.of()
                        ),
                        new CatalogDtos.PlaybookNodeDto(
                            "finish",
                            "结束",
                            com.lynxus.contracts.session.SessionContracts.PlaybookNodeType.END,
                            "",
                            null,
                            null,
                            null,
                            null,
                            Map.of()
                        )
                    ),
                    List.of(new CatalogDtos.PlaybookEdgeDto("start-to-finish", "start", "finish", null, null, true))
                )
            )
        );

        assertTrue(error.getMessage().contains("STEP node must define config.scriptVersions"));
    }

    @Test
    void shouldKeepLegacyKnowledgeReferencesAlignedWithUnifiedAnalysis() {
        CustomerOpsFixture fixture = customerOpsFixture();

        List<CatalogDtos.KnowledgeReferenceDto> legacyReferences = fixture.service().listKnowledgeReferences(fixture.knowledgeBaseId());
        CatalogDtos.ObjectReferenceAnalysisDto unifiedAnalysis = fixture.service().objectReferences("KNOWLEDGE_BASE", fixture.knowledgeBaseId());

        assertEquals(unifiedAnalysis.relations().size(), legacyReferences.size());
        assertTrue(legacyReferences.stream().anyMatch(reference -> reference.referenceKind().equals("KNOWLEDGE_BASE_EFFECTIVE_RELEASE")));
        assertTrue(legacyReferences.stream().anyMatch(reference -> reference.referenceKind().equals("RELEASE_ASSISTANT_KNOWLEDGE")));
    }

    @Test
    void shouldKeepDeletionBlockersConsistentWithUnifiedReferenceAnalysis() {
        CustomerOpsFixture fixture = customerOpsFixture();

        String domainBlockerName = fixture.service().objectReferences("DOMAIN", fixture.domainId()).relations().stream()
            .filter(relation -> relation.impactLevel().equals("BLOCKS_DELETION"))
            .findFirst()
            .orElseThrow()
            .targetName();
        IllegalStateException domainError = assertThrows(IllegalStateException.class, () -> fixture.service().deleteDomain(fixture.domainId()));
        assertTrue(domainError.getMessage().contains(domainBlockerName));

        String scenarioBlockerName = fixture.service().objectReferences("SCENARIO", fixture.scenarioId()).relations().stream()
            .filter(relation -> relation.impactLevel().equals("BLOCKS_DELETION"))
            .findFirst()
            .orElseThrow()
            .targetName();
        IllegalStateException scenarioError = assertThrows(IllegalStateException.class, () -> fixture.service().deleteScenario(fixture.scenarioId()));
        assertTrue(scenarioError.getMessage().contains(scenarioBlockerName));

        String assistantBlockerName = fixture.service().objectReferences("ASSISTANT", fixture.assistantId()).relations().stream()
            .filter(relation -> relation.impactLevel().equals("BLOCKS_DELETION"))
            .findFirst()
            .orElseThrow()
            .targetName();
        IllegalStateException assistantError = assertThrows(IllegalStateException.class, () -> fixture.service().deleteAssistant(fixture.assistantId()));
        assertTrue(assistantError.getMessage().contains(assistantBlockerName));

        String playbookBlockerName = fixture.service().objectReferences("PLAYBOOK", fixture.playbookId()).relations().stream()
            .filter(relation -> relation.impactLevel().equals("BLOCKS_DELETION"))
            .findFirst()
            .orElseThrow()
            .targetName();
        IllegalStateException playbookError = assertThrows(IllegalStateException.class, () -> fixture.service().deletePlaybook(fixture.playbookId()));
        assertTrue(playbookError.getMessage().contains(playbookBlockerName));

        String resourceBlockerName = fixture.service().objectReferences("RESOURCE", fixture.toolResourceId()).relations().stream()
            .filter(relation -> relation.impactLevel().equals("BLOCKS_DELETION"))
            .findFirst()
            .orElseThrow()
            .targetName();
        IllegalStateException resourceError = assertThrows(IllegalStateException.class, () -> fixture.service().deleteResource(fixture.toolResourceId()));
        assertTrue(resourceError.getMessage().contains(resourceBlockerName));

        String knowledgeBlockerName = fixture.service().objectReferences("KNOWLEDGE_BASE", fixture.knowledgeBaseId()).relations().stream()
            .filter(relation -> relation.impactLevel().equals("BLOCKS_DELETION"))
            .findFirst()
            .orElseThrow()
            .targetName();
        IllegalStateException knowledgeError = assertThrows(IllegalStateException.class, () -> fixture.service().deleteKnowledgeBase(fixture.knowledgeBaseId()));
        assertTrue(knowledgeError.getMessage().contains(knowledgeBlockerName));
    }

    @Test
    void shouldExposeDeletionPreviewAcrossSupportedObjects() {
        CustomerOpsFixture fixture = customerOpsFixture();

        CatalogDtos.DeletionImpactPreviewDto domainPreview = fixture.service().deletionPreview("DOMAIN", fixture.domainId());
        assertFalse(domainPreview.canDelete());
        assertTrue(domainPreview.blockers().stream().anyMatch(relation -> relation.relationKind().equals("DOMAIN_SCENARIO")));
        assertTrue(domainPreview.cascadeDeletes().isEmpty());

        CatalogDtos.DeletionImpactPreviewDto scenarioPreview = fixture.service().deletionPreview("SCENARIO", fixture.scenarioId());
        assertFalse(scenarioPreview.canDelete());
        assertTrue(scenarioPreview.blockers().stream().anyMatch(relation -> relation.relationKind().equals("SCENARIO_ASSISTANT")));
        assertTrue(scenarioPreview.cascadeDeletes().isEmpty());

        CatalogDtos.DeletionImpactPreviewDto assistantPreview = fixture.service().deletionPreview("ASSISTANT", fixture.assistantId());
        assertFalse(assistantPreview.canDelete());
        assertTrue(assistantPreview.blockers().stream().anyMatch(relation -> relation.relationKind().equals("ASSISTANT_AGENT")));
        assertTrue(assistantPreview.cascadeDeletes().stream().anyMatch(item -> item.relationKind().equals("ASSISTANT_RELEASE")));

        CatalogDtos.DeletionImpactPreviewDto playbookPreview = fixture.service().deletionPreview("PLAYBOOK", fixture.playbookId());
        assertFalse(playbookPreview.canDelete());
        assertTrue(playbookPreview.blockers().stream().anyMatch(relation -> relation.relationKind().equals("PLAYBOOK_AGENT_ENABLED")));
        assertTrue(playbookPreview.advisories().stream().anyMatch(relation -> relation.relationKind().equals("PLAYBOOK_RELEASE_FROZEN")));
        assertTrue(playbookPreview.cascadeDeletes().isEmpty());

        CatalogDtos.DeletionImpactPreviewDto agentPreview = fixture.service().deletionPreview("AGENT", fixture.agentId());
        assertTrue(agentPreview.canDelete());
        assertTrue(agentPreview.blockers().isEmpty());
        assertTrue(agentPreview.cascadeDeletes().isEmpty());

        CatalogDtos.DeletionImpactPreviewDto resourcePreview = fixture.service().deletionPreview("RESOURCE", fixture.toolResourceId());
        assertFalse(resourcePreview.canDelete());
        assertTrue(resourcePreview.blockers().stream().anyMatch(relation -> relation.relationKind().equals("AGENT_TOOL_ENABLED")));
        assertTrue(resourcePreview.advisories().stream().anyMatch(relation -> relation.relationKind().equals("RELEASE_FROZEN")));
        assertTrue(resourcePreview.cascadeDeletes().stream().anyMatch(item -> item.relationKind().equals("RESOURCE_VERSION")));

        CatalogDtos.DeletionImpactPreviewDto knowledgePreview = fixture.service().deletionPreview("KNOWLEDGE_BASE", fixture.knowledgeBaseId());
        assertFalse(knowledgePreview.canDelete());
        assertTrue(knowledgePreview.blockers().stream().anyMatch(relation -> relation.relationKind().equals("KNOWLEDGE_BASE_EFFECTIVE_RELEASE")));
        assertTrue(knowledgePreview.blockers().stream().anyMatch(relation -> relation.relationKind().equals("RELEASE_ASSISTANT_KNOWLEDGE")));
        assertTrue(knowledgePreview.cascadeDeletes().stream().anyMatch(item -> item.relationKind().equals("KNOWLEDGE_BASE_DRAFT_RELEASE")));
    }

    @Test
    void shouldKeepDeletionPreviewCanDeleteAlignedWithActualDeletion() {
        CustomerOpsFixture fixture = customerOpsFixture();

        assertFalse(fixture.service().deletionPreview("DOMAIN", fixture.domainId()).canDelete());
        assertThrows(IllegalStateException.class, () -> fixture.service().deleteDomain(fixture.domainId()));

        assertFalse(fixture.service().deletionPreview("SCENARIO", fixture.scenarioId()).canDelete());
        assertThrows(IllegalStateException.class, () -> fixture.service().deleteScenario(fixture.scenarioId()));

        assertFalse(fixture.service().deletionPreview("ASSISTANT", fixture.assistantId()).canDelete());
        assertThrows(IllegalStateException.class, () -> fixture.service().deleteAssistant(fixture.assistantId()));

        assertFalse(fixture.service().deletionPreview("PLAYBOOK", fixture.playbookId()).canDelete());
        assertThrows(IllegalStateException.class, () -> fixture.service().deletePlaybook(fixture.playbookId()));

        assertFalse(fixture.service().deletionPreview("RESOURCE", fixture.toolResourceId()).canDelete());
        assertThrows(IllegalStateException.class, () -> fixture.service().deleteResource(fixture.toolResourceId()));

        assertFalse(fixture.service().deletionPreview("KNOWLEDGE_BASE", fixture.knowledgeBaseId()).canDelete());
        assertThrows(IllegalStateException.class, () -> fixture.service().deleteKnowledgeBase(fixture.knowledgeBaseId()));

        assertTrue(fixture.service().deletionPreview("AGENT", fixture.agentId()).canDelete());
        CatalogDtos.AgentDto deletedAgent = fixture.service().deleteAgent(fixture.agentId());
        assertEquals(fixture.agentId(), deletedAgent.id());
    }

    @Test
    void shouldKeepDraftDefaultModelUnsetWhenAssistantCreatedAndUpdated() {
        CatalogService catalogService = new CatalogService(new InMemoryCatalogRepository(), readySnapshotKnowledgeClient(), noopKnowledgeWorkflowGateway());
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("草稿域", "验证默认模型不再隐式回填"));
        CatalogDtos.ScenarioDto scenario = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "草稿场景", "测试草稿助手")
        );
        catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "可用默认模型",
                ResourceType.LLM_MODEL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "存在可用 LLM，也不应自动选中",
                "平台模型团队",
                List.of("LLM"),
                null
            )
        );

        CatalogDtos.AssistantDto created = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(scenario.id(), "草稿助手", "保持未配置默认模型", null, null, null)
        );
        CatalogDtos.AssistantDto updated = catalogService.updateAssistant(
            created.id(),
            new CatalogDtos.UpdateAssistantRequest(
                created.name(),
                created.description(),
                VersionStatus.DRAFT,
                new CatalogDtos.AssistantModelPolicyDto(null),
                created.knowledgeAccessPolicy(),
                created.memoryPolicy()
            )
        );

        assertEquals(null, created.modelPolicy().defaultModelResourceId());
        assertEquals(null, updated.modelPolicy().defaultModelResourceId());
    }

    @Test
    void shouldRejectPublishingAssistantWithoutDefaultModel() {
        CatalogService catalogService = new CatalogService(new InMemoryCatalogRepository(), readySnapshotKnowledgeClient(), noopKnowledgeWorkflowGateway());
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("发布域", "验证发布前默认模型校验"));
        CatalogDtos.ScenarioDto scenario = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "发布场景", "测试发布校验")
        );
        CatalogDtos.AssistantDto assistant = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(scenario.id(), "发布助手", "缺省模型未配置", null, null, null)
        );

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> catalogService.updateAssistant(
                assistant.id(),
                new CatalogDtos.UpdateAssistantRequest(
                    assistant.name(),
                    assistant.description(),
                    VersionStatus.PUBLISHED,
                    assistant.modelPolicy(),
                    assistant.knowledgeAccessPolicy(),
                    assistant.memoryPolicy()
                )
            )
        );

        assertTrue(error.getMessage().contains("assistant default model must be configured before publishing"));
        CatalogDtos.AssistantDto reloaded = catalogService.listAssistants().stream()
            .filter(item -> item.id().equals(assistant.id()))
            .findFirst()
            .orElseThrow();
        assertEquals(VersionStatus.DRAFT, reloaded.version().status());
        assertEquals(null, reloaded.currentRelease());
    }

    @Test
    void shouldRejectClearingDefaultModelWhenPublishedAssistantOmitsStatus() {
        CatalogService catalogService = new CatalogService(new InMemoryCatalogRepository(), readySnapshotKnowledgeClient(), noopKnowledgeWorkflowGateway());
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("已发布域", "验证省略状态时仍要校验发布约束"));
        CatalogDtos.ScenarioDto scenario = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "已发布场景", "测试已发布助手更新")
        );
        CatalogDtos.ResourceDto defaultModel = catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "已发布默认模型",
                ResourceType.LLM_MODEL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "用于发布助手",
                "平台模型团队",
                List.of("LLM"),
                null
            )
        );
        CatalogDtos.AssistantDto assistant = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(
                scenario.id(),
                "已发布助手",
                "先发布再尝试清空默认模型",
                new CatalogDtos.AssistantModelPolicyDto(defaultModel.id()),
                null,
                null
            )
        );
        catalogService.createAgent(new CatalogDtos.CreateAgentRequest(
            assistant.id(),
            "已发布执行智能体",
            "owner",
            "负责会话主处理",
            new CatalogDtos.AgentExecutionPolicyDto(true, null, "", false, false, null, 8, List.of(), List.of())
        ));
        CatalogDtos.AssistantDto published = publishAssistant(catalogService, assistant);

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> catalogService.updateAssistant(
                published.id(),
                new CatalogDtos.UpdateAssistantRequest(
                    published.name(),
                    published.description(),
                    null,
                    new CatalogDtos.AssistantModelPolicyDto(null),
                    published.knowledgeAccessPolicy(),
                    published.memoryPolicy()
                )
            )
        );

        assertTrue(error.getMessage().contains("assistant default model must be configured before publishing"));
        CatalogDtos.AssistantDto reloaded = catalogService.listAssistants().stream()
            .filter(item -> item.id().equals(published.id()))
            .findFirst()
            .orElseThrow();
        assertEquals(VersionStatus.PUBLISHED, reloaded.version().status());
        assertEquals(defaultModel.id(), reloaded.modelPolicy().defaultModelResourceId());
    }

    @Test
    void shouldFreezeDefaultModelBindingWhenPublishingAssistant() {
        CatalogService catalogService = new CatalogService(new InMemoryCatalogRepository(), readySnapshotKnowledgeClient(), noopKnowledgeWorkflowGateway());
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("模型域", "验证发布冻结模型绑定"));
        CatalogDtos.ScenarioDto scenario = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "模型场景", "测试默认模型冻结")
        );
        CatalogDtos.ResourceDto defaultModel = catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "冻结默认模型",
                ResourceType.LLM_MODEL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "发布时冻结默认模型资源",
                "平台模型团队",
                List.of("LLM"),
                new CatalogDtos.CreateResourceVersionRequest("正式模型", VersionStatus.PUBLISHED, null)
            )
        );
        CatalogDtos.AssistantDto assistant = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(
                scenario.id(),
                "模型助手",
                "发布后应保存冻结模型信息",
                new CatalogDtos.AssistantModelPolicyDto(defaultModel.id()),
                null,
                null
            )
        );
        catalogService.createAgent(new CatalogDtos.CreateAgentRequest(
            assistant.id(),
            "模型执行智能体",
            "owner",
            "负责模型会话",
            new CatalogDtos.AgentExecutionPolicyDto(true, null, "", false, false, null, 8, List.of(), List.of())
        ));

        CatalogDtos.AssistantDto published = publishAssistant(catalogService, assistant);

        CatalogDtos.DefaultModelBindingDto binding = published.currentRelease().defaultModelBinding();
        assertNotNull(binding);
        assertEquals(defaultModel.id(), binding.resourceId());
        assertEquals(defaultModel.name(), binding.resourceName());
        assertEquals(defaultModel.effectiveVersion().id(), binding.resourceVersionId());
        assertEquals(defaultModel.effectiveVersion().version(), binding.resourceVersion());
        assertEquals(defaultModel.effectiveVersion().configuration().llmModel().providerType(), binding.providerType());
        assertEquals(defaultModel.effectiveVersion().configuration().llmModel().modelId(), binding.modelId());
    }

    @Test
    void shouldKeepPublishedReleaseResourceAnchorsFrozenUntilAssistantRepublished() {
        CatalogService catalogService = new CatalogService(new InMemoryCatalogRepository(), readySnapshotKnowledgeClient(), noopKnowledgeWorkflowGateway());
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("冻结资源域", "验证资源版本冻结"));
        CatalogDtos.ScenarioDto scenario = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "冻结资源场景", "测试资源版本锚点")
        );
        CatalogDtos.ResourceDto defaultModel = catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "冻结模型",
                ResourceType.LLM_MODEL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "初始模型版本",
                "平台模型团队",
                List.of("LLM"),
                new CatalogDtos.CreateResourceVersionRequest("模型 v1", VersionStatus.PUBLISHED, null)
            )
        );
        CatalogDtos.ResourceDto tool = catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "冻结 Tool",
                ResourceType.TOOL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "初始工具版本",
                "平台工具团队",
                List.of("TOOL"),
                new CatalogDtos.CreateResourceVersionRequest("Tool v1", VersionStatus.PUBLISHED, null)
            )
        );
        CatalogDtos.AssistantDto assistant = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(
                scenario.id(),
                "冻结助手",
                "发布后资源版本应被冻结",
                new CatalogDtos.AssistantModelPolicyDto(defaultModel.id()),
                null,
                null
            )
        );
        catalogService.createAgent(new CatalogDtos.CreateAgentRequest(
            assistant.id(),
            "执行智能体",
            "executor",
            "调用工具",
            new CatalogDtos.AgentExecutionPolicyDto(true, null, "", false, false, null, 8, List.of(), List.of(tool.id()))
        ));

        CatalogDtos.AssistantDto publishedV1 = publishAssistant(catalogService, assistant);
        CatalogDtos.AssistantReleaseDto releaseV1 = publishedV1.currentRelease();
        String frozenModelVersionId = releaseV1.defaultModelBinding().resourceVersionId();
        String frozenModelVersion = releaseV1.defaultModelBinding().resourceVersion();
        CatalogDtos.AssistantReleaseResourceDto frozenToolBinding = releaseV1.resources().stream()
            .filter(item -> item.resourceId().equals(tool.id()))
            .findFirst()
            .orElseThrow();

        CatalogDtos.ResourceVersionDto newModelVersion = catalogService.createResourceVersion(
            defaultModel.id(),
            new CatalogDtos.CreateResourceVersionRequest("模型 v2", VersionStatus.PUBLISHED, null)
        );
        CatalogDtos.ResourceVersionDto newToolVersion = catalogService.createResourceVersion(
            tool.id(),
            new CatalogDtos.CreateResourceVersionRequest("Tool v2", VersionStatus.PUBLISHED, null)
        );

        CatalogDtos.AssistantDto unchangedRelease = catalogService.listAssistants().stream()
            .filter(item -> item.id().equals(assistant.id()))
            .findFirst()
            .orElseThrow();
        assertEquals(frozenModelVersionId, unchangedRelease.currentRelease().defaultModelBinding().resourceVersionId());
        assertEquals(frozenModelVersion, unchangedRelease.currentRelease().defaultModelBinding().resourceVersion());
        CatalogDtos.AssistantReleaseResourceDto unchangedToolBinding = unchangedRelease.currentRelease().resources().stream()
            .filter(item -> item.resourceId().equals(tool.id()))
            .findFirst()
            .orElseThrow();
        assertEquals(frozenToolBinding.resourceVersionId(), unchangedToolBinding.resourceVersionId());
        assertEquals(frozenToolBinding.resourceVersion(), unchangedToolBinding.resourceVersion());
        assertEquals(newModelVersion.id(), catalogService.listResources().stream()
            .filter(item -> item.id().equals(defaultModel.id()))
            .findFirst()
            .orElseThrow()
            .effectiveVersion()
            .id());
        assertEquals(newToolVersion.id(), catalogService.listResources().stream()
            .filter(item -> item.id().equals(tool.id()))
            .findFirst()
            .orElseThrow()
            .effectiveVersion()
            .id());

        CatalogDtos.AssistantDto publishedV2 = publishAssistant(catalogService, unchangedRelease);
        CatalogDtos.AssistantReleaseResourceDto republishedToolBinding = publishedV2.currentRelease().resources().stream()
            .filter(item -> item.resourceId().equals(tool.id()))
            .findFirst()
            .orElseThrow();
        assertEquals(newModelVersion.id(), publishedV2.currentRelease().defaultModelBinding().resourceVersionId());
        assertEquals(newModelVersion.version(), publishedV2.currentRelease().defaultModelBinding().resourceVersion());
        assertEquals(newToolVersion.id(), republishedToolBinding.resourceVersionId());
        assertEquals(newToolVersion.version(), republishedToolBinding.resourceVersion());
    }

    @Test
    void shouldKeepPublishedKnowledgeBindingFrozenUntilAssistantRepublished() {
        CatalogService catalogService = new CatalogService(new InMemoryCatalogRepository(), readySnapshotKnowledgeClient(), noopKnowledgeWorkflowGateway());
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("冻结知识域", "验证知识版本冻结"));
        CatalogDtos.ScenarioDto scenario = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "冻结知识场景", "测试知识绑定锚点")
        );
        CatalogDtos.KnowledgeBaseDto knowledgeBase = catalogService.createKnowledgeBase(
            new CatalogDtos.CreateKnowledgeBaseRequest(
                domain.id(),
                "冻结知识库",
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "用于验证知识版本冻结",
                "知识团队",
                List.of("KB")
            )
        );
        CatalogDtos.KnowledgeReleaseDto knowledgeV1 = catalogService.createKnowledgeRelease(
            knowledgeBase.id(),
            new CatalogDtos.CreateKnowledgeReleaseRequest(
                "知识 v1",
                VersionStatus.PUBLISHED,
                "snapshot-knowledge-v1",
                new CatalogDtos.KnowledgeRetrievalProfileDto(5, "HYBRID", 0.1)
            )
        );
        CatalogDtos.ResourceDto defaultModel = catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "知识默认模型",
                ResourceType.LLM_MODEL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "用于知识助手",
                "平台模型团队",
                List.of("LLM"),
                new CatalogDtos.CreateResourceVersionRequest("模型 v1", VersionStatus.PUBLISHED, null)
            )
        );
        CatalogDtos.AssistantDto assistant = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(
                scenario.id(),
                "知识助手",
                "发布后知识绑定应被冻结",
                new CatalogDtos.AssistantModelPolicyDto(defaultModel.id()),
                new CatalogDtos.KnowledgeAccessPolicyDto(true, knowledgeBase.id()),
                null
            )
        );
        catalogService.createAgent(new CatalogDtos.CreateAgentRequest(
            assistant.id(),
            "知识执行智能体",
            "support",
            "使用知识库检索",
            new CatalogDtos.AgentExecutionPolicyDto(true, null, "", true, false, knowledgeBase.id(), 8, List.of(), List.of())
        ));

        CatalogDtos.AssistantDto publishedV1 = publishAssistant(catalogService, assistant);
        CatalogDtos.KnowledgeBindingSnapshotDto frozenAssistantKnowledge = publishedV1.currentRelease().assistantKnowledgeBinding();
        CatalogDtos.KnowledgeBindingSnapshotDto frozenAgentKnowledge = publishedV1.currentRelease().agents().getFirst().knowledgeBinding();
        assertEquals(knowledgeV1.id(), frozenAssistantKnowledge.knowledgeReleaseId());
        assertEquals(knowledgeV1.version(), frozenAssistantKnowledge.knowledgeReleaseVersion());
        assertEquals("snapshot-knowledge-v1", frozenAssistantKnowledge.snapshotId());

        CatalogDtos.KnowledgeReleaseDto knowledgeV2 = catalogService.createKnowledgeRelease(
            knowledgeBase.id(),
            new CatalogDtos.CreateKnowledgeReleaseRequest(
                "知识 v2",
                VersionStatus.PUBLISHED,
                "snapshot-knowledge-v2",
                new CatalogDtos.KnowledgeRetrievalProfileDto(8, "VECTOR", 0.3)
            )
        );

        CatalogDtos.AssistantDto unchangedRelease = catalogService.listAssistants().stream()
            .filter(item -> item.id().equals(assistant.id()))
            .findFirst()
            .orElseThrow();
        assertEquals(frozenAssistantKnowledge.knowledgeReleaseId(), unchangedRelease.currentRelease().assistantKnowledgeBinding().knowledgeReleaseId());
        assertEquals(frozenAssistantKnowledge.knowledgeReleaseVersion(), unchangedRelease.currentRelease().assistantKnowledgeBinding().knowledgeReleaseVersion());
        assertEquals(frozenAssistantKnowledge.snapshotId(), unchangedRelease.currentRelease().assistantKnowledgeBinding().snapshotId());
        assertEquals(frozenAgentKnowledge.knowledgeReleaseId(), unchangedRelease.currentRelease().agents().getFirst().knowledgeBinding().knowledgeReleaseId());
        assertEquals(frozenAgentKnowledge.knowledgeReleaseVersion(), unchangedRelease.currentRelease().agents().getFirst().knowledgeBinding().knowledgeReleaseVersion());
        assertEquals(knowledgeV2.id(), catalogService.getKnowledgeBase(knowledgeBase.id()).effectiveRelease().id());

        CatalogDtos.AssistantDto publishedV2 = publishAssistant(catalogService, unchangedRelease);
        assertEquals(knowledgeV2.id(), publishedV2.currentRelease().assistantKnowledgeBinding().knowledgeReleaseId());
        assertEquals(knowledgeV2.version(), publishedV2.currentRelease().assistantKnowledgeBinding().knowledgeReleaseVersion());
        assertEquals("snapshot-knowledge-v2", publishedV2.currentRelease().assistantKnowledgeBinding().snapshotId());
        assertEquals(knowledgeV2.id(), publishedV2.currentRelease().agents().getFirst().knowledgeBinding().knowledgeReleaseId());
        assertEquals(knowledgeV2.version(), publishedV2.currentRelease().agents().getFirst().knowledgeBinding().knowledgeReleaseVersion());
    }

    @Test
    void shouldStillFreezeToolVersionsWhenPublishingAssistant() {
        CatalogService catalogService = new CatalogService(new InMemoryCatalogRepository(), readySnapshotKnowledgeClient(), noopKnowledgeWorkflowGateway());
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("交付域", "承载交付流程"));
        CatalogDtos.ScenarioDto scenario = catalogService.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "交付跟进", "跟进交付流程")
        );
        CatalogDtos.ResourceDto defaultModel = catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "交付默认模型",
                ResourceType.LLM_MODEL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "交付助手默认模型",
                "交付团队",
                List.of("LLM"),
                null
            )
        );
        CatalogDtos.AssistantDto assistant = catalogService.createAssistant(
            new CatalogDtos.CreateAssistantRequest(
                scenario.id(),
                "交付助手",
                "处理交付跟进",
                new CatalogDtos.AssistantModelPolicyDto(defaultModel.id()),
                null,
                null
            )
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

        CatalogDtos.AssistantDto published = publishAssistant(catalogService, assistant);
        assertEquals(VersionStatus.PUBLISHED, published.version().status());
        assertFalse(published.currentRelease().agents().getFirst().toolResourceVersionIds().isEmpty());
    }

    @Test
    void shouldDeleteUnusedResourceAndProtectBoundResources() {
        CatalogService catalogService = new CatalogService(new InMemoryCatalogRepository(), readySnapshotKnowledgeClient(), noopKnowledgeWorkflowGateway());
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
        CustomerOpsFixture fixture = customerOpsFixture();
        assertThrows(IllegalStateException.class, () -> fixture.service().deleteResource(fixture.toolResourceId()));
    }

    @Test
    void shouldUpdateResourceMetadataIncludingName() {
        CatalogService catalogService = new CatalogService(new InMemoryCatalogRepository(), readySnapshotKnowledgeClient(), noopKnowledgeWorkflowGateway());
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
        PlatformEventService platformEventService = new PlatformEventService(
            new PlatformEventRepository.InMemoryPlatformEventRepository(),
            testCurrentUserResolver()
        );
        CatalogService catalogService = new CatalogService(
            new InMemoryCatalogRepository(),
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway(),
            platformEventService
        );
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

        List<String> eventTypes = platformEventService.listEvents(
            PlatformAggregateType.RESOURCE,
            resource.id(),
            null,
            20,
            null
        ).items().stream().map(item -> item.eventType()).toList();
        assertTrue(eventTypes.contains("RESOURCE_VERSION_UPDATED"));
        assertTrue(eventTypes.contains("RESOURCE_VERSION_PUBLISHED"));
    }

    private static KnowledgeServiceClient readySnapshotKnowledgeClient() {
        return new KnowledgeServiceClient("http://localhost:8091", "test-internal-token") {
            @Override
            public CatalogDtos.KnowledgeIndexSnapshotDto getIndexSnapshot(String snapshotId) {
                return new CatalogDtos.KnowledgeIndexSnapshotDto(
                    snapshotId,
                    "knowledge-base-support",
                    "PGVECTOR",
                    "HYBRID",
                    "READY",
                    "READY",
                    100,
                    0,
                    false,
                    1,
                    2,
                    null,
                    Instant.now(),
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

    private static CustomerOpsFixture customerOpsFixture() {
        return customerOpsFixture(PlatformEventService.disabled());
    }

    private static CustomerOpsFixture customerOpsFixture(PlatformEventService platformEventService) {
        CatalogService service = new CatalogService(
            new InMemoryCatalogRepository(),
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway(),
            platformEventService
        );
        CatalogDtos.BusinessDomainDto domain = service.createDomain(new CatalogDtos.CreateDomainRequest("客服运营域", "承载客服体验数据"));
        CatalogDtos.ScenarioDto scenario = service.createScenario(
            new CatalogDtos.CreateScenarioRequest(domain.id(), "客服处理", "处理用户咨询与售后问题")
        );
        CatalogDtos.KnowledgeBaseDto knowledgeBase = service.createKnowledgeBase(
            new CatalogDtos.CreateKnowledgeBaseRequest(
                domain.id(),
                "客服知识库",
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "用于客服问答与流程说明",
                "知识运营",
                List.of("FAQ", "支持")
            )
        );
        service.createKnowledgeRelease(
            knowledgeBase.id(),
            new CatalogDtos.CreateKnowledgeReleaseRequest(
                "客服知识正式版",
                VersionStatus.PUBLISHED,
                "snapshot-kb-support-v1",
                new CatalogDtos.KnowledgeRetrievalProfileDto(5, "HYBRID", 0.1)
            )
        );
        service.createKnowledgeRelease(
            knowledgeBase.id(),
            new CatalogDtos.CreateKnowledgeReleaseRequest(
                "客服知识草稿版",
                VersionStatus.DRAFT,
                "snapshot-kb-support-v2",
                new CatalogDtos.KnowledgeRetrievalProfileDto(6, "HYBRID", 0.2)
            )
        );
        CatalogDtos.ResourceDto defaultModel = service.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "客服默认模型",
                ResourceType.LLM_MODEL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "客服助手默认模型",
                "平台模型团队",
                List.of("LLM"),
                null
            )
        );
        CatalogDtos.AssistantDto assistant = service.createAssistant(
            new CatalogDtos.CreateAssistantRequest(
                scenario.id(),
                "客服助手",
                "处理客服问题",
                new CatalogDtos.AssistantModelPolicyDto(defaultModel.id()),
                new CatalogDtos.KnowledgeAccessPolicyDto(true, knowledgeBase.id()),
                null
            )
        );
        CatalogDtos.ResourceDto tool = service.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "工单工具",
                ResourceType.TOOL,
                ShareScope.PRIVATE,
                "ASSISTANT",
                assistant.id(),
                "提交客服工单",
                "客服平台",
                List.of("工单"),
                null
            )
        );
        CatalogDtos.AgentDto agent = service.createAgent(new CatalogDtos.CreateAgentRequest(
            assistant.id(),
            "客服执行智能体",
            "support",
            "处理客服流转",
            new CatalogDtos.AgentExecutionPolicyDto(true, null, "", true, true, knowledgeBase.id(), 8, List.of(), List.of(tool.id()))
        ));
        CatalogDtos.PlaybookDto playbook = service.createPlaybook(new CatalogDtos.CreatePlaybookRequest(
            assistant.id(),
            "退款受理",
            "处理退款申请",
            "{\"type\":\"object\"}",
            "{\"type\":\"object\"}",
            new CatalogDtos.PlaybookExecutionPolicyDto("PT5M", "PT30S"),
            true,
            true,
            "start",
            List.of(
                new CatalogDtos.PlaybookNodeDto(
                    "start",
                    "开始",
                    com.lynxus.contracts.session.SessionContracts.PlaybookNodeType.STEP,
                    "",
                    "refund.start",
                    "2026.04.20",
                    null,
                    null,
                    Map.of(
                        "scriptVersions",
                        Map.of(
                            "2026.04.20",
                            Map.of("runtime", "python", "code", "result = {'statePatch': {}, 'routeKey': None}")
                        )
                    )
                ),
                new CatalogDtos.PlaybookNodeDto(
                    "finish",
                    "结束",
                    com.lynxus.contracts.session.SessionContracts.PlaybookNodeType.END,
                    "",
                    null,
                    null,
                    null,
                    null,
                    Map.of()
                )
            ),
            List.of(
                new CatalogDtos.PlaybookEdgeDto("start-to-finish", "start", "finish", null, null, true)
            )
        ));
        agent = service.updateAgent(
            agent.id(),
            new CatalogDtos.UpdateAgentRequest(
                agent.name(),
                agent.role(),
                agent.responsibility(),
                agent.executionPolicy(),
                agent.canOwnSession(),
                agent.allowedActions(),
                agent.switchableOwnerAgentIds(),
                List.of(playbook.id())
            )
        );
        publishAssistant(service, service.updateAssistant(
            assistant.id(),
            new CatalogDtos.UpdateAssistantRequest(
                assistant.name(),
                assistant.description(),
                VersionStatus.DRAFT,
                assistant.modelPolicy(),
                new CatalogDtos.KnowledgeAccessPolicyDto(true, knowledgeBase.id()),
                assistant.memoryPolicy()
            )
        ));
        return new CustomerOpsFixture(service, domain.id(), scenario.id(), assistant.id(), agent.id(), playbook.id(), knowledgeBase.id(), tool.id());
    }

    private static CatalogDtos.AssistantDto publishAssistant(CatalogService service, CatalogDtos.AssistantDto assistant) {
        CatalogDtos.AssistantDto current = service.getAssistant(assistant.id());
        String primaryAgentId = current.primaryAgentId();
        if (primaryAgentId == null || primaryAgentId.isBlank()) {
            primaryAgentId = service.listAgents().stream()
                .filter(agent -> agent.assistantId().equals(current.id()))
                .filter(CatalogDtos.AgentDto::canOwnSession)
                .map(CatalogDtos.AgentDto::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("assistant must have an owner-capable agent before publishing"));
        }
        return service.updateAssistant(
            current.id(),
            new CatalogDtos.UpdateAssistantRequest(
                current.name(),
                current.description(),
                VersionStatus.PUBLISHED,
                primaryAgentId,
                current.ownerPolicy(),
                current.sessionPolicy(),
                current.replyPolicy(),
                current.playbookPolicy(),
                current.modelPolicy(),
                current.knowledgeAccessPolicy(),
                current.memoryPolicy()
            )
        );
    }

    private record CustomerOpsFixture(
        CatalogService service,
        String domainId,
        String scenarioId,
        String assistantId,
        String agentId,
        String playbookId,
        String knowledgeBaseId,
        String toolResourceId
    ) {
    }

    private static CurrentUserResolver testCurrentUserResolver() {
        return () -> new PlatformUser(
            "user-test",
            "tester",
            "Tester",
            "tester@example.com",
            AuthSource.LOCAL_BOOTSTRAP,
            null,
            null,
            UserStatus.ACTIVE,
            Instant.parse("2026-04-01T00:00:00Z"),
            Instant.parse("2026-04-01T00:00:00Z"),
            Instant.parse("2026-04-01T00:00:00Z"),
            List.of(Role.DEVELOPER)
        );
    }
}
