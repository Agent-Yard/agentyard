package com.lynxus.platform.knowledge;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.platform.catalog.CatalogService;
import com.lynxus.platform.shared.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeController {
    private final KnowledgeService knowledgeService;
    private final CatalogService catalogService;

    public KnowledgeController(KnowledgeService knowledgeService, CatalogService catalogService) {
        this.knowledgeService = knowledgeService;
        this.catalogService = catalogService;
    }

    @GetMapping
    public ApiResponse<?> knowledgeBases() {
        return ApiResponse.ok(knowledgeService.listKnowledgeBases());
    }

    @GetMapping("/{knowledgeBaseId}")
    public ApiResponse<?> knowledgeBase(@PathVariable String knowledgeBaseId) {
        return ApiResponse.ok(knowledgeService.getKnowledgeBase(knowledgeBaseId));
    }

    @PostMapping
    public ApiResponse<?> createKnowledgeBase(@RequestBody CreateKnowledgeBaseRequest request) {
        return ApiResponse.ok(knowledgeService.createKnowledgeBase(request));
    }

    @PutMapping("/{knowledgeBaseId}")
    public ApiResponse<?> updateKnowledgeBase(@PathVariable String knowledgeBaseId, @RequestBody UpdateKnowledgeBaseRequest request) {
        return ApiResponse.ok(knowledgeService.updateKnowledgeBase(knowledgeBaseId, request));
    }

    @DeleteMapping("/{knowledgeBaseId}")
    public ApiResponse<?> deleteKnowledgeBase(@PathVariable String knowledgeBaseId) {
        return ApiResponse.ok(knowledgeService.deleteKnowledgeBase(knowledgeBaseId));
    }

    @GetMapping("/{knowledgeBaseId}/releases")
    public ApiResponse<?> knowledgeReleases(@PathVariable String knowledgeBaseId) {
        return ApiResponse.ok(knowledgeService.listKnowledgeReleases(knowledgeBaseId));
    }

    @PostMapping("/{knowledgeBaseId}/releases")
    public ApiResponse<?> createKnowledgeRelease(@PathVariable String knowledgeBaseId, @RequestBody CreateKnowledgeReleaseRequest request) {
        return ApiResponse.ok(knowledgeService.createKnowledgeRelease(knowledgeBaseId, request));
    }

    @PatchMapping("/{knowledgeBaseId}/releases/{releaseId}/publish")
    public ApiResponse<?> publishKnowledgeRelease(@PathVariable String knowledgeBaseId, @PathVariable String releaseId) {
        return ApiResponse.ok(knowledgeService.publishKnowledgeRelease(knowledgeBaseId, releaseId));
    }

    @DeleteMapping("/{knowledgeBaseId}/releases/{releaseId}")
    public ApiResponse<?> deleteKnowledgeRelease(@PathVariable String knowledgeBaseId, @PathVariable String releaseId) {
        return ApiResponse.ok(knowledgeService.deleteKnowledgeRelease(knowledgeBaseId, releaseId));
    }

    @GetMapping("/{knowledgeBaseId}/references")
    public ApiResponse<?> knowledgeReferences(@PathVariable String knowledgeBaseId) {
        return ApiResponse.ok(catalogService.listKnowledgeReferences(knowledgeBaseId));
    }

    @PostMapping("/{knowledgeBaseId}/upload-sessions")
    public ApiResponse<?> createUploadSession(@PathVariable String knowledgeBaseId) {
        return ApiResponse.ok(knowledgeService.createUploadSession(knowledgeBaseId));
    }

    @PostMapping("/{knowledgeBaseId}/upload-sessions/{uploadSessionId}/complete")
    public ApiResponse<?> completeUpload(
        @PathVariable String knowledgeBaseId,
        @PathVariable String uploadSessionId,
        @RequestParam("file") MultipartFile file
    ) throws Exception {
        return ApiResponse.ok(knowledgeService.completeUpload(
            knowledgeBaseId,
            uploadSessionId,
            file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename(),
            file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
            file.getBytes()
        ));
    }

    @PostMapping("/{knowledgeBaseId}/url-imports")
    public ApiResponse<?> importUrl(@PathVariable String knowledgeBaseId, @RequestBody CreateKnowledgeUrlImportRequest request) {
        return ApiResponse.ok(knowledgeService.importUrl(knowledgeBaseId, request));
    }

    @GetMapping("/{knowledgeBaseId}/files")
    public ApiResponse<?> files(@PathVariable String knowledgeBaseId) {
        return ApiResponse.ok(knowledgeService.listFiles(knowledgeBaseId));
    }

    @GetMapping("/{knowledgeBaseId}/import-jobs")
    public ApiResponse<?> importJobs(@PathVariable String knowledgeBaseId) {
        return ApiResponse.ok(knowledgeService.listImportJobs(knowledgeBaseId));
    }

    @GetMapping("/{knowledgeBaseId}/documents")
    public ApiResponse<?> documents(@PathVariable String knowledgeBaseId) {
        return ApiResponse.ok(knowledgeService.listDocuments(knowledgeBaseId));
    }

    @GetMapping("/{knowledgeBaseId}/snapshots")
    public ApiResponse<?> snapshots(@PathVariable String knowledgeBaseId) {
        return ApiResponse.ok(knowledgeService.listIndexSnapshots(knowledgeBaseId));
    }

    @PostMapping("/{knowledgeBaseId}/snapshots")
    public ApiResponse<?> createSnapshot(@PathVariable String knowledgeBaseId, @RequestBody CreateKnowledgeIndexSnapshotRequest request) {
        return ApiResponse.ok(knowledgeService.createIndexSnapshot(knowledgeBaseId, request));
    }
}
