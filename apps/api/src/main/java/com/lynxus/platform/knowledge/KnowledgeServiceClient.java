package com.lynxus.platform.knowledge;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.platform.shared.logging.PlatformLogContext;
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

    public KnowledgeServiceClient(
        @Value("${lynxus.knowledge-service.base-url:http://localhost:8091}") String baseUrl,
        @Value("${lynxus.internal-auth.token}") String internalAuthToken
    ) {
        String sanitizedToken = requireInternalAuthToken(internalAuthToken);
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + sanitizedToken)
            .requestInterceptor((request, body, execution) -> {
                PlatformLogContext.outboundHeaders(null, null, null, null)
                    .forEach((headerName, headerValue) -> request.getHeaders().set(headerName, headerValue));
                return execution.execute(request, body);
            })
            .build();
    }

    private static String requireInternalAuthToken(String internalAuthToken) {
        if (internalAuthToken == null || internalAuthToken.isBlank()) {
            throw new IllegalStateException("lynxus.internal-auth.token must be configured");
        }
        return internalAuthToken.trim();
    }

    public KnowledgeUploadSessionDto createUploadSession(String knowledgeBaseId) {
        return restClient.post()
            .uri("/internal/upload-sessions")
            .body(new CreateKnowledgeUploadSessionRequest(knowledgeBaseId))
            .retrieve()
            .body(KnowledgeUploadSessionDto.class);
    }

    public KnowledgeUploadCompletionDto completeUpload(String knowledgeBaseId, String uploadSessionId, String fileName, String contentType, byte[] payload) {
        return restClient.post()
            .uri("/internal/uploads")
            .body(new InternalCompleteUploadRequest(
                knowledgeBaseId,
                uploadSessionId,
                fileName,
                contentType,
                Base64.getEncoder().encodeToString(payload)
            ))
            .retrieve()
            .body(KnowledgeUploadCompletionDto.class);
    }

    public KnowledgeUploadCompletionDto importUrl(String knowledgeBaseId, String url, String title) {
        return restClient.post()
            .uri("/internal/url-imports")
            .body(new CreateKnowledgeUrlImportInternalRequest(knowledgeBaseId, url, title))
            .retrieve()
            .body(KnowledgeUploadCompletionDto.class);
    }

    public List<KnowledgeFileDto> listFiles(String knowledgeBaseId) {
        return restClient.get()
            .uri("/internal/knowledge-bases/{knowledgeBaseId}/files", knowledgeBaseId)
            .retrieve()
            .body(KNOWLEDGE_FILE_LIST);
    }

    public List<KnowledgeImportJobDto> listImportJobs(String knowledgeBaseId) {
        return restClient.get()
            .uri("/internal/knowledge-bases/{knowledgeBaseId}/import-jobs", knowledgeBaseId)
            .retrieve()
            .body(KNOWLEDGE_IMPORT_JOB_LIST);
    }

    public KnowledgeImportJobDto retryImportJob(String jobId) {
        return restClient.post()
            .uri("/internal/import-jobs/{jobId}/retry", jobId)
            .retrieve()
            .body(KnowledgeImportJobDto.class);
    }

    public List<KnowledgeDocumentDto> listDocuments(String knowledgeBaseId) {
        return restClient.get()
            .uri("/internal/knowledge-bases/{knowledgeBaseId}/documents", knowledgeBaseId)
            .retrieve()
            .body(KNOWLEDGE_DOCUMENT_LIST);
    }

    public KnowledgeIndexSnapshotDto createIndexSnapshot(String knowledgeBaseId, List<String> documentIds) {
        return restClient.post()
            .uri("/internal/knowledge-bases/{knowledgeBaseId}/index-snapshots", knowledgeBaseId)
            .body(new CreateKnowledgeIndexSnapshotRequest(documentIds))
            .retrieve()
            .body(KnowledgeIndexSnapshotDto.class);
    }

    public KnowledgeIndexSnapshotDto retryIndexSnapshot(String snapshotId) {
        return restClient.post()
            .uri("/internal/index-snapshots/{snapshotId}/retry", snapshotId)
            .retrieve()
            .body(KnowledgeIndexSnapshotDto.class);
    }

    public List<KnowledgeIndexSnapshotDto> listIndexSnapshots(String knowledgeBaseId) {
        return restClient.get()
            .uri("/internal/knowledge-bases/{knowledgeBaseId}/index-snapshots", knowledgeBaseId)
            .retrieve()
            .body(KNOWLEDGE_SNAPSHOT_LIST);
    }

    public KnowledgeIndexSnapshotDto getIndexSnapshot(String snapshotId) {
        return restClient.get()
            .uri("/internal/index-snapshots/{snapshotId}", snapshotId)
            .retrieve()
            .body(KnowledgeIndexSnapshotDto.class);
    }

    public KnowledgeRetrievalPreviewResultDto previewRetrieval(KnowledgeRetrievalPreviewRequest request) {
        return restClient.post()
            .uri("/internal/retrieve")
            .body(new InternalRetrievePreviewRequest(
                request.snapshotId(),
                request.query(),
                request.topK(),
                request.minScore(),
                request.retrievalMode()
            ))
            .retrieve()
            .body(KnowledgeRetrievalPreviewResultDto.class);
    }

    record InternalCompleteUploadRequest(
        String knowledgeBaseId,
        String uploadSessionId,
        String fileName,
        String contentType,
        String contentBase64
    ) {
    }

    record CreateKnowledgeUrlImportInternalRequest(
        String knowledgeBaseId,
        String url,
        String title
    ) {
    }

    record InternalRetrievePreviewRequest(
        String indexSnapshotId,
        String query,
        Integer topK,
        Double minScore,
        String retrievalMode
    ) {
    }
}
