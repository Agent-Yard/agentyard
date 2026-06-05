package com.agentyard.platform.catalog;

import static com.agentyard.platform.catalog.CatalogDtos.*;

import com.agentyard.platform.auth.RequireGovernanceWrite;
import com.agentyard.platform.shared.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CatalogController {
    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/catalog/summary")
    public ApiResponse<?> summary() {
        return ApiResponse.ok(catalogService.summary());
    }

    @GetMapping("/catalog/references/{objectType}/{objectId}")
    public ApiResponse<?> objectReferences(@PathVariable String objectType, @PathVariable String objectId) {
        return ApiResponse.ok(catalogService.objectReferences(objectType, objectId));
    }

    @GetMapping("/catalog/deletion-preview/{objectType}/{objectId}")
    public ApiResponse<?> deletionPreview(@PathVariable String objectType, @PathVariable String objectId) {
        return ApiResponse.ok(catalogService.deletionPreview(objectType, objectId));
    }

    @GetMapping("/domains")
    public ApiResponse<?> domains() {
        return ApiResponse.ok(catalogService.listDomains());
    }

    @GetMapping("/domains/{domainId}")
    public ApiResponse<?> domain(@PathVariable String domainId) {
        return ApiResponse.ok(catalogService.getDomain(domainId));
    }

    @PostMapping("/domains")
    @RequireGovernanceWrite
    public ApiResponse<?> createDomain(@RequestBody CreateDomainRequest request) {
        return ApiResponse.ok(catalogService.createDomain(request));
    }

    @PutMapping("/domains/{domainId}")
    @RequireGovernanceWrite
    public ApiResponse<?> updateDomain(@PathVariable String domainId, @RequestBody UpdateDomainRequest request) {
        return ApiResponse.ok(catalogService.updateDomain(domainId, request));
    }

    @DeleteMapping("/domains/{domainId}")
    @RequireGovernanceWrite
    public ApiResponse<?> deleteDomain(@PathVariable String domainId) {
        return ApiResponse.ok(catalogService.deleteDomain(domainId));
    }

    @GetMapping("/scenarios")
    public ApiResponse<?> scenarios() {
        return ApiResponse.ok(catalogService.listScenarios());
    }

    @GetMapping("/scenarios/{scenarioId}")
    public ApiResponse<?> scenario(@PathVariable String scenarioId) {
        return ApiResponse.ok(catalogService.getScenario(scenarioId));
    }

    @PostMapping("/scenarios")
    @RequireGovernanceWrite
    public ApiResponse<?> createScenario(@RequestBody CreateScenarioRequest request) {
        return ApiResponse.ok(catalogService.createScenario(request));
    }

    @PutMapping("/scenarios/{scenarioId}")
    @RequireGovernanceWrite
    public ApiResponse<?> updateScenario(@PathVariable String scenarioId, @RequestBody UpdateScenarioRequest request) {
        return ApiResponse.ok(catalogService.updateScenario(scenarioId, request));
    }

    @DeleteMapping("/scenarios/{scenarioId}")
    @RequireGovernanceWrite
    public ApiResponse<?> deleteScenario(@PathVariable String scenarioId) {
        return ApiResponse.ok(catalogService.deleteScenario(scenarioId));
    }

    @GetMapping("/assistants")
    public ApiResponse<?> assistants() {
        return ApiResponse.ok(catalogService.listAssistants());
    }

    @PostMapping("/assistants")
    @RequireGovernanceWrite
    public ApiResponse<?> createAssistant(@RequestBody CreateAssistantRequest request) {
        return ApiResponse.ok(catalogService.createAssistant(request));
    }

    @PutMapping("/assistants/{assistantId}")
    @RequireGovernanceWrite
    public ApiResponse<?> updateAssistant(@PathVariable String assistantId, @RequestBody UpdateAssistantRequest request) {
        return ApiResponse.ok(catalogService.updateAssistant(assistantId, request));
    }

    @DeleteMapping("/assistants/{assistantId}")
    @RequireGovernanceWrite
    public ApiResponse<?> deleteAssistant(@PathVariable String assistantId) {
        return ApiResponse.ok(catalogService.deleteAssistant(assistantId));
    }

    @GetMapping("/agents")
    public ApiResponse<?> agents() {
        return ApiResponse.ok(catalogService.listAgents());
    }

    @GetMapping("/agents/{agentId}")
    public ApiResponse<?> agent(@PathVariable String agentId) {
        return ApiResponse.ok(catalogService.getAgent(agentId));
    }

    @PostMapping("/agents")
    @RequireGovernanceWrite
    public ApiResponse<?> createAgent(@RequestBody CreateAgentRequest request) {
        return ApiResponse.ok(catalogService.createAgent(request));
    }

    @PutMapping("/agents/{agentId}")
    @RequireGovernanceWrite
    public ApiResponse<?> updateAgent(@PathVariable String agentId, @RequestBody UpdateAgentRequest request) {
        return ApiResponse.ok(catalogService.updateAgent(agentId, request));
    }

    @DeleteMapping("/agents/{agentId}")
    @RequireGovernanceWrite
    public ApiResponse<?> deleteAgent(@PathVariable String agentId) {
        return ApiResponse.ok(catalogService.deleteAgent(agentId));
    }

    @GetMapping("/playbooks")
    public ApiResponse<?> playbooks() {
        return ApiResponse.ok(catalogService.listPlaybooks());
    }

    @GetMapping("/playbooks/{playbookId}")
    public ApiResponse<?> playbook(@PathVariable String playbookId) {
        return ApiResponse.ok(catalogService.getPlaybook(playbookId));
    }

    @PostMapping("/playbooks")
    @RequireGovernanceWrite
    public ApiResponse<?> createPlaybook(@RequestBody CreatePlaybookRequest request) {
        return ApiResponse.ok(catalogService.createPlaybook(request));
    }

    @PutMapping("/playbooks/{playbookId}")
    @RequireGovernanceWrite
    public ApiResponse<?> updatePlaybook(@PathVariable String playbookId, @RequestBody UpdatePlaybookRequest request) {
        return ApiResponse.ok(catalogService.updatePlaybook(playbookId, request));
    }

    @DeleteMapping("/playbooks/{playbookId}")
    @RequireGovernanceWrite
    public ApiResponse<?> deletePlaybook(@PathVariable String playbookId) {
        return ApiResponse.ok(catalogService.deletePlaybook(playbookId));
    }

    @GetMapping("/resources")
    public ApiResponse<?> resources() {
        return ApiResponse.ok(catalogService.listResources());
    }

    @GetMapping("/resources/{resourceId}/versions")
    public ApiResponse<?> resourceVersions(@PathVariable String resourceId) {
        return ApiResponse.ok(catalogService.listResourceVersions(resourceId));
    }

    @PutMapping("/resources/{resourceId}")
    @RequireGovernanceWrite
    public ApiResponse<?> updateResource(@PathVariable String resourceId, @RequestBody UpdateResourceRequest request) {
        return ApiResponse.ok(catalogService.updateResource(resourceId, request));
    }

    @PostMapping("/resources/{resourceId}/versions")
    @RequireGovernanceWrite
    public ApiResponse<?> createResourceVersion(@PathVariable String resourceId, @RequestBody CreateResourceVersionRequest request) {
        return ApiResponse.ok(catalogService.createResourceVersion(resourceId, request));
    }

    @PutMapping("/resources/{resourceId}/versions/{versionId}")
    @RequireGovernanceWrite
    public ApiResponse<?> updateResourceVersion(
        @PathVariable String resourceId,
        @PathVariable String versionId,
        @RequestBody UpdateResourceVersionRequest request
    ) {
        return ApiResponse.ok(catalogService.updateResourceVersion(resourceId, versionId, request));
    }

    @PatchMapping("/resources/{resourceId}/versions/{versionId}/publish")
    @RequireGovernanceWrite
    public ApiResponse<?> publishResourceVersion(@PathVariable String resourceId, @PathVariable String versionId) {
        return ApiResponse.ok(catalogService.publishResourceVersion(resourceId, versionId));
    }

    @DeleteMapping("/resources/{resourceId}/versions/{versionId}")
    @RequireGovernanceWrite
    public ApiResponse<?> deleteResourceVersion(@PathVariable String resourceId, @PathVariable String versionId) {
        return ApiResponse.ok(catalogService.deleteResourceVersion(resourceId, versionId));
    }

    @GetMapping("/resource-center")
    public ApiResponse<?> resourceCenter() {
        return ApiResponse.ok(catalogService.resourceCenter());
    }

    @GetMapping("/resource-blueprints")
    public ApiResponse<?> resourceBlueprints() {
        return ApiResponse.ok(catalogService.resourceBlueprints());
    }

    @PostMapping("/resources")
    @RequireGovernanceWrite
    public ApiResponse<?> createResource(@RequestBody CreateResourceRequest request) {
        return ApiResponse.ok(catalogService.createResource(request));
    }

    @DeleteMapping("/resources/{resourceId}")
    @RequireGovernanceWrite
    public ApiResponse<?> deleteResource(@PathVariable String resourceId) {
        return ApiResponse.ok(catalogService.deleteResource(resourceId));
    }

}
