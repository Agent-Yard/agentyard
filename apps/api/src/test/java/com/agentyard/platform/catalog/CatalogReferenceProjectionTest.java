package com.agentyard.platform.catalog;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.List;

class CatalogReferenceProjectionTest {
    @Test
    void followsPinnedVersionsAndPreservesPublishedConfiguration() {
        var service = new CatalogService();
        var resource = CatalogServiceTest.model(service, "pinned");
        var template = CatalogServiceTest.template(service, resource.effectiveVersion().id(), List.of(), List.of());
        var refs = service.objectReferences("RESOURCE", resource.id()).relations();
        assertTrue(refs.stream().anyMatch(r -> r.targetId().equals(template.effectiveVersion().id())
            && r.resourceVersionId().equals(resource.effectiveVersion().id())));
        assertFalse(service.deletionPreview("RESOURCE", resource.id()).canDelete());
        assertFalse(service.deletionPreview("ROLE_TEMPLATE", template.id()).canDelete());
        assertThrows(IllegalStateException.class, () -> service.deleteResource(resource.id()));
    }

    @Test
    void rollsBackFailedInMemoryMutation() {
        var repository = new InMemoryCatalogRepository();
        assertThrows(IllegalStateException.class, () -> repository.inWriteTransaction(() -> {
            repository.upsertRoleTemplate(new CatalogDtos.RoleTemplateDto("bad", "Bad", "", "PERSON", "operator", null, null, List.of()));
            throw new IllegalStateException("rollback");
        }));
        assertEquals(0, repository.revision());
        assertTrue(repository.listRoleTemplates().isEmpty());
    }
}
