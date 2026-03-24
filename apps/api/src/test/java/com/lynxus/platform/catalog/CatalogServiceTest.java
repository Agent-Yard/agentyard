package com.lynxus.platform.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
