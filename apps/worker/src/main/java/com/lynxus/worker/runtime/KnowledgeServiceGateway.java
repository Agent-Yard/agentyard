package com.lynxus.worker.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.lynxus.worker.logging.WorkerLogContext;

public interface KnowledgeServiceGateway {
    String runImportJob(String importJobId);

    String buildIndexSnapshot(String indexSnapshotId);

    @Component
    class HttpKnowledgeServiceGateway implements KnowledgeServiceGateway {
        private final RestClient restClient;

        public HttpKnowledgeServiceGateway(
            @Value("${lynxus.knowledge-service.base-url:http://localhost:8091}") String baseUrl,
            @Value("${lynxus.internal-auth.token}") String internalAuthToken
        ) {
            String sanitizedToken = requireInternalAuthToken(internalAuthToken);
            this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + sanitizedToken)
                .requestInterceptor((request, body, execution) -> {
                    WorkerLogContext.outboundHeaders(null)
                        .forEach((headerName, headerValue) -> request.getHeaders().set(headerName, headerValue));
                    return execution.execute(request, body);
                })
                .build();
        }

        @Override
        public String runImportJob(String importJobId) {
            ImportJobDto response = restClient.post()
                .uri("/internal/import-jobs/{jobId}/run", importJobId)
                .retrieve()
                .body(ImportJobDto.class);
            return response == null ? "FAILED" : response.status();
        }

        @Override
        public String buildIndexSnapshot(String indexSnapshotId) {
            IndexSnapshotDto response = restClient.post()
                .uri("/internal/index-snapshots/{snapshotId}/build", indexSnapshotId)
                .retrieve()
                .body(IndexSnapshotDto.class);
            return response == null ? "FAILED" : response.status();
        }

        record ImportJobDto(String status) {
        }

        record IndexSnapshotDto(String status) {
        }

        private static String requireInternalAuthToken(String internalAuthToken) {
            if (internalAuthToken == null || internalAuthToken.isBlank()) {
                throw new IllegalStateException("lynxus.internal-auth.token must be configured");
            }
            return internalAuthToken.trim();
        }
    }
}
