package com.lynxus.platform.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.extension.sdk.protocol.LynxusExtensionHeaders;
import com.lynxus.extension.sdk.registration.ExtensionRegistrationLoader;
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
import com.lynxus.platform.extension.ExtensionDefinitionService;
import com.lynxus.platform.extension.ExtensionRegistrationProperties;
import com.lynxus.platform.extension.ExtensionRegistrationService;
import com.lynxus.platform.integration.IntegrationAccountRepository;
import com.lynxus.platform.integration.IntegrationAccountService;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountAvailabilityDecision;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountCredentialStatus;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountStatus;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountSubjectType;
import com.lynxus.platform.integration.IntegrationDtos.StoredIntegrationAccount;
import com.lynxus.platform.knowledge.KnowledgeServiceClient;
import com.lynxus.platform.knowledge.KnowledgeWorkflowGateway;
import com.lynxus.platform.shared.ApiProblemException;
import com.lynxus.platform.shared.ConflictException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class CatalogServiceTest {
    @Test
    void shouldRejectCatalogWriteWhenAnotherInstanceCommitsDuringMutation() {
        ConflictingCatalogRepository repository = new ConflictingCatalogRepository();
        CatalogService service = new CatalogService(
            repository,
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway()
        );

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> service.createDomain(new CatalogDtos.CreateDomainRequest("本地提交", "local"))
        );

        assertEquals("catalog changed on another instance; retry the request", error.getMessage());
        assertEquals(List.of("远端提交"), service.listDomains().stream().map(CatalogDtos.BusinessDomainDto::name).toList());
    }

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
        CatalogService service = catalogServiceWithCoreToolConnectors();
        CatalogDtos.CatalogSummaryDto summary = service.summary();

        assertTrue(summary.domains().isEmpty());
        assertTrue(summary.resources().isEmpty());
        assertTrue(summary.knowledgeBases().isEmpty());
    }

    @Test
    void shouldKeepFreshCatalogReadsReadOnlyAtRevisionZero() {
        ReadOnlyGuardCatalogRepository catalogRepository = new ReadOnlyGuardCatalogRepository();
        ReadOnlyGuardKnowledgeRepository knowledgeRepository = new ReadOnlyGuardKnowledgeRepository();
        CatalogService service = catalogServiceWithCoreToolConnectors(
            catalogRepository,
            knowledgeRepository,
            PlatformEventService.disabled(),
            null
        );

        CatalogDtos.CatalogSummaryDto summary = service.summary();

        assertTrue(summary.domains().isEmpty());
        assertTrue(summary.knowledgeBases().isEmpty());
        assertTrue(service.listDomains().isEmpty());
        assertTrue(service.listKnowledgeBases().isEmpty());
        assertEquals(0, service.resourceCenter().totalResources());
        assertEquals(0L, catalogRepository.revision());
        assertEquals(0L, knowledgeRepository.revision());
        assertEquals(0, catalogRepository.writeAttempts());
        assertEquals(0, knowledgeRepository.writeAttempts());
    }

    @Test
    void shouldKeepCatalogAndKnowledgeReadsOnSingleSnapshotWithinSummary() {
        com.lynxus.platform.knowledge.InMemoryKnowledgeRepository knowledgeRepository =
            new com.lynxus.platform.knowledge.InMemoryKnowledgeRepository();
        MutatingCatalogRepository catalogRepository = new MutatingCatalogRepository(knowledgeRepository);
        CatalogService service = catalogServiceWithCoreToolConnectors(
            catalogRepository,
            knowledgeRepository,
            PlatformEventService.disabled(),
            null
        );

        service.createDomain(new CatalogDtos.CreateDomainRequest("知识运营域", "承载知识沉淀"));

        CatalogDtos.CatalogSummaryDto summary = service.summary();

        assertTrue(summary.knowledgeBases().isEmpty());
        assertEquals(1, service.listKnowledgeBases().size());
        assertEquals("并发写入知识库", service.listKnowledgeBases().getFirst().name());
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
                            Map.of("code", "result = {'statePatch': {}, 'routeKey': None}"),
                            nodeLayout(120, 120)
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
                            Map.of(),
                            nodeLayout(420, 120)
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
                            Map.of(),
                            nodeLayout(120, 120)
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
                            Map.of(),
                            nodeLayout(420, 120)
                        )
                    ),
                    List.of(new CatalogDtos.PlaybookEdgeDto("start-to-finish", "start", "finish", null, null, true))
                )
            )
        );

        assertTrue(error.getMessage().contains("STEP node must define config.scriptVersions"));
    }

    @Test
    void shouldPersistPlaybookNodeLayout() {
        CustomerOpsFixture fixture = customerOpsFixture();

        CatalogDtos.PlaybookDto created = fixture.service().createPlaybook(
            new CatalogDtos.CreatePlaybookRequest(
                fixture.assistantId(),
                "布局校验",
                "保存节点坐标",
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
                        ),
                        nodeLayout(180, 140)
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
                        Map.of(),
                        nodeLayout(480, 140)
                    )
                ),
                List.of(new CatalogDtos.PlaybookEdgeDto("start-to-finish", "start", "finish", null, null, true))
            )
        );

        assertEquals(180, created.nodes().get(0).layout().x());
        assertEquals(140, created.nodes().get(0).layout().y());
        assertEquals(480, fixture.service().getPlaybook(created.id()).nodes().get(1).layout().x());
    }

    @Test
    void shouldRejectToolTaskNodeWithoutToolBinding() {
        CustomerOpsFixture fixture = customerOpsFixture();

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> fixture.service().createPlaybook(
                new CatalogDtos.CreatePlaybookRequest(
                    fixture.assistantId(),
                    "无效 Tool 节点",
                    "缺少 Tool 绑定",
                    "{\"type\":\"object\"}",
                    "{\"type\":\"object\"}",
                    new CatalogDtos.PlaybookExecutionPolicyDto("PT5M", "PT30S"),
                    true,
                    true,
                    "tool-step",
                    List.of(
                        new CatalogDtos.PlaybookNodeDto(
                            "tool-step",
                            "工具节点",
                            com.lynxus.contracts.session.SessionContracts.PlaybookNodeType.TOOL_TASK,
                            "",
                            null,
                            null,
                            null,
                            null,
                            Map.of(),
                            nodeLayout(120, 120)
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
                            Map.of(),
                            nodeLayout(420, 120)
                        )
                    ),
                    List.of(new CatalogDtos.PlaybookEdgeDto("tool-to-finish", "tool-step", "finish", null, null, true))
                )
            )
        );

        assertTrue(error.getMessage().contains("TOOL_TASK node must define toolId and toolOperation"));
    }

    @Test
    void shouldRejectDuplicatePlaybookEdgeKeys() {
        CustomerOpsFixture fixture = customerOpsFixture();

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> fixture.service().createPlaybook(
                new CatalogDtos.CreatePlaybookRequest(
                    fixture.assistantId(),
                    "重复边",
                    "edgeKey 冲突",
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
                            ),
                            nodeLayout(120, 120)
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
                            Map.of(),
                            nodeLayout(420, 120)
                        )
                    ),
                    List.of(
                        new CatalogDtos.PlaybookEdgeDto("start-to-finish", "start", "finish", null, null, true),
                        new CatalogDtos.PlaybookEdgeDto("start-to-finish", "start", "finish", "retry", "重试", false)
                    )
                )
            )
        );

        assertTrue(error.getMessage().contains("duplicate playbook edgeKey"));
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
        CatalogService catalogService = catalogServiceWithCoreToolConnectors();
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
                new CatalogDtos.CreateResourceVersionRequest(
                    "Tool v1",
                    VersionStatus.PUBLISHED,
                    toolResourceConfiguration(simpleHttpToolConfig())
                )
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
            new CatalogDtos.CreateResourceVersionRequest(
                "Tool v2",
                VersionStatus.PUBLISHED,
                toolResourceConfiguration(simpleHttpToolConfig())
            )
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
        CatalogService catalogService = catalogServiceWithCoreToolConnectors();
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
                new CatalogDtos.CreateResourceVersionRequest(
                    "交付 Tool 初始版本",
                    VersionStatus.DRAFT,
                    toolResourceConfiguration(simpleHttpToolConfig())
                )
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
        CatalogService catalogService = catalogServiceWithCoreToolConnectors();
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
                new CatalogDtos.CreateResourceVersionRequest(
                    "质检 Tool 初始版本",
                    VersionStatus.DRAFT,
                    toolResourceConfiguration(simpleHttpToolConfig())
                )
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
        CatalogService catalogService = catalogServiceWithCoreToolConnectors(platformEventService);
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
                new CatalogDtos.CreateResourceVersionRequest(
                    "初始草稿",
                    VersionStatus.DRAFT,
                    toolResourceConfiguration(simpleHttpToolConfig())
                )
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

    @Test
    void shouldNormalizeToolConnectorConfigFromExtensionDefinitionDescriptorId() {
        CatalogService catalogService = catalogServiceWithToolConnectors(
            List.of(crmToolConnectorDescriptor()),
            null,
            null
        );
        CatalogDtos.ResourceDto tool = createToolResource(
            catalogService,
            toolConfig(
                "enterprise.acme.crm",
                null,
                Map.of("tenantId", "acme"),
                Map.of("query_customer", Map.of("endpoint", "/customers"))
            )
        );

        CatalogDtos.ToolConnectorConfigDto connector = tool.versions().getFirst().configuration().tool().connector();

        assertEquals("enterprise.acme.crm", connector.connectorType());
        assertNull(connector.accountId());
        assertNull(connector.accountSnapshot());
        assertEquals(Map.of("tenantId", "acme"), connector.config());
        assertEquals(Map.of("endpoint", "/customers"), connector.operationMappings().get("query_customer"));
    }

    @Test
    void shouldRejectToolConnectorConfigThatDoesNotMatchCurrentDefinitionSchema() {
        CatalogService catalogService = catalogServiceWithToolConnectors(
            List.of(crmToolConnectorDescriptor()),
            null,
            null
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> createToolResource(
            catalogService,
            toolConfig(
                "enterprise.acme.crm",
                null,
                Map.of(),
                Map.of("query_customer", Map.of("endpoint", "/customers"))
            )
        ));

        assertTrue(error.getMessage().contains("tool connector config does not satisfy connector schema"));
    }

    @Test
    void shouldRequireExplicitOperationMappingForEveryToolOperation() {
        CatalogService catalogService = catalogServiceWithToolConnectors(
            List.of(crmToolConnectorDescriptor()),
            null,
            null
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> createToolResource(
            catalogService,
            new CatalogDtos.ToolConfigDto(
                List.of(
                    new CatalogDtos.ToolOperationDto("query_customer", "查询客户", "{\"type\":\"object\"}", "{\"type\":\"object\"}"),
                    new CatalogDtos.ToolOperationDto("create_ticket", "创建工单", "{\"type\":\"object\"}", "{\"type\":\"object\"}")
                ),
                new CatalogDtos.ToolConnectorConfigDto(
                    "enterprise.acme.crm",
                    null,
                    null,
                    20,
                    "NONE",
                    Map.of("tenantId", "acme"),
                    Map.of("query_customer", Map.of("endpoint", "/customers"))
                )
            )
        ));

        assertTrue(error.getMessage().contains("operationMappings missing operation: create_ticket"));
    }

    @Test
    void shouldRejectSecretLikeMaterialInToolConnectorConfigAndOperationMappings() {
        CatalogService catalogService = catalogServiceWithToolConnectors(
            List.of(crmToolConnectorDescriptor()),
            null,
            null
        );

        IllegalArgumentException configError = assertThrows(IllegalArgumentException.class, () -> createToolResource(
            catalogService,
            toolConfig(
                "enterprise.acme.crm",
                null,
                Map.of("tenantId", "acme", "apiKey", "secret"),
                Map.of("query_customer", Map.of("endpoint", "/customers"))
            )
        ));
        IllegalArgumentException mappingError = assertThrows(IllegalArgumentException.class, () -> createToolResource(
            catalogService,
            toolConfig(
                "enterprise.acme.crm",
                null,
                Map.of("tenantId", "acme"),
                Map.of("query_customer", Map.of("endpoint", "/customers", "externalSecretRef", "vault://secret"))
            )
        ));

        assertTrue(configError.getMessage().contains("secret-like key"));
        assertTrue(mappingError.getMessage().contains("secret-like key"));
    }

    @Test
    void shouldRejectToolResourceWithoutExplicitConnectorConfig() {
        CatalogService catalogService = catalogServiceWithCoreToolConnectors();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> createToolResource(
            catalogService,
            new CatalogDtos.ToolConfigDto(
                List.of(new CatalogDtos.ToolOperationDto("query_customer", "查询客户", "{\"type\":\"object\"}", "{\"type\":\"object\"}")),
                null
            )
        ));

        assertEquals("tool connector config is required", error.getMessage());
    }

    @Test
    void shouldRejectToolResourceWithoutToolConfig() {
        CatalogService catalogService = catalogServiceWithCoreToolConnectors();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> createToolResource(
            catalogService,
            null
        ));

        assertEquals("tool config is required", error.getMessage());
    }

    @Test
    void shouldRejectToolResourceVersionCreateWithNullConfiguration() {
        CatalogService catalogService = catalogServiceWithCoreToolConnectors();
        CatalogDtos.ResourceDto resource = createToolResource(catalogService, simpleHttpToolConfig());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> catalogService.createResourceVersion(
            resource.id(),
            new CatalogDtos.CreateResourceVersionRequest("null config", VersionStatus.DRAFT, null)
        ));

        assertEquals("tool resource version configuration is required", error.getMessage());
    }

    @Test
    void shouldRejectToolResourceVersionUpdateWithNullConfiguration() {
        CatalogService catalogService = catalogServiceWithCoreToolConnectors();
        CatalogDtos.ResourceDto resource = createDraftToolResource(catalogService, simpleHttpToolConfig());
        CatalogDtos.ResourceVersionDto draftVersion = resource.versions().getFirst();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> catalogService.updateResourceVersion(
            resource.id(),
            draftVersion.id(),
            new CatalogDtos.UpdateResourceVersionRequest("null config", VersionStatus.DRAFT, null)
        ));

        assertEquals("tool resource version configuration is required", error.getMessage());
    }

    @Test
    void shouldFailClearlyWhenToolConnectorDefinitionIsMissing() {
        CatalogService catalogService = catalogServiceWithToolConnectors(
            List.of(crmToolConnectorDescriptor()),
            null,
            null
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> createToolResource(
            catalogService,
            toolConfig(
                "enterprise.missing",
                null,
                Map.of("tenantId", "acme"),
                Map.of("query_customer", Map.of("endpoint", "/customers"))
            )
        ));

        assertEquals("tool connector definition not found: enterprise.missing", error.getMessage());
    }

    @Test
    void shouldFailClearlyWhenToolConnectorDefinitionsAreUnavailable() {
        CatalogService catalogService = new CatalogService(
            new InMemoryCatalogRepository(),
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway()
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> createToolResource(
            catalogService,
            simpleHttpToolConfig()
        ));

        assertEquals(
            "tool connector definition registry is unavailable; ExtensionDefinitionService is required",
            error.getMessage()
        );
    }

    @Test
    void shouldMaterializeToolConnectorAccountSnapshotWhenPublishingAssistantRelease() {
        RecordingIntegrationAccountService accountService = new RecordingIntegrationAccountService();
        CatalogService catalogService = catalogServiceWithToolConnectors(
            List.of(crmToolConnectorDescriptor()),
            accountService,
            new FixedIntegrationAccountRepository(storedAccount(
                "integration-account-1",
                "enterprise.acme.crm",
                "vault://tool-secret"
            ))
        );
        CatalogDtos.ToolConnectorConfigDto connector = publishedReleaseToolConnector(
            catalogService,
            toolConfig(
                "enterprise.acme.crm",
                "integration-account-1",
                Map.of("tenantId", "acme"),
                Map.of("query_customer", Map.of("endpoint", "/customers"))
            )
        );

        assertNull(connector.accountId());
        assertNotNull(connector.accountSnapshot());
        assertEquals("integration-account-1", connector.accountSnapshot().accountId());
        assertTrue(connector.accountSnapshot().hasExternalSecretRef());
        assertNull(connector.accountSnapshot().runtimeSecretRef());
        String publicJson = new ObjectMapper().writeValueAsString(connector);
        assertTrue(publicJson.contains("hasExternalSecretRef"));
        assertFalse(publicJson.contains("runtimeSecretRef"));
        assertFalse(publicJson.contains("vault://tool-secret"));
        assertEquals(IntegrationAccountSubjectType.TOOL_CONNECTOR, accountService.expectedSubjectType);
        assertEquals("enterprise.acme.crm", accountService.expectedSubjectId);
        assertEquals("integration-account-1", accountService.accountId);
    }

    @Test
    void shouldMaterializeToolConnectorAccountSnapshotWithoutExternalSecretRef() {
        CatalogService catalogService = catalogServiceWithToolConnectors(
            List.of(crmToolConnectorDescriptor()),
            new RecordingIntegrationAccountService(),
            new FixedIntegrationAccountRepository(storedAccount(
                "integration-account-1",
                "enterprise.acme.crm",
                null
            ))
        );

        CatalogDtos.ToolConnectorConfigDto connector = publishedReleaseToolConnector(
            catalogService,
            toolConfig(
                "enterprise.acme.crm",
                "integration-account-1",
                Map.of("tenantId", "acme"),
                Map.of("query_customer", Map.of("endpoint", "/customers"))
            )
        );

        assertNotNull(connector.accountSnapshot());
        assertEquals("integration-account-1", connector.accountSnapshot().accountId());
        assertFalse(connector.accountSnapshot().hasExternalSecretRef());
        assertNull(connector.accountSnapshot().runtimeSecretRef());
    }

    @Test
    void shouldHardBlockAssistantReleaseWhenSelectedToolConnectorAccountIsUnavailable() {
        RecordingIntegrationAccountService accountService = new RecordingIntegrationAccountService();
        accountService.error = new ApiProblemException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INTEGRATION_ACCOUNT_AVAILABILITY_BLOCKED",
            "INTEGRATION_ACCOUNT_AVAILABILITY_BLOCKED: blocked"
        );
        CatalogService catalogService = catalogServiceWithToolConnectors(
            List.of(crmToolConnectorDescriptor()),
            accountService,
            new FixedIntegrationAccountRepository(storedAccount(
                "integration-account-1",
                "enterprise.acme.crm",
                "vault://tool-secret"
            ))
        );

        ApiProblemException error = assertThrows(ApiProblemException.class, () -> publishedReleaseToolConnector(
            catalogService,
            toolConfig(
                "enterprise.acme.crm",
                "integration-account-1",
                Map.of("tenantId", "acme"),
                Map.of("query_customer", Map.of("endpoint", "/customers"))
            )
        ));

        assertEquals("INTEGRATION_ACCOUNT_AVAILABILITY_BLOCKED", error.code());
    }

    @Test
    void shouldRevalidateToolConnectorOperationMappingsAgainstCurrentDefinitionWhenPublishingAssistantRelease() {
        List<Map<String, Object>> descriptors = new java.util.ArrayList<>(List.of(crmToolConnectorDescriptor()));
        CatalogService catalogService = catalogServiceWithToolConnectors(descriptors, null, null);
        CatalogDtos.BusinessDomainDto domain = catalogService.createDomain(new CatalogDtos.CreateDomainRequest("漂移域", "schema drift"));
        CatalogDtos.ScenarioDto scenario = catalogService.createScenario(new CatalogDtos.CreateScenarioRequest(domain.id(), "漂移场景", "schema drift"));
        CatalogDtos.ResourceDto defaultModel = catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "默认模型",
                ResourceType.LLM_MODEL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "default model",
                "model team",
                List.of("llm"),
                new CatalogDtos.CreateResourceVersionRequest("model", VersionStatus.PUBLISHED, null)
            )
        );
        CatalogDtos.ResourceDto tool = catalogService.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "CRM Tool",
                ResourceType.TOOL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "CRM tool",
                "Tool team",
                List.of("crm"),
                new CatalogDtos.CreateResourceVersionRequest(
                    "tool",
                    VersionStatus.PUBLISHED,
                    new CatalogDtos.ResourceVersionConfigurationDto(
                        ResourceType.TOOL,
                        toolConfig(
                            "enterprise.acme.crm",
                            null,
                            Map.of("tenantId", "acme"),
                            Map.of("query_customer", Map.of("endpoint", "/customers"))
                        ),
                        null,
                        null
                    )
                )
            )
        );
        Map<String, Object> stricterDescriptor = new LinkedHashMap<>(crmToolConnectorDescriptor());
        stricterDescriptor.put("operationMappingSchema", objectSchema(
            Map.of("route", Map.of("type", "string", "minLength", 1)),
            List.of("route")
        ));
        descriptors.set(0, stricterDescriptor);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            publishAssistantWithTool(catalogService, scenario.id(), defaultModel.id(), tool.id())
        );

        assertTrue(error.getMessage().contains("tool connector operation mapping query_customer does not satisfy connector schema"));
    }

    private static CatalogService catalogServiceWithToolConnectors(
        List<Map<String, Object>> toolConnectorDescriptors,
        IntegrationAccountService integrationAccountService,
        IntegrationAccountRepository integrationAccountRepository
    ) {
        ExtensionDefinitionService definitionService = extensionDefinitionService(toolConnectorDescriptors);
        return new CatalogService(
            new InMemoryCatalogRepository(),
            new com.lynxus.platform.knowledge.InMemoryKnowledgeRepository(),
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway(),
            PlatformEventService.disabled(),
            null,
            definitionService,
            integrationAccountService,
            integrationAccountRepository
        );
    }

    private static CatalogService catalogServiceWithCoreToolConnectors() {
        return catalogServiceWithCoreToolConnectors(PlatformEventService.disabled());
    }

    private static CatalogService catalogServiceWithCoreToolConnectors(PlatformEventService platformEventService) {
        return catalogServiceWithCoreToolConnectors(
            new InMemoryCatalogRepository(),
            new com.lynxus.platform.knowledge.InMemoryKnowledgeRepository(),
            platformEventService,
            null
        );
    }

    private static CatalogService catalogServiceWithCoreToolConnectors(
        CatalogRepository catalogRepository,
        com.lynxus.platform.knowledge.KnowledgeRepository knowledgeRepository,
        PlatformEventService platformEventService,
        com.lynxus.platform.shared.redis.RedisInvalidationBus invalidationBus
    ) {
        return new CatalogService(
            catalogRepository,
            knowledgeRepository,
            readySnapshotKnowledgeClient(),
            noopKnowledgeWorkflowGateway(),
            platformEventService,
            invalidationBus,
            extensionDefinitionService(List.of()),
            null,
            null
        );
    }

    private static ExtensionDefinitionService extensionDefinitionService(List<Map<String, Object>> toolConnectorDescriptors) {
        return new ExtensionDefinitionService(
            registrationServiceWithRemoteToolConnector(!toolConnectorDescriptors.isEmpty()),
            (manifestUrl, headers) -> {
                String registrationId = headers.get(LynxusExtensionHeaders.REGISTRATION_ID);
                if (ExtensionRegistrationLoader.CORE_CHANNEL_GATEWAY_REGISTRATION_ID.equals(registrationId)) {
                    return manifest(List.of(channelProviderDescriptor("feishu")), List.of());
                }
                if (ExtensionRegistrationLoader.CORE_AGENT_RUNTIME_REGISTRATION_ID.equals(registrationId)) {
                    return manifest(List.of(), coreToolConnectorDescriptors());
                }
                if ("acme-remote".equals(registrationId)) {
                    return manifest(List.of(), toolConnectorDescriptors);
                }
                throw new AssertionError("unexpected registration id: " + registrationId);
            },
            "internal-token"
        );
    }

    private static ExtensionRegistrationService registrationServiceWithRemoteToolConnector(boolean includeRemoteToolConnector) {
        try {
            Path tempFile = Files.createTempFile("lynxus-catalog-tool-connector", ".yaml");
            Files.writeString(tempFile, includeRemoteToolConnector
                ? """
                    lynxus:
                      extensions:
                        services:
                          - registrationId: acme-remote
                            baseUrl: https://remote.example.com/private
                            exposes:
                              toolConnectorTypes:
                                - enterprise.acme.crm
                            auth:
                              type: INTERNAL_TOKEN
                    """
                : """
                    lynxus:
                      extensions:
                        services: []
                    """);
            return new ExtensionRegistrationService(
                new ExtensionRegistrationProperties(
                    tempFile.toString(),
                    "http://channel-gateway.example.com",
                    "http://agent-runtime.example.com"
                )
            );
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private static CatalogDtos.ResourceDto createToolResource(CatalogService service, CatalogDtos.ToolConfigDto toolConfig) {
        CatalogDtos.BusinessDomainDto domain = service.createDomain(new CatalogDtos.CreateDomainRequest("工具域-" + System.nanoTime(), "tool config"));
        return service.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "CRM Tool",
                ResourceType.TOOL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "CRM tool",
                "Tool team",
                List.of("crm"),
                new CatalogDtos.CreateResourceVersionRequest(
                    "tool version",
                    VersionStatus.PUBLISHED,
                    new CatalogDtos.ResourceVersionConfigurationDto(ResourceType.TOOL, toolConfig, null, null)
                )
            )
        );
    }

    private static CatalogDtos.ResourceDto createDraftToolResource(CatalogService service, CatalogDtos.ToolConfigDto toolConfig) {
        CatalogDtos.BusinessDomainDto domain = service.createDomain(new CatalogDtos.CreateDomainRequest("草稿工具域-" + System.nanoTime(), "tool config"));
        return service.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "Draft CRM Tool",
                ResourceType.TOOL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "Draft CRM tool",
                "Tool team",
                List.of("crm"),
                new CatalogDtos.CreateResourceVersionRequest(
                    "draft tool version",
                    VersionStatus.DRAFT,
                    new CatalogDtos.ResourceVersionConfigurationDto(ResourceType.TOOL, toolConfig, null, null)
                )
            )
        );
    }

    private static CatalogDtos.ToolConnectorConfigDto publishedReleaseToolConnector(
        CatalogService service,
        CatalogDtos.ToolConfigDto toolConfig
    ) {
        CatalogDtos.BusinessDomainDto domain = service.createDomain(new CatalogDtos.CreateDomainRequest("发布域-" + System.nanoTime(), "release"));
        CatalogDtos.ScenarioDto scenario = service.createScenario(new CatalogDtos.CreateScenarioRequest(domain.id(), "发布场景", "release"));
        CatalogDtos.ResourceDto defaultModel = service.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "默认模型",
                ResourceType.LLM_MODEL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "default model",
                "model team",
                List.of("llm"),
                new CatalogDtos.CreateResourceVersionRequest("model", VersionStatus.PUBLISHED, null)
            )
        );
        CatalogDtos.ResourceDto tool = service.createResource(
            new CatalogDtos.CreateResourceRequest(
                domain.id(),
                "CRM Tool",
                ResourceType.TOOL,
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "CRM tool",
                "Tool team",
                List.of("crm"),
                new CatalogDtos.CreateResourceVersionRequest(
                    "tool",
                    VersionStatus.PUBLISHED,
                    new CatalogDtos.ResourceVersionConfigurationDto(ResourceType.TOOL, toolConfig, null, null)
                )
            )
        );
        CatalogDtos.AssistantDto assistant = service.createAssistant(
            new CatalogDtos.CreateAssistantRequest(
                scenario.id(),
                "发布助手",
                "release assistant",
                new CatalogDtos.AssistantModelPolicyDto(defaultModel.id()),
                null,
                null
            )
        );
        CatalogDtos.AssistantDto published = publishAssistantWithTool(service, assistant, tool.id());
        return published.currentRelease().resources().stream()
            .filter(resource -> resource.resourceType() == ResourceType.TOOL)
            .findFirst()
            .orElseThrow()
            .configuration()
            .tool()
            .connector();
    }

    private static CatalogDtos.AssistantDto publishAssistantWithTool(
        CatalogService service,
        String scenarioId,
        String defaultModelId,
        String toolId
    ) {
        CatalogDtos.AssistantDto assistant = service.createAssistant(
            new CatalogDtos.CreateAssistantRequest(
                scenarioId,
                "发布助手",
                "release assistant",
                new CatalogDtos.AssistantModelPolicyDto(defaultModelId),
                null,
                null
            )
        );
        return publishAssistantWithTool(service, assistant, toolId);
    }

    private static CatalogDtos.AssistantDto publishAssistantWithTool(
        CatalogService service,
        CatalogDtos.AssistantDto assistant,
        String toolId
    ) {
        service.createAgent(new CatalogDtos.CreateAgentRequest(
            assistant.id(),
            "工具智能体",
            "owner",
            "owns tool calls",
            new CatalogDtos.AgentExecutionPolicyDto(true, null, "", false, false, null, 8, List.of(), List.of(toolId))
        ));
        return publishAssistant(service, assistant);
    }

    private static CatalogDtos.ToolConfigDto toolConfig(
        String connectorType,
        String accountId,
        Map<String, Object> config,
        Map<String, Map<String, Object>> operationMappings
    ) {
        return new CatalogDtos.ToolConfigDto(
            List.of(new CatalogDtos.ToolOperationDto("query_customer", "查询客户", "{\"type\":\"object\"}", "{\"type\":\"object\"}")),
            new CatalogDtos.ToolConnectorConfigDto(
                connectorType,
                accountId,
                null,
                20,
                "NONE",
                config,
                operationMappings
            )
        );
    }

    private static CatalogDtos.ToolConfigDto simpleHttpToolConfig() {
        return toolConfig(
            "simple-http",
            null,
            Map.of("baseUrl", "https://tools.example.com"),
            Map.of("query_customer", Map.of("endpoint", "/customers"))
        );
    }

    private static CatalogDtos.ResourceVersionConfigurationDto toolResourceConfiguration(CatalogDtos.ToolConfigDto toolConfig) {
        return new CatalogDtos.ResourceVersionConfigurationDto(ResourceType.TOOL, toolConfig, null, null);
    }

    private static List<Map<String, Object>> coreToolConnectorDescriptors() {
        return List.of(
            toolConnectorDescriptor(
                "business-code-secret-http",
                objectSchema(Map.of("baseUrl", Map.of("type", "string")), List.of()),
                objectSchema(Map.of("endpoint", Map.of("type", "string")), List.of())
            ),
            toolConnectorDescriptor(
                "mcp",
                objectSchema(Map.of("connectionUri", Map.of("type", "string")), List.of()),
                objectSchema(Map.of("tool", Map.of("type", "string")), List.of())
            ),
            toolConnectorDescriptor(
                "simple-http",
                objectSchema(Map.of("baseUrl", Map.of("type", "string")), List.of()),
                objectSchema(Map.of("endpoint", Map.of("type", "string")), List.of())
            )
        );
    }

    private static Map<String, Object> crmToolConnectorDescriptor() {
        return toolConnectorDescriptor(
            "enterprise.acme.crm",
            objectSchema(Map.of("tenantId", Map.of("type", "string", "minLength", 1)), List.of("tenantId")),
            objectSchema(Map.of("endpoint", Map.of("type", "string", "minLength", 1)), List.of("endpoint"))
        );
    }

    private static Map<String, Object> toolConnectorDescriptor(
        String connectorType,
        Map<String, Object> configSchema,
        Map<String, Object> operationMappingSchema
    ) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("connectorType", connectorType);
        descriptor.put("title", connectorType);
        descriptor.put("description", connectorType + " description");
        descriptor.put("accountConfigSchema", objectSchema(Map.of(), List.of()));
        descriptor.put("accountConfigUiSchema", List.of());
        descriptor.put("configSchema", configSchema);
        descriptor.put("configUiSchema", List.of());
        descriptor.put("operationMappingSchema", operationMappingSchema);
        descriptor.put("operationMappingUiSchema", List.of());
        descriptor.put("endpoints", Map.of("invoke", "/tools/" + connectorType.replace(".", "-") + "/invoke"));
        return descriptor;
    }

    private static Map<String, Object> channelProviderDescriptor(String providerType) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("providerType", providerType);
        descriptor.put("title", providerType);
        descriptor.put("description", providerType + " description");
        descriptor.put("accountConfigSchema", objectSchema(Map.of(), List.of()));
        descriptor.put("accountConfigUiSchema", List.of());
        descriptor.put("configSchema", objectSchema(Map.of(), List.of()));
        descriptor.put("configUiSchema", List.of());
        descriptor.put("defaultConfig", Map.of());
        descriptor.put("jobDefinitions", List.of());
        descriptor.put("endpoints", Map.of("sendOutbound", "/channel/send-outbound"));
        return descriptor;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return Map.copyOf(schema);
    }

    private static String manifest(List<Map<String, Object>> channelProviders, List<Map<String, Object>> toolConnectors) {
        Map<String, Object> descriptors = new LinkedHashMap<>();
        descriptors.put("channelProviders", channelProviders);
        descriptors.put("toolConnectors", toolConnectors);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("extensionApiVersion", 1);
        manifest.put("coreMinVersion", "0.8.0");
        manifest.put("coreMaxVersion", "0.9.x");
        manifest.put("descriptors", descriptors);
        return new ObjectMapper().writeValueAsString(manifest);
    }

    private static StoredIntegrationAccount storedAccount(String accountId, String subjectId, String externalSecretRef) {
        Instant now = Instant.now();
        return new StoredIntegrationAccount(
            accountId,
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            subjectId,
            "CRM Account",
            IntegrationAccountStatus.ENABLED,
            Map.of(),
            externalSecretRef,
            null,
            null,
            IntegrationAccountCredentialStatus.ACTIVE,
            Map.of(),
            now,
            now
        );
    }

    private static final class RecordingIntegrationAccountService extends IntegrationAccountService {
        String accountId;
        IntegrationAccountSubjectType expectedSubjectType;
        String expectedSubjectId;
        ApiProblemException error;

        RecordingIntegrationAccountService() {
            super(null, null, null, null);
        }

        @Override
        public IntegrationAccountAvailabilityDecision requireAccountAvailability(
            String accountId,
            IntegrationAccountSubjectType expectedSubjectType,
            String expectedSubjectId
        ) {
            this.accountId = accountId;
            this.expectedSubjectType = expectedSubjectType;
            this.expectedSubjectId = expectedSubjectId;
            if (error != null) {
                throw error;
            }
            return new IntegrationAccountAvailabilityDecision(
                accountId,
                expectedSubjectType,
                expectedSubjectId,
                IntegrationAccountStatus.ENABLED,
                IntegrationAccountCredentialStatus.ACTIVE,
                null,
                List.of()
            );
        }
    }

    private static final class FixedIntegrationAccountRepository extends IntegrationAccountRepository {
        private final StoredIntegrationAccount account;

        FixedIntegrationAccountRepository(StoredIntegrationAccount account) {
            super(null, new ObjectMapper());
            this.account = account;
        }

        @Override
        public Optional<StoredIntegrationAccount> findAccount(String accountId) {
            if (account.id().equals(accountId)) {
                return Optional.of(account);
            }
            return Optional.empty();
        }
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
        CatalogService service = catalogServiceWithCoreToolConnectors(platformEventService);
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
                new CatalogDtos.CreateResourceVersionRequest(
                    "工单工具初始版本",
                    VersionStatus.DRAFT,
                    toolResourceConfiguration(simpleHttpToolConfig())
                )
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
                    ),
                    nodeLayout(120, 120)
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
                    Map.of(),
                    nodeLayout(420, 120)
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

    private static CatalogDtos.PlaybookNodeLayoutDto nodeLayout(int x, int y) {
        return new CatalogDtos.PlaybookNodeLayoutDto(x, y);
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

    private static final class ConflictingCatalogRepository extends InMemoryCatalogRepository {
        private boolean injectConflict = true;

        @Override
        protected void commit(CatalogData working, long expectedRevision) {
            if (injectConflict) {
                injectConflict = false;
                CatalogData remoteData = committedCopy();
                remoteData.domains.clear();
                remoteData.domains.add(new CatalogDtos.BusinessDomainDto("domain-remote", "远端提交", "remote", List.of(), List.of(), List.of()));
                super.commit(remoteData, revision());
            }
            super.commit(working, expectedRevision);
        }
    }

    private static final class MutatingCatalogRepository extends InMemoryCatalogRepository {
        private final com.lynxus.platform.knowledge.InMemoryKnowledgeRepository knowledgeRepository;
        private boolean injected;

        private MutatingCatalogRepository(com.lynxus.platform.knowledge.InMemoryKnowledgeRepository knowledgeRepository) {
            this.knowledgeRepository = knowledgeRepository;
        }

        @Override
        public List<CatalogDtos.BusinessDomainDto> listDomains() {
            List<CatalogDtos.BusinessDomainDto> domains = super.listDomains();
            if (!injected && !domains.isEmpty()) {
                injected = true;
                knowledgeRepository.inWriteTransaction(() -> {
                    knowledgeRepository.upsertKnowledgeBase(
                        new CatalogDtos.KnowledgeBaseDto(
                            "kb-concurrent",
                            domains.getFirst().id(),
                            "并发写入知识库",
                            ShareScope.DOMAIN_SHARED,
                            "DOMAIN",
                            domains.getFirst().id(),
                            "summary should not observe this write mid-flight",
                            "知识运营",
                            List.of("snapshot"),
                            null,
                            null,
                            List.of()
                        )
                    );
                    return null;
                });
            }
            return domains;
        }
    }

    private static final class ReadOnlyGuardCatalogRepository extends InMemoryCatalogRepository {
        private int writeAttempts;

        @Override
        public <T> T inWriteTransaction(java.util.function.Supplier<T> action) {
            writeAttempts += 1;
            throw new AssertionError("catalog read path must not open a write transaction");
        }

        private int writeAttempts() {
            return writeAttempts;
        }
    }

    private static final class ReadOnlyGuardKnowledgeRepository extends com.lynxus.platform.knowledge.InMemoryKnowledgeRepository {
        private int writeAttempts;

        @Override
        public <T> T inWriteTransaction(java.util.function.Supplier<T> action) {
            writeAttempts += 1;
            throw new AssertionError("knowledge read path must not open a write transaction");
        }

        private int writeAttempts() {
            return writeAttempts;
        }
    }
}
