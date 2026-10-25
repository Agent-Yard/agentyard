package com.agentyard.platform.catalog;

import static org.junit.jupiter.api.Assertions.*;
import com.agentyard.contracts.runtime.WorkflowContracts.*;
import com.agentyard.platform.catalog.CatalogDtos.*;
import com.agentyard.platform.knowledge.*;
import com.agentyard.platform.testing.EmbeddedPostgresTestDatabase;
import java.util.List;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

class JooqCatalogRepositoryTest {
    private static EmbeddedPostgresTestDatabase database;
    @BeforeAll static void start() throws Exception { database = new EmbeddedPostgresTestDatabase(); }
    @AfterAll static void stop() throws Exception { database.close(); }
    @BeforeEach void reset() { database.reset(); }
    @Test void persistsIndependentConfigurationAndKeepsReferencedVersions() {
        var repository = new JooqCatalogRepository(database.dsl(), new ObjectMapper());
        var service = new CatalogService(repository, new KnowledgeService(new JooqKnowledgeRepository(database.dsl(), new ObjectMapper()), repository,
            new KnowledgeServiceClient("http://knowledge.test", "test-token"), new KnowledgeWorkflowGateway() {
                public void startImport(String base, String job) {}
                public void startIndexBuild(String base, String job) {}
            }));
        var model = CatalogServiceTest.model(service, "initial");
        var template = CatalogServiceTest.template(service, model.effectiveVersion().id(), List.of(), List.of());
        var entry = service.createEntry(new EntryRequest("Reception", "", "PERSON", "operator", ShareScope.PRIVATE,
            template.effectiveVersion().id(), true, List.of(template.id()), List.of()));
        var next = service.createRoleTemplateVersion(template.id(), new CreateRoleTemplateVersionRequest(VersionStatus.PUBLISHED,
            CatalogServiceTest.roleConfig(model.effectiveVersion().id(), List.of(), List.of())));
        var restored = new JooqCatalogRepository(database.dsl(), new ObjectMapper());
        assertEquals(2, restored.listRoleTemplateVersions(template.id()).size());
        assertEquals(template.effectiveVersion().id(), restored.findEntry(entry.id()).orElseThrow().receptionTemplateVersionId());
        assertEquals(VersionStatus.PUBLISHED, restored.findRoleTemplateVersion(template.effectiveVersion().id()).orElseThrow().status());
        assertEquals(next.configuration(), restored.findRoleTemplateVersion(next.id()).orElseThrow().configuration());
        assertEquals("initial", service.resolveRoleTemplateVersion(template.effectiveVersion().id()).model().modelId());
        assertEquals(0, database.dsl().fetchCount(org.jooq.impl.DSL.table("catalog_role_template"), org.jooq.impl.DSL.field("owner_id").isNull()));
    }
}
