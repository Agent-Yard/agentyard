package com.agentyard.platform.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agentyard.contracts.runtime.WorkflowContracts.ShareScope;
import com.agentyard.contracts.runtime.WorkflowContracts.VersionStatus;
import com.agentyard.platform.catalog.CatalogDtos.KnowledgeBaseDto;
import com.agentyard.platform.catalog.CatalogDtos.KnowledgeReleaseDto;
import com.agentyard.platform.catalog.CatalogDtos.KnowledgeRetrievalProfileDto;
import com.agentyard.platform.testing.EmbeddedPostgresTestDatabase;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JooqKnowledgeRepositoryTest {
    private static EmbeddedPostgresTestDatabase database;

    private JooqKnowledgeRepository repository;

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
        repository = new JooqKnowledgeRepository(database.dsl(), new ObjectMapper());
    }

    @Test
    void shouldPersistTypedKnowledgeBaseAndReleaseRows() {
        repository.inWriteTransaction(() -> {
            repository.upsertKnowledgeBase(new KnowledgeBaseDto(
                "kb-1",
                "dom-1",
                "Knowledge Base",
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                "dom-1",
                "summary",
                "ops",
                List.of("faq"),
                null,
                null,
                List.of()
            ));
            repository.replaceKnowledgeReleases("kb-1", List.of(new KnowledgeReleaseDto(
                "kr-1",
                "kb-1",
                "1.0.0",
                VersionStatus.PUBLISHED,
                "summary",
                "snapshot-1",
                new KnowledgeRetrievalProfileDto(6, "HYBRID", 0.3),
                Instant.parse("2026-04-21T00:00:00Z"),
                Instant.parse("2026-04-21T00:10:00Z")
            )));
            return null;
        });

        assertEquals(1, repository.listKnowledgeBases().size());
        assertEquals(1, repository.listKnowledgeReleases("kb-1").size());
        assertEquals("Knowledge Base", repository.findKnowledgeBase("kb-1").orElseThrow().name());
        assertEquals(0, ((Number) database.dsl()
            .fetchOne("select count(*) from information_schema.columns where table_name = 'knowledge_base' and column_name = 'payload'")
            .get(0)).intValue());
        assertEquals(1, repository.revision());
    }
}
