package com.lynxus.platform.catalog;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class KnowledgeServiceClient {
    private static final ParameterizedTypeReference<List<KnowledgeFileDto>> KNOWLEDGE_FILE_LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<KnowledgeImportJobDto>> KNOWLEDGE_IMPORT_JOB_LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<KnowledgeDocumentDto>> KNOWLEDGE_DOCUMENT_LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<KnowledgeIndexSnapshotDto>> KNOWLEDGE_SNAPSHOT_LIST = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public KnowledgeServiceClient(@Value("${lynxus.knowledge-service.base-url:http://localhost:8091}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public KnowledgeUploadSessionDto createUploadSession(String resourceId) {
        return restClient.post()
            .uri("/internal/upload-sessions")
            .body(new CreateKnowledgeUploadSessionRequest(resourceId))
            .retrieve()
            .body(KnowledgeUploadSessionDto.class);
    }

    public KnowledgeUploadCompletionDto completeUpload(String resourceId, String uploadSessionId, String fileName, String contentType, byte[] payload) {
        return restClient.post()
            .uri("/internal/uploads")
            .body(new InternalCompleteUploadRequest(
                resourceId,
                uploadSessionId,
                fileName,
                contentType,
                Base64.getEncoder().encodeToString(payload)
            ))
            .retrieve()
            .body(KnowledgeUploadCompletionDto.class);
    }

    public KnowledgeUploadCompletionDto importUrl(String resourceId, String url, String title) {
        return restClient.post()
            .uri("/internal/url-imports")
            .body(new CreateKnowledgeUrlImportInternalRequest(resourceId, url, title))
            .retrieve()
            .body(KnowledgeUploadCompletionDto.class);
    }

    public List<KnowledgeFileDto> listFiles(String resourceId) {
        return restClient.get()
            .uri("/internal/resources/{resourceId}/files", resourceId)
            .retrieve()
            .body(KNOWLEDGE_FILE_LIST);
    }

    public List<KnowledgeImportJobDto> listImportJobs(String resourceId) {
        return restClient.get()
            .uri("/internal/resources/{resourceId}/import-jobs", resourceId)
            .retrieve()
            .body(KNOWLEDGE_IMPORT_JOB_LIST);
    }

    public List<KnowledgeDocumentDto> listDocuments(String resourceId) {
        return restClient.get()
            .uri("/internal/resources/{resourceId}/documents", resourceId)
            .retrieve()
            .body(KNOWLEDGE_DOCUMENT_LIST);
    }

    public KnowledgeIndexSnapshotDto createIndexSnapshot(String resourceId, List<String> documentIds) {
        return restClient.post()
            .uri("/internal/resources/{resourceId}/index-snapshots", resourceId)
            .body(new CreateKnowledgeIndexSnapshotRequest(documentIds))
            .retrieve()
            .body(KnowledgeIndexSnapshotDto.class);
    }

    public List<KnowledgeIndexSnapshotDto> listIndexSnapshots(String resourceId) {
        return restClient.get()
            .uri("/internal/resources/{resourceId}/index-snapshots", resourceId)
            .retrieve()
            .body(KNOWLEDGE_SNAPSHOT_LIST);
    }

    public KnowledgeIndexSnapshotDto getIndexSnapshot(String snapshotId) {
        return restClient.get()
            .uri("/internal/index-snapshots/{snapshotId}", snapshotId)
            .retrieve()
            .body(KnowledgeIndexSnapshotDto.class);
    }

    record InternalCompleteUploadRequest(
        String resourceId,
        String uploadSessionId,
        String fileName,
        String contentType,
        String contentBase64
    ) {
    }

    record CreateKnowledgeUrlImportInternalRequest(
        String resourceId,
        String url,
        String title
    ) {
    }
}
