package com.lynxus.platform.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.runtime.WorkflowContracts.ResourceType;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
import com.lynxus.contracts.session.SessionContracts.AgentDecisionAction;
import com.lynxus.platform.catalog.CatalogDtos.*;
import com.lynxus.platform.testing.EmbeddedPostgresTestDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JooqCatalogRepositoryTest {
    private static EmbeddedPostgresTestDatabase database;

    private JooqCatalogRepository repository;

    @BeforeAll
    static void startDatabase() throws Exception {
        database = new EmbeddedPostgresTestDatabase();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        database.close();
    }

    @BeforeEach
    void setUp() {
        database.reset();
        repository = new JooqCatalogRepository(database.dsl(), new ObjectMapper());
    }

    @Test
    void shouldPersistTypedCatalogRowsAndRefreshDerivedReferences() {
        AssistantDto assistant = new AssistantDto(
            "ast-1",
            "scn-1",
            "Test Assistant",
            "desc",
            new VersionDto("1.0.0", VersionStatus.PUBLISHED, Instant.parse("2026-04-21T00:00:00Z")),
            List.of(),
            List.of(),
            null,
            List.of(),
            "agt-1",
            new AssistantOwnerPolicyDto(3),
            new AssistantSessionPolicyDto("PT30M", "P7D", 20_000),
            new AssistantReplyPolicyDto(true),
            new AssistantPlaybookPolicyDto(null, null),
            new AssistantModelPolicyDto("res-llm-1"),
            "res-privacy-1",
            true,
            new KnowledgeAccessPolicyDto(true, "kb-1"),
            new MemoryPolicyDto(true, 8)
        );
        AgentDto agent = new AgentDto(
            "agt-1",
            "ast-1",
            "Test Agent",
            "worker",
            "handles requests",
            new AgentExecutionPolicyDto(
                false,
                "res-llm-2",
                "res-privacy-2",
                true,
                "system prompt",
                true,
                false,
                "kb-2",
                5,
                List.of("res-skill-1"),
                List.of("res-tool-1")
            ),
            true,
            List.of(AgentDecisionAction.REPLY, AgentDecisionAction.RUN_PLAYBOOK),
            List.of("agt-2"),
            List.of("playbook-1")
        );
        ResourceDto resource = new ResourceDto(
            "res-llm-1",
            "dom-1",
            "LLM",
            ResourceType.LLM_MODEL,
            ShareScope.DOMAIN_SHARED,
            "DOMAIN",
            "dom-1",
            "summary",
            "ops",
            List.of("llm"),
            null,
            null,
            List.of()
        );
        ResourceDto toolResource = new ResourceDto(
            "res-tool-1",
            "dom-1",
            "CRM Tool",
            ResourceType.TOOL,
            ShareScope.DOMAIN_SHARED,
            "DOMAIN",
            "dom-1",
            "summary",
            "ops",
            List.of("tool"),
            null,
            null,
            List.of()
        );
        ResourceVersionConfigurationDto toolConfiguration = new ResourceVersionConfigurationDto(
            ResourceType.TOOL,
            new ToolConfigDto(
                List.of(),
                new ToolConnectorConfigDto(
                    "enterprise.acme.crm",
                    null,
                    new ToolConnectorAccountSnapshotDto("integration-account-1", "vault://tool-secret"),
                    30,
                    "NONE",
                    Map.of("tenantId", "acme"),
                    Map.of()
                )
            ),
            null,
            null
        );
        AssistantReleaseDto release = new AssistantReleaseDto(
            "rel-1",
            "ast-1",
            "1.0.0",
            VersionStatus.PUBLISHED,
            Instant.parse("2026-04-21T00:00:00Z"),
            Instant.parse("2026-04-21T00:10:00Z"),
            new KnowledgeBindingSnapshotDto("kb-1", "Knowledge", "kr-1", "1.0.0", "snap-1", 5, "HYBRID", 0.5),
            new DefaultModelBindingDto("res-llm-1", "LLM", "rv-1", "1.0.0", "OPENAI_COMPATIBLE", "gpt-test"),
            null,
            true,
            List.of(
                new AssistantReleaseResourceDto("res-llm-1", "LLM", ResourceType.LLM_MODEL, "rv-1", "1.0.0", List.of(), null),
                new AssistantReleaseResourceDto("res-tool-1", "CRM Tool", ResourceType.TOOL, "rv-tool-1", "1.0.0", List.of("agt-1"), toolConfiguration)
            ),
            List.of(new AssistantReleaseAgentDto(
                "agt-1",
                "Test Agent",
                "worker",
                "handles requests",
                agent.executionPolicy(),
                new KnowledgeBindingSnapshotDto("kb-2", "Agent KB", "kr-2", "1.0.0", "snap-2", 5, "HYBRID", 0.5),
                null,
                false,
                true,
                agent.allowedActions(),
                agent.switchableOwnerAgentIds(),
                agent.playbookIds(),
                List.of("rv-skill-1"),
                List.of("rv-tool-1")
            )),
            List.of(),
            assistant.primaryAgentId(),
            assistant.ownerPolicy(),
            assistant.sessionPolicy(),
            assistant.replyPolicy(),
            assistant.playbookPolicy(),
            assistant.modelPolicy(),
            assistant.knowledgeAccessPolicy(),
            assistant.memoryPolicy()
        );

        repository.inWriteTransaction(() -> {
            repository.upsertDomain(new BusinessDomainDto("dom-1", "Domain", "desc", List.of(), List.of(), List.of()));
            repository.upsertScenario(new ScenarioDto("scn-1", "dom-1", "Scenario", "goal", new VersionDto("1.0.0", VersionStatus.PUBLISHED, Instant.now()), List.of()));
            repository.upsertAssistant(assistant);
            repository.upsertAgent(agent);
            repository.upsertResource(resource);
            repository.upsertResource(toolResource);
            repository.replaceResourceVersions("res-llm-1", List.of(new StoredResourceVersion(
                "rv-1",
                "res-llm-1",
                "1.0.0",
                VersionStatus.PUBLISHED,
                "summary",
                "digest",
                Instant.parse("2026-04-21T00:00:00Z"),
                Instant.parse("2026-04-21T00:10:00Z"),
                new ResourceVersionConfigurationDto(ResourceType.LLM_MODEL, null, new LlmModelConfigDto("OPENAI_COMPATIBLE", "gpt-test", null, null, 0.2, 2048), null)
            )));
            repository.replaceResourceVersions("res-tool-1", List.of(new StoredResourceVersion(
                "rv-tool-1",
                "res-tool-1",
                "1.0.0",
                VersionStatus.PUBLISHED,
                "summary",
                "digest",
                Instant.parse("2026-04-21T00:00:00Z"),
                Instant.parse("2026-04-21T00:10:00Z"),
                toolConfiguration
            )));
            repository.replaceAssistantReleases("ast-1", List.of(release));
            return null;
        });

        assertEquals(1, repository.listDomains().size());
        assertEquals(1, repository.listAssistants().size());
        assertEquals(1, repository.listResourceVersions("res-llm-1").size());
        assertEquals(1, repository.listResourceVersions("res-tool-1").size());
        assertEquals(1, repository.listAssistantReleases("ast-1").size());
        assertEquals(1, repository.findResourceBindings("res-llm-1").size());
        assertEquals(1, repository.findReleaseKnowledgeRefs("kb-1").size());
        ToolConnectorAccountSnapshotDto resourceSnapshot = repository.listResourceVersions("res-tool-1")
            .getFirst()
            .configuration()
            .tool()
            .connector()
            .accountSnapshot();
        assertNotNull(resourceSnapshot);
        assertTrue(resourceSnapshot.hasExternalSecretRef());
        assertEquals("vault://tool-secret", resourceSnapshot.runtimeSecretRef());
        ToolConnectorAccountSnapshotDto releaseSnapshot = repository.listAssistantReleases("ast-1")
            .getFirst()
            .resources()
            .stream()
            .filter(item -> "res-tool-1".equals(item.resourceId()))
            .findFirst()
            .orElseThrow()
            .configuration()
            .tool()
            .connector()
            .accountSnapshot();
        assertEquals("vault://tool-secret", releaseSnapshot.runtimeSecretRef());
        String publicJson = new ObjectMapper().writeValueAsString(releaseSnapshot);
        assertTrue(publicJson.contains("hasExternalSecretRef"));
        assertFalse(publicJson.contains("runtimeSecretRef"));
        assertFalse(publicJson.contains("vault://tool-secret"));
        assertEquals(0, ((Number) database.dsl()
            .fetchOne("select count(*) from information_schema.columns where table_name = 'catalog_assistant' and column_name = 'payload'")
            .get(0)).intValue());
        assertTrue(Boolean.TRUE.equals(database.dsl().fetchValue(
            "select exists(select 1 from catalog_assistant where name = 'Test Assistant')",
            Boolean.class
        )));
    }
}
