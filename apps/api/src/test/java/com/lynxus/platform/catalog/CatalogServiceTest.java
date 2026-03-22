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
        assertEquals("知识问答升级处理", summary.scenarios().getFirst().name());
        assertTrue(summary.resourceCenter().totalResources() >= 2);
    }
}
