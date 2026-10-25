package com.agentyard.platform.catalog;

import static com.agentyard.platform.catalog.CatalogDtos.*;
import com.agentyard.platform.auth.RequireGovernanceWrite;
import com.agentyard.platform.auth.RequireGovernanceAccess;
import org.springframework.security.access.prepost.PreAuthorize;
import com.agentyard.platform.shared.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequireGovernanceAccess
@RequestMapping("/api")
public class CatalogController {
    private final CatalogService catalogService;
    private final CatalogAccess access;
    public CatalogController(CatalogService catalogService, CatalogAccess access) { this.catalogService = catalogService; this.access = access; }
    @GetMapping("/catalog/summary")
    public ApiResponse<?> summary() { return ApiResponse.ok(access.summary()); }
    @GetMapping("/catalog/references/{type}/{id}")
    public ApiResponse<?> references(@PathVariable String type, @PathVariable String id) { return ApiResponse.ok(access.references(type, id)); }
    @GetMapping("/catalog/deletion-preview/{type}/{id}")
    public ApiResponse<?> deletionPreview(@PathVariable String type, @PathVariable String id) { return ApiResponse.ok(access.deletionPreview(type, id)); }
    @GetMapping("/role-templates")
    public ApiResponse<?> listRoleTemplates() { return ApiResponse.ok(access.visible(catalogService.listRoleTemplates())); }
    @GetMapping("/role-templates/{id}")
    public ApiResponse<?> getRoleTemplate(@PathVariable String id) { return ApiResponse.ok(access.read(catalogService.getRoleTemplate(id))); }
    @PostMapping("/role-templates") @RequireGovernanceWrite
    public ApiResponse<?> createRoleTemplate(@RequestBody CreateRoleTemplateRequest request) { access.write(request); if (request.initialVersion() != null) access.roleReferences(request.initialVersion().configuration()); return ApiResponse.ok(catalogService.createRoleTemplate(request)); }
    @PutMapping("/role-templates/{id}") @RequireGovernanceWrite
    public ApiResponse<?> updateRoleTemplate(@PathVariable String id, @RequestBody UpdateRoleTemplateRequest request) { access.write(catalogService.getRoleTemplate(id)); access.write(request); return ApiResponse.ok(catalogService.updateRoleTemplate(id, request)); }
    @DeleteMapping("/role-templates/{id}") @RequireGovernanceWrite
    public ApiResponse<?> deleteRoleTemplate(@PathVariable String id) { access.write(catalogService.getRoleTemplate(id)); return ApiResponse.ok(catalogService.deleteRoleTemplate(id)); }
    @PostMapping("/role-templates/{id}/versions") @RequireGovernanceWrite
    public ApiResponse<?> createRoleTemplateVersion(@PathVariable String id, @RequestBody CreateRoleTemplateVersionRequest request) { access.write(catalogService.getRoleTemplate(id)); access.roleReferences(request.configuration()); return ApiResponse.ok(catalogService.createRoleTemplateVersion(id, request)); }
    @PostMapping("/role-templates/{id}/versions/{versionId}/publish") @RequireGovernanceWrite
    public ApiResponse<?> publishRoleTemplateVersion(@PathVariable String id, @PathVariable String versionId) { access.write(catalogService.getRoleTemplate(id)); access.roleReferences(catalogService.getRoleTemplateVersion(versionId).configuration()); return ApiResponse.ok(catalogService.publishRoleTemplateVersion(id, versionId)); }
    @GetMapping("/entries")
    public ApiResponse<?> listEntries() { return ApiResponse.ok(access.visible(catalogService.listEntries())); }
    @GetMapping("/entries/{id}")
    public ApiResponse<?> getEntry(@PathVariable String id) { return ApiResponse.ok(access.read(catalogService.getEntry(id))); }
    @PostMapping("/entries") @PreAuthorize("@catalogAccess.isPlatformAdmin()")
    public ApiResponse<?> createEntry(@RequestBody EntryRequest request) { access.write(request); return ApiResponse.ok(catalogService.createEntry(request)); }
    @PutMapping("/entries/{id}") @PreAuthorize("@catalogAccess.isPlatformAdmin()")
    public ApiResponse<?> updateEntry(@PathVariable String id, @RequestBody EntryRequest request) { access.write(catalogService.getEntry(id)); access.write(request); return ApiResponse.ok(catalogService.updateEntry(id, request)); }
    @DeleteMapping("/entries/{id}") @PreAuthorize("@catalogAccess.isPlatformAdmin()")
    public ApiResponse<?> deleteEntry(@PathVariable String id) { access.write(catalogService.getEntry(id)); return ApiResponse.ok(catalogService.deleteEntry(id)); }
    @GetMapping("/playbooks")
    public ApiResponse<?> listPlaybooks() { return ApiResponse.ok(access.visible(catalogService.listPlaybooks())); }
    @GetMapping("/playbooks/{id}")
    public ApiResponse<?> getPlaybook(@PathVariable String id) { return ApiResponse.ok(access.read(catalogService.getPlaybook(id))); }
    @PostMapping("/playbooks") @RequireGovernanceWrite
    public ApiResponse<?> createPlaybook(@RequestBody CreatePlaybookRequest request) { access.write(request); if (request.initialVersion() != null) access.playbookReferences(request.initialVersion().definition()); return ApiResponse.ok(catalogService.createPlaybook(request)); }
    @PutMapping("/playbooks/{id}") @RequireGovernanceWrite
    public ApiResponse<?> updatePlaybook(@PathVariable String id, @RequestBody UpdatePlaybookRequest request) { access.write(catalogService.getPlaybook(id)); access.write(request); return ApiResponse.ok(catalogService.updatePlaybook(id, request)); }
    @DeleteMapping("/playbooks/{id}") @RequireGovernanceWrite
    public ApiResponse<?> deletePlaybook(@PathVariable String id) { access.write(catalogService.getPlaybook(id)); return ApiResponse.ok(catalogService.deletePlaybook(id)); }
    @PostMapping("/playbooks/{id}/versions") @RequireGovernanceWrite
    public ApiResponse<?> createPlaybookVersion(@PathVariable String id, @RequestBody CreatePlaybookVersionRequest request) { access.write(catalogService.getPlaybook(id)); access.playbookReferences(request.definition()); return ApiResponse.ok(catalogService.createPlaybookVersion(id, request)); }
    @PostMapping("/playbooks/{id}/versions/{versionId}/publish") @RequireGovernanceWrite
    public ApiResponse<?> publishPlaybookVersion(@PathVariable String id, @PathVariable String versionId) { access.write(catalogService.getPlaybook(id)); access.playbookReferences(catalogService.getPlaybookVersion(versionId).definition()); return ApiResponse.ok(catalogService.publishPlaybookVersion(id, versionId)); }
    @GetMapping("/resources")
    public ApiResponse<?> resources() {
        return ApiResponse.ok(access.visible(catalogService.listResources()));
    }

    @GetMapping("/resources/{resourceId}/versions")
    public ApiResponse<?> resourceVersions(@PathVariable String resourceId) {
        access.read(catalogService.getResource(resourceId));
        return ApiResponse.ok(catalogService.listResourceVersions(resourceId));
    }

    @PutMapping("/resources/{resourceId}")
    @RequireGovernanceWrite
    public ApiResponse<?> updateResource(@PathVariable String resourceId, @RequestBody UpdateResourceRequest request) {
        access.write(catalogService.getResource(resourceId));
        access.write(request);
        return ApiResponse.ok(catalogService.updateResource(resourceId, request));
    }

    @PostMapping("/resources/{resourceId}/versions")
    @RequireGovernanceWrite
    public ApiResponse<?> createResourceVersion(@PathVariable String resourceId, @RequestBody CreateResourceVersionRequest request) {
        access.write(catalogService.getResource(resourceId));
        return ApiResponse.ok(catalogService.createResourceVersion(resourceId, request));
    }

    @PutMapping("/resources/{resourceId}/versions/{versionId}")
    @RequireGovernanceWrite
    public ApiResponse<?> updateResourceVersion(
        @PathVariable String resourceId,
        @PathVariable String versionId,
        @RequestBody UpdateResourceVersionRequest request
    ) {
        access.write(catalogService.getResource(resourceId));
        return ApiResponse.ok(catalogService.updateResourceVersion(resourceId, versionId, request));
    }

    @PatchMapping("/resources/{resourceId}/versions/{versionId}/publish")
    @RequireGovernanceWrite
    public ApiResponse<?> publishResourceVersion(@PathVariable String resourceId, @PathVariable String versionId) {
        access.write(catalogService.getResource(resourceId));
        return ApiResponse.ok(catalogService.publishResourceVersion(resourceId, versionId));
    }

    @DeleteMapping("/resources/{resourceId}/versions/{versionId}")
    @RequireGovernanceWrite
    public ApiResponse<?> deleteResourceVersion(@PathVariable String resourceId, @PathVariable String versionId) {
        access.write(catalogService.getResource(resourceId));
        return ApiResponse.ok(catalogService.deleteResourceVersion(resourceId, versionId));
    }

    @GetMapping("/resource-center")
    public ApiResponse<?> resourceCenter() {
        return ApiResponse.ok(access.summary().resourceCenter());
    }

    @GetMapping("/resource-blueprints")
    public ApiResponse<?> resourceBlueprints() {
        return ApiResponse.ok(catalogService.resourceBlueprints());
    }

    @PostMapping("/resources")
    @RequireGovernanceWrite
    public ApiResponse<?> createResource(@RequestBody CreateResourceRequest request) {
        access.write(request);
        return ApiResponse.ok(catalogService.createResource(request));
    }

    @DeleteMapping("/resources/{resourceId}")
    @RequireGovernanceWrite
    public ApiResponse<?> deleteResource(@PathVariable String resourceId) {
        access.write(catalogService.getResource(resourceId));
        return ApiResponse.ok(catalogService.deleteResource(resourceId));
    }

}
