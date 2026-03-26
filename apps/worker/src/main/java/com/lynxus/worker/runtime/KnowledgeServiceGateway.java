package com.lynxus.worker.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

public interface KnowledgeServiceGateway {
    String runImportJob(String importJobId);

    String buildIndexSnapshot(String indexSnapshotId);

    @Component
    class HttpKnowledgeServiceGateway implements KnowledgeServiceGateway {
        private final RestClient restClient;

        public HttpKnowledgeServiceGateway(@Value("${lynxus.knowledge-service.base-url:http://localhost:8091}") String baseUrl) {
            this.restClient = RestClient.builder().baseUrl(baseUrl).build();
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
    }
}
