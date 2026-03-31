package com.lynxus.platform.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.platform.catalog.CatalogDtos;
import com.lynxus.platform.catalog.CatalogService;
import com.lynxus.platform.catalog.InMemoryCatalogRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeServiceTest {
    @Test
    void shouldRetryFailedImportJobAndTriggerWorkflow() {
        InMemoryCatalogRepository catalogRepository = new InMemoryCatalogRepository();
        CatalogDtos.BusinessDomainDto domain = seedDomain(catalogRepository);
        TrackingKnowledgeWorkflowGateway workflowGateway = new TrackingKnowledgeWorkflowGateway();
        StubKnowledgeServiceClient client = new StubKnowledgeServiceClient();
        KnowledgeService knowledgeService = new KnowledgeService(
            new InMemoryKnowledgeRepository(),
            catalogRepository,
            client,
            workflowGateway
        );
        CatalogDtos.KnowledgeBaseDto knowledgeBase = knowledgeService.createKnowledgeBase(
            new CatalogDtos.CreateKnowledgeBaseRequest(
                domain.id(),
                "客服知识库",
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "FAQ",
                "知识运营",
                List.of("FAQ")
            )
        );
        Instant now = Instant.parse("2026-03-31T00:00:00Z");
        client.file = new CatalogDtos.KnowledgeFileDto(
            "file-1",
            knowledgeBase.id(),
            "upload-session-1",
            "FILE_UPLOAD",
            "upload://knowledge/guide.md",
            "guide.md",
            "text/markdown",
            128,
            "FAILED",
            "document parsing failed",
            now,
            now
        );
        client.retryImportJobResponse = new CatalogDtos.KnowledgeImportJobDto(
            "job-2",
            knowledgeBase.id(),
            "file-1",
            "FILE_UPLOAD",
            "upload://knowledge/guide.md",
            "guide.md",
            "QUEUED",
            "QUEUED",
            0,
            1,
            false,
            null,
            null,
            now,
            now,
            null
        );

        CatalogDtos.KnowledgeUploadCompletionDto retried = knowledgeService.retryImportJob(knowledgeBase.id(), "job-1");

        assertEquals("job-2", retried.importJob().id());
        assertEquals("file-1", retried.file().id());
        assertEquals(List.of("job-2"), workflowGateway.importJobIds);
    }

    @Test
    void shouldPreviewRetrievalAgainstReadySnapshot() {
        InMemoryCatalogRepository catalogRepository = new InMemoryCatalogRepository();
        CatalogDtos.BusinessDomainDto domain = seedDomain(catalogRepository);
        StubKnowledgeServiceClient client = new StubKnowledgeServiceClient();
        KnowledgeService knowledgeService = new KnowledgeService(
            new InMemoryKnowledgeRepository(),
            catalogRepository,
            client,
            new TrackingKnowledgeWorkflowGateway()
        );
        CatalogDtos.KnowledgeBaseDto knowledgeBase = knowledgeService.createKnowledgeBase(
            new CatalogDtos.CreateKnowledgeBaseRequest(
                domain.id(),
                "客服知识库",
                ShareScope.DOMAIN_SHARED,
                "DOMAIN",
                domain.id(),
                "FAQ",
                "知识运营",
                List.of("FAQ")
            )
        );
        Instant now = Instant.parse("2026-03-31T00:00:00Z");
        client.snapshot = new CatalogDtos.KnowledgeIndexSnapshotDto(
            "snapshot-1",
            knowledgeBase.id(),
            "OPENSEARCH",
            "HYBRID",
            "READY",
            "READY",
            100,
            0,
            false,
            1,
            2,
            null,
            now,
            now,
            now,
            now
        );
        client.previewResult = new CatalogDtos.KnowledgeRetrievalPreviewResultDto(
            List.of(new CatalogDtos.KnowledgeRetrievalPreviewHitDto(
                "chunk-1",
                "doc-1",
                "支付失败",
                "https://help.example.com/payment-failed",
                "建议用户更换支付方式后重试。",
                0.93,
                null,
                "处理建议"
            )),
            false
        );

        CatalogDtos.KnowledgeRetrievalPreviewResultDto preview = knowledgeService.previewRetrieval(
            knowledgeBase.id(),
            new CatalogDtos.KnowledgeRetrievalPreviewRequest("snapshot-1", "支付失败怎么办", 5, 0.1, "HYBRID")
        );

        assertEquals(1, preview.hits().size());
        assertEquals("snapshot-1", client.previewRequest.snapshotId());
        assertEquals("支付失败怎么办", client.previewRequest.query());
        assertFalse(preview.lowConfidence());
    }

    private static CatalogDtos.BusinessDomainDto seedDomain(InMemoryCatalogRepository catalogRepository) {
        CatalogService catalogService = new CatalogService(
            catalogRepository,
            new StubKnowledgeServiceClient(),
            new TrackingKnowledgeWorkflowGateway()
        );
        return catalogService.createDomain(new CatalogDtos.CreateDomainRequest("知识运营域", "承载知识沉淀"));
    }

    private static final class StubKnowledgeServiceClient extends KnowledgeServiceClient {
        private CatalogDtos.KnowledgeFileDto file;
        private CatalogDtos.KnowledgeImportJobDto retryImportJobResponse;
        private CatalogDtos.KnowledgeIndexSnapshotDto snapshot;
        private CatalogDtos.KnowledgeRetrievalPreviewRequest previewRequest;
        private CatalogDtos.KnowledgeRetrievalPreviewResultDto previewResult;

        private StubKnowledgeServiceClient() {
            super("http://localhost:8091");
        }

        @Override
        public CatalogDtos.KnowledgeImportJobDto retryImportJob(String jobId) {
            return retryImportJobResponse;
        }

        @Override
        public List<CatalogDtos.KnowledgeFileDto> listFiles(String knowledgeBaseId) {
            return file == null ? List.of() : List.of(file);
        }

        @Override
        public CatalogDtos.KnowledgeIndexSnapshotDto getIndexSnapshot(String snapshotId) {
            return snapshot;
        }

        @Override
        public CatalogDtos.KnowledgeRetrievalPreviewResultDto previewRetrieval(CatalogDtos.KnowledgeRetrievalPreviewRequest request) {
            previewRequest = request;
            return previewResult;
        }
    }

    private static final class TrackingKnowledgeWorkflowGateway implements KnowledgeWorkflowGateway {
        private final List<String> importJobIds = new ArrayList<>();

        @Override
        public void startImport(String knowledgeBaseId, String importJobId) {
            importJobIds.add(importJobId);
        }

        @Override
        public void startIndexBuild(String knowledgeBaseId, String indexSnapshotId) {
        }
    }
}
