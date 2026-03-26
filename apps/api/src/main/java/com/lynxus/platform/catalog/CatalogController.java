package com.lynxus.platform.catalog;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.platform.shared.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

    @GetMapping("/domains")
    public ApiResponse<?> domains() {
        return ApiResponse.ok(catalogService.listDomains());
    }

    @GetMapping("/domains/{domainId}")
    public ApiResponse<?> domain(@PathVariable String domainId) {
        return ApiResponse.ok(catalogService.getDomain(domainId));
    }

    @PostMapping("/domains")
    public ApiResponse<?> createDomain(@RequestBody CreateDomainRequest request) {
        return ApiResponse.ok(catalogService.createDomain(request));
    }

    @PutMapping("/domains/{domainId}")
    public ApiResponse<?> updateDomain(@PathVariable String domainId, @RequestBody UpdateDomainRequest request) {
        return ApiResponse.ok(catalogService.updateDomain(domainId, request));
    }

    @DeleteMapping("/domains/{domainId}")
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
    public ApiResponse<?> createScenario(@RequestBody CreateScenarioRequest request) {
        return ApiResponse.ok(catalogService.createScenario(request));
    }

    @PutMapping("/scenarios/{scenarioId}")
    public ApiResponse<?> updateScenario(@PathVariable String scenarioId, @RequestBody UpdateScenarioRequest request) {
        return ApiResponse.ok(catalogService.updateScenario(scenarioId, request));
    }

    @DeleteMapping("/scenarios/{scenarioId}")
    public ApiResponse<?> deleteScenario(@PathVariable String scenarioId) {
        return ApiResponse.ok(catalogService.deleteScenario(scenarioId));
    }

    @GetMapping("/assistants")
    public ApiResponse<?> assistants() {
        return ApiResponse.ok(catalogService.listAssistants());
    }

    @PostMapping("/assistants")
    public ApiResponse<?> createAssistant(@RequestBody CreateAssistantRequest request) {
        return ApiResponse.ok(catalogService.createAssistant(request));
    }

    @PutMapping("/assistants/{assistantId}")
    public ApiResponse<?> updateAssistant(@PathVariable String assistantId, @RequestBody UpdateAssistantRequest request) {
        return ApiResponse.ok(catalogService.updateAssistant(assistantId, request));
    }

    @DeleteMapping("/assistants/{assistantId}")
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
    public ApiResponse<?> createAgent(@RequestBody CreateAgentRequest request) {
        return ApiResponse.ok(catalogService.createAgent(request));
    }

    @PutMapping("/agents/{agentId}")
    public ApiResponse<?> updateAgent(@PathVariable String agentId, @RequestBody UpdateAgentRequest request) {
        return ApiResponse.ok(catalogService.updateAgent(agentId, request));
    }

    @DeleteMapping("/agents/{agentId}")
    public ApiResponse<?> deleteAgent(@PathVariable String agentId) {
        return ApiResponse.ok(catalogService.deleteAgent(agentId));
    }

    @GetMapping("/resources")
    public ApiResponse<?> resources() {
        return ApiResponse.ok(catalogService.listResources());
    }

    @GetMapping("/resources/{resourceId}/versions")
    public ApiResponse<?> resourceVersions(@PathVariable String resourceId) {
        return ApiResponse.ok(catalogService.listResourceVersions(resourceId));
    }

    @PostMapping("/resources/{resourceId}/versions")
    public ApiResponse<?> createResourceVersion(@PathVariable String resourceId, @RequestBody CreateResourceVersionRequest request) {
        return ApiResponse.ok(catalogService.createResourceVersion(resourceId, request));
    }

    @PatchMapping("/resources/{resourceId}/versions/{versionId}/publish")
    public ApiResponse<?> publishResourceVersion(@PathVariable String resourceId, @PathVariable String versionId) {
        return ApiResponse.ok(catalogService.publishResourceVersion(resourceId, versionId));
    }

    @DeleteMapping("/resources/{resourceId}/versions/{versionId}")
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

    @GetMapping("/orchestrations")
    public ApiResponse<?> orchestrations() {
        return ApiResponse.ok(catalogService.listOrchestrations());
    }

    @GetMapping("/orchestrations/{assistantId}")
    public ApiResponse<?> orchestration(@PathVariable String assistantId) {
        return ApiResponse.ok(catalogService.getOrchestration(assistantId));
    }

    @PutMapping("/orchestrations/{assistantId}")
    public ApiResponse<?> saveOrchestration(@PathVariable String assistantId, @RequestBody UpdateOrchestrationRequest request) {
        return ApiResponse.ok(catalogService.saveOrchestration(assistantId, request));
    }

    @PostMapping("/resources")
    public ApiResponse<?> createResource(@RequestBody CreateResourceRequest request) {
        return ApiResponse.ok(catalogService.createResource(request));
    }

    @DeleteMapping("/resources/{resourceId}")
    public ApiResponse<?> deleteResource(@PathVariable String resourceId) {
        return ApiResponse.ok(catalogService.deleteResource(resourceId));
    }

    @PostMapping("/resources/{resourceId}/knowledge/upload-sessions")
    public ApiResponse<?> createKnowledgeUploadSession(@PathVariable String resourceId) {
        return ApiResponse.ok(catalogService.createKnowledgeUploadSession(resourceId));
    }

    @PostMapping("/resources/{resourceId}/knowledge/upload-sessions/{uploadSessionId}/complete")
    public ApiResponse<?> completeKnowledgeUpload(
        @PathVariable String resourceId,
        @PathVariable String uploadSessionId,
        @RequestParam("file") MultipartFile file
    ) throws Exception {
        return ApiResponse.ok(catalogService.completeKnowledgeUpload(
            resourceId,
            uploadSessionId,
            file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename(),
            file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
            file.getBytes()
        ));
    }

    @PostMapping("/resources/{resourceId}/knowledge/url-imports")
    public ApiResponse<?> importKnowledgeUrl(
        @PathVariable String resourceId,
        @RequestBody CreateKnowledgeUrlImportRequest request
    ) {
        return ApiResponse.ok(catalogService.importKnowledgeUrl(resourceId, request));
    }

    @GetMapping("/resources/{resourceId}/knowledge/files")
    public ApiResponse<?> knowledgeFiles(@PathVariable String resourceId) {
        return ApiResponse.ok(catalogService.listKnowledgeFiles(resourceId));
    }

    @GetMapping("/resources/{resourceId}/knowledge/import-jobs")
    public ApiResponse<?> knowledgeImportJobs(@PathVariable String resourceId) {
        return ApiResponse.ok(catalogService.listKnowledgeImportJobs(resourceId));
    }

    @GetMapping("/resources/{resourceId}/knowledge/documents")
    public ApiResponse<?> knowledgeDocuments(@PathVariable String resourceId) {
        return ApiResponse.ok(catalogService.listKnowledgeDocuments(resourceId));
    }

    @PostMapping("/resources/{resourceId}/knowledge/index-snapshots")
    public ApiResponse<?> createKnowledgeIndexSnapshot(
        @PathVariable String resourceId,
        @RequestBody CreateKnowledgeIndexSnapshotRequest request
    ) {
        return ApiResponse.ok(catalogService.createKnowledgeIndexSnapshot(resourceId, request));
    }

    @GetMapping("/resources/{resourceId}/knowledge/index-snapshots")
    public ApiResponse<?> knowledgeIndexSnapshots(@PathVariable String resourceId) {
        return ApiResponse.ok(catalogService.listKnowledgeIndexSnapshots(resourceId));
    }

}
