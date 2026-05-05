package com.lynxus.platform.knowledge;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.platform.catalog.CatalogRepository;
import com.lynxus.platform.catalog.ObjectReferenceAnalyzer;
import com.lynxus.platform.event.PlatformEventDtos.PlatformAggregateType;
import com.lynxus.platform.event.PlatformEventService;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeService {
    private final KnowledgeRepository repository;
    private final CatalogRepository catalogRepository;
    private final KnowledgeServiceClient knowledgeServiceClient;
    private final KnowledgeWorkflowGateway knowledgeWorkflowGateway;
    private final PlatformEventService platformEventService;
    private final com.lynxus.platform.shared.redis.RedisInvalidationBus invalidationBus;

    public KnowledgeService() {
        this(
            new InMemoryKnowledgeRepository(),
            new com.lynxus.platform.catalog.InMemoryCatalogRepository(),
            new KnowledgeServiceClient("http://127.0.0.1:8091", "in-memory-internal-token"),
            new NoOpKnowledgeWorkflowGateway(),
            PlatformEventService.disabled(),
            null
        );
    }

    @Autowired
    public KnowledgeService(
        KnowledgeRepository repository,
        CatalogRepository catalogRepository,
        KnowledgeServiceClient knowledgeServiceClient,
        KnowledgeWorkflowGateway knowledgeWorkflowGateway,
        PlatformEventService platformEventService,
        com.lynxus.platform.shared.redis.RedisInvalidationBus invalidationBus
    ) {
        this.repository = repository;
        this.catalogRepository = catalogRepository;
        this.knowledgeServiceClient = knowledgeServiceClient;
        this.knowledgeWorkflowGateway = knowledgeWorkflowGateway;
        this.platformEventService = platformEventService;
        this.invalidationBus = invalidationBus;
    }

    public KnowledgeService(
        KnowledgeRepository repository,
        CatalogRepository catalogRepository,
        KnowledgeServiceClient knowledgeServiceClient,
        KnowledgeWorkflowGateway knowledgeWorkflowGateway
    ) {
        this(repository, catalogRepository, knowledgeServiceClient, knowledgeWorkflowGateway, PlatformEventService.disabled(), null);
    }

    public KnowledgeRepository repository() {
        return repository;
    }

    private <T> T withReadState(Function<KnowledgeRepository, T> action) {
        return repository.inReadTransaction(() -> action.apply(repository));
    }

    private <T> T withReadState(BiFunction<KnowledgeRepository, CatalogRepository, T> action) {
        return repository.inReadTransaction(() ->
            catalogRepository.inReadTransaction(() -> action.apply(repository, catalogRepository))
        );
    }

    private <T> T withWriteState(Function<KnowledgeRepository, T> action) {
        T result = repository.inWriteTransaction(() -> action.apply(repository));
        if (invalidationBus != null) {
            invalidationBus.publishKnowledgeInvalidated(repository.revision());
        }
        return result;
    }

    public List<KnowledgeBaseDto> listKnowledgeBases() {
        return withReadState(repo -> {
        return repo.listKnowledgeBases().stream()
            .sorted(Comparator.comparing(KnowledgeBaseDto::name))
            .map(item -> toKnowledgeBaseView(repo, item))
            .toList();
        });
    }

    public List<KnowledgeBaseDto> listKnowledgeBasesByDomain(String domainId) {
        return withReadState(repo -> {
        return repo.listKnowledgeBases().stream()
            .filter(item -> item.domainId().equals(domainId))
            .sorted(Comparator.comparing(KnowledgeBaseDto::name))
            .map(item -> toKnowledgeBaseView(repo, item))
            .toList();
        });
    }

    public KnowledgeBaseDto getKnowledgeBase(String knowledgeBaseId) {
        return withReadState(repo -> {
        return toKnowledgeBaseView(repo, findKnowledgeBase(repo, knowledgeBaseId));
        });
    }

    public KnowledgeBaseDto createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        return withWriteState(repo -> {
        String domainId = requireText(request.domainId(), "knowledgeBase.domainId");
        requireDomain(domainId);
        validateKnowledgeOwner(domainId, request.ownerType(), request.ownerId());
        KnowledgeBaseDto knowledgeBase = new KnowledgeBaseDto(
            nextId("knowledge-base"),
            domainId,
            requireText(request.name(), "knowledgeBase.name"),
            request.shareScope() == null ? ShareScope.DOMAIN_SHARED : request.shareScope(),
            requireText(request.ownerType(), "knowledgeBase.ownerType"),
            requireText(request.ownerId(), "knowledgeBase.ownerId"),
            normalizeOptionalText(request.summary()),
            normalizeOptionalText(request.steward()),
            request.tags() == null ? List.of() : List.copyOf(request.tags()),
            null,
            null,
            List.of()
        );
        repo.upsertKnowledgeBase(knowledgeBase);
        recordKnowledgeEvent("KNOWLEDGE_BASE_CREATED", PlatformAggregateType.KNOWLEDGE_BASE, knowledgeBase.id(), Map.of("name", knowledgeBase.name()));
        return toKnowledgeBaseView(repo, knowledgeBase);
        });
    }

    public KnowledgeBaseDto updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
        return withWriteState(repo -> {
        KnowledgeBaseDto existing = findKnowledgeBase(repo, knowledgeBaseId);
        validateKnowledgeOwner(existing.domainId(), request.ownerType(), request.ownerId());
        KnowledgeBaseDto updated = new KnowledgeBaseDto(
            existing.id(),
            existing.domainId(),
            requireText(request.name(), "knowledgeBase.name"),
            request.shareScope() == null ? existing.shareScope() : request.shareScope(),
            requireText(request.ownerType(), "knowledgeBase.ownerType"),
            requireText(request.ownerId(), "knowledgeBase.ownerId"),
            normalizeOptionalText(request.summary()),
            normalizeOptionalText(request.steward()),
            request.tags() == null ? List.of() : List.copyOf(request.tags()),
            existing.latestRelease(),
            existing.effectiveRelease(),
            existing.releases()
        );
        repo.upsertKnowledgeBase(updated);
        recordKnowledgeEvent("KNOWLEDGE_BASE_UPDATED", PlatformAggregateType.KNOWLEDGE_BASE, updated.id(), Map.of("name", updated.name()));
        return toKnowledgeBaseView(repo, updated);
        });
    }

    public KnowledgeBaseDto deleteKnowledgeBase(String knowledgeBaseId) {
        return withWriteState(repo -> {
        KnowledgeBaseDto existing = toKnowledgeBaseView(repo, findKnowledgeBase(repo, knowledgeBaseId));
        String blocker = findKnowledgeBaseDeletionBlocker(repo, knowledgeBaseId);
        if (blocker != null) {
            throw new IllegalStateException(blocker);
        }
        repo.deleteKnowledgeBase(knowledgeBaseId);
        repo.deleteKnowledgeReleases(knowledgeBaseId);
        recordKnowledgeEvent(
            "KNOWLEDGE_BASE_DELETED",
            PlatformAggregateType.KNOWLEDGE_BASE,
            existing.id(),
            Map.of("name", existing.name(), "deletedObjectId", existing.id(), "deletedObjectName", existing.name())
        );
        return existing;
        });
    }

    public KnowledgeBaseDto deleteKnowledgeBaseUnchecked(String knowledgeBaseId) {
        return withWriteState(repo -> {
        KnowledgeBaseDto existing = toKnowledgeBaseView(repo, findKnowledgeBase(repo, knowledgeBaseId));
        repo.deleteKnowledgeBase(knowledgeBaseId);
        repo.deleteKnowledgeReleases(knowledgeBaseId);
        recordKnowledgeEvent(
            "KNOWLEDGE_BASE_DELETED",
            PlatformAggregateType.KNOWLEDGE_BASE,
            existing.id(),
            Map.of("name", existing.name(), "deletedObjectId", existing.id(), "deletedObjectName", existing.name())
        );
        return existing;
        });
    }

    public List<KnowledgeReleaseDto> listKnowledgeReleases(String knowledgeBaseId) {
        return withReadState(repo -> {
        findKnowledgeBase(repo, knowledgeBaseId);
        return releasesForKnowledgeBase(repo, knowledgeBaseId);
        });
    }

    public KnowledgeReleaseDto createKnowledgeRelease(String knowledgeBaseId, CreateKnowledgeReleaseRequest request) {
        return withWriteState(repo -> {
        findKnowledgeBase(repo, knowledgeBaseId);
        KnowledgeRetrievalProfileDto retrievalProfile = normalizeKnowledgeRetrievalProfile(request == null ? null : request.retrievalProfile());
        String snapshotId = requireText(request == null ? null : request.snapshotId(), "knowledgeRelease.snapshotId");
        VersionStatus status = request == null || request.status() == null ? VersionStatus.DRAFT : request.status();
        validateSnapshotReady(snapshotId);
        List<KnowledgeReleaseDto> existingReleases = new ArrayList<>(releasesForKnowledgeBase(repo, knowledgeBaseId));
        List<KnowledgeReleaseDto> normalizedExisting = status == VersionStatus.PUBLISHED
            ? existingReleases.stream().map(release -> release.status() == VersionStatus.PUBLISHED
                ? new KnowledgeReleaseDto(
                    release.id(),
                    release.knowledgeBaseId(),
                    release.version(),
                    VersionStatus.DRAFT,
                    release.summary(),
                    release.snapshotId(),
                    release.retrievalProfile(),
                    release.createdAt(),
                    null
                )
                : release)
                .toList()
            : existingReleases;
        String summary = normalizeOptionalText(request == null ? null : request.summary());
        KnowledgeReleaseDto created = new KnowledgeReleaseDto(
            nextId("knowledge-release"),
            knowledgeBaseId,
            nextKnowledgeReleaseVersion(normalizedExisting),
            status,
            summary.isBlank() ? "初始发布" : summary,
            snapshotId,
            retrievalProfile,
            Instant.now(),
            status == VersionStatus.PUBLISHED ? Instant.now() : null
        );
        List<KnowledgeReleaseDto> updated = new ArrayList<>(normalizedExisting);
        updated.add(created);
        repo.replaceKnowledgeReleases(knowledgeBaseId, updated);
        recordKnowledgeEvent(
            "KNOWLEDGE_RELEASE_CREATED",
            PlatformAggregateType.KNOWLEDGE_BASE,
            knowledgeBaseId,
            Map.of(
                "name", findKnowledgeBase(repo, knowledgeBaseId).name(),
                "releaseId", created.id(),
                "version", created.version(),
                "status", created.status().name(),
                "snapshotId", created.snapshotId()
            )
        );
        if (created.status() == VersionStatus.PUBLISHED) {
            recordKnowledgeEvent(
                "KNOWLEDGE_RELEASE_PUBLISHED",
                PlatformAggregateType.KNOWLEDGE_BASE,
                knowledgeBaseId,
                Map.of(
                    "name", findKnowledgeBase(repo, knowledgeBaseId).name(),
                    "releaseId", created.id(),
                    "version", created.version(),
                    "status", created.status().name(),
                    "snapshotId", created.snapshotId()
                )
            );
        }
        return created;
        });
    }

    public KnowledgeReleaseDto publishKnowledgeRelease(String knowledgeBaseId, String releaseId) {
        return withWriteState(repo -> {
        findKnowledgeBase(repo, knowledgeBaseId);
        KnowledgeReleaseDto target = findKnowledgeRelease(repo, knowledgeBaseId, releaseId);
        validateSnapshotReady(target.snapshotId());
        List<KnowledgeReleaseDto> updated = releasesForKnowledgeBase(repo, knowledgeBaseId).stream()
            .map(release -> new KnowledgeReleaseDto(
                release.id(),
                release.knowledgeBaseId(),
                release.version(),
                release.id().equals(releaseId) ? VersionStatus.PUBLISHED : VersionStatus.DRAFT,
                release.summary(),
                release.snapshotId(),
                release.retrievalProfile(),
                release.createdAt(),
                release.id().equals(releaseId) ? Instant.now() : null
            ))
            .toList();
        repo.replaceKnowledgeReleases(knowledgeBaseId, updated);
        KnowledgeReleaseDto publishedRelease = updated.stream().filter(item -> item.id().equals(releaseId)).findFirst().orElseThrow();
        recordKnowledgeEvent(
            "KNOWLEDGE_RELEASE_PUBLISHED",
            PlatformAggregateType.KNOWLEDGE_BASE,
            knowledgeBaseId,
            Map.of(
                "name", findKnowledgeBase(repo, knowledgeBaseId).name(),
                "releaseId", publishedRelease.id(),
                "version", publishedRelease.version(),
                "status", publishedRelease.status().name(),
                "snapshotId", publishedRelease.snapshotId()
            )
        );
        return publishedRelease;
        });
    }

    public KnowledgeReleaseDto deleteKnowledgeRelease(String knowledgeBaseId, String releaseId) {
        return withWriteState(repo -> {
        KnowledgeReleaseDto existing = findKnowledgeRelease(repo, knowledgeBaseId, releaseId);
        String blocker = findKnowledgeReleaseDeletionBlocker(repo, knowledgeBaseId, releaseId);
        if (blocker != null) {
            throw new IllegalStateException(blocker);
        }
        repo.replaceKnowledgeReleases(
            knowledgeBaseId,
            releasesForKnowledgeBase(repo, knowledgeBaseId).stream()
                .filter(item -> !item.id().equals(releaseId))
                .toList()
        );
        recordKnowledgeEvent(
            "KNOWLEDGE_RELEASE_DELETED",
            PlatformAggregateType.KNOWLEDGE_BASE,
            knowledgeBaseId,
            Map.of(
                "name", findKnowledgeBase(repo, knowledgeBaseId).name(),
                "releaseId", existing.id(),
                "version", existing.version(),
                "status", existing.status().name(),
                "snapshotId", existing.snapshotId()
            )
        );
        return existing;
        });
    }

    public List<KnowledgeReferenceDto> listKnowledgeReferences(String knowledgeBaseId) {
        return withReadState((repo, catalogState) ->
            listKnowledgeBaseReferences(repo, catalogState, toKnowledgeBaseView(repo, findKnowledgeBase(repo, knowledgeBaseId)))
        );
    }

    public KnowledgeUploadSessionDto createUploadSession(String knowledgeBaseId) {
        return withReadState(repo -> {
        requireKnowledgeBase(repo, knowledgeBaseId);
        return knowledgeServiceClient.createUploadSession(knowledgeBaseId);
        });
    }

    public KnowledgeUploadCompletionDto completeUpload(
        String knowledgeBaseId,
        String uploadSessionId,
        String fileName,
        String contentType,
        byte[] payload
    ) {
        return withReadState(repo -> {
        requireKnowledgeBase(repo, knowledgeBaseId);
        KnowledgeUploadCompletionDto completed = knowledgeServiceClient.completeUpload(knowledgeBaseId, uploadSessionId, fileName, contentType, payload);
        knowledgeWorkflowGateway.startImport(knowledgeBaseId, completed.importJob().id());
        return completed;
        });
    }

    public KnowledgeUploadCompletionDto importUrl(String knowledgeBaseId, CreateKnowledgeUrlImportRequest request) {
        return withReadState(repo -> {
        requireKnowledgeBase(repo, knowledgeBaseId);
        if (request == null || request.url() == null || request.url().isBlank()) {
            throw new IllegalArgumentException("knowledge url import requires a non-empty url");
        }
        KnowledgeUploadCompletionDto completed = knowledgeServiceClient.importUrl(knowledgeBaseId, request.url().trim(), normalizeOptionalText(request.title()));
        knowledgeWorkflowGateway.startImport(knowledgeBaseId, completed.importJob().id());
        return completed;
        });
    }

    public List<KnowledgeFileDto> listFiles(String knowledgeBaseId) {
        return withReadState(repo -> {
        requireKnowledgeBase(repo, knowledgeBaseId);
        return knowledgeServiceClient.listFiles(knowledgeBaseId);
        });
    }

    public List<KnowledgeImportJobDto> listImportJobs(String knowledgeBaseId) {
        return withReadState(repo -> {
        requireKnowledgeBase(repo, knowledgeBaseId);
        return knowledgeServiceClient.listImportJobs(knowledgeBaseId);
        });
    }

    public KnowledgeUploadCompletionDto retryImportJob(String knowledgeBaseId, String jobId) {
        return withReadState(repo -> {
        requireKnowledgeBase(repo, knowledgeBaseId);
        KnowledgeImportJobDto importJob = knowledgeServiceClient.retryImportJob(jobId);
        if (!knowledgeBaseId.equals(importJob.knowledgeBaseId())) {
            throw new IllegalArgumentException("import job does not belong to the requested knowledge base");
        }
        KnowledgeFileDto file = listFiles(knowledgeBaseId).stream()
            .filter(item -> item.id().equals(importJob.fileId()))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("knowledge file not found: " + importJob.fileId()));
        knowledgeWorkflowGateway.startImport(knowledgeBaseId, importJob.id());
        return new KnowledgeUploadCompletionDto(file, importJob);
        });
    }

    public List<KnowledgeDocumentDto> listDocuments(String knowledgeBaseId) {
        return withReadState(repo -> {
        requireKnowledgeBase(repo, knowledgeBaseId);
        return knowledgeServiceClient.listDocuments(knowledgeBaseId);
        });
    }

    public KnowledgeDocumentDeletionPreviewDto previewDocumentDeletion(String knowledgeBaseId, String documentId) {
        return withReadState(repo -> {
        requireKnowledgeBase(repo, knowledgeBaseId);
        KnowledgeDocumentDeletionPreviewDto preview = knowledgeServiceClient.previewDocumentDeletion(knowledgeBaseId, documentId);
        if (!knowledgeBaseId.equals(preview.knowledgeBaseId())) {
            throw new IllegalArgumentException("knowledge document does not belong to the requested knowledge base");
        }
        return preview;
        });
    }

    public KnowledgeDocumentDeletionResultDto deleteDocument(String knowledgeBaseId, String documentId) {
        return withReadState(repo -> {
        requireKnowledgeBase(repo, knowledgeBaseId);
        KnowledgeDocumentDeletionPreviewDto preview = previewDocumentDeletion(knowledgeBaseId, documentId);
        if (!preview.canDelete()) {
            String blocker = preview.blockers().stream()
                .findFirst()
                .map(item -> "knowledge document is referenced by snapshot: " + item.snapshotId())
                .orElse("knowledge document cannot be deleted");
            throw new IllegalStateException(blocker);
        }
        KnowledgeDocumentDeletionResultDto deleted = knowledgeServiceClient.deleteDocument(knowledgeBaseId, documentId);
        if (!knowledgeBaseId.equals(deleted.knowledgeBaseId())) {
            throw new IllegalArgumentException("knowledge document does not belong to the requested knowledge base");
        }
        return deleted;
        });
    }

    public KnowledgeIndexSnapshotDto createIndexSnapshot(String knowledgeBaseId, CreateKnowledgeIndexSnapshotRequest request) {
        return withReadState(repo -> {
        requireKnowledgeBase(repo, knowledgeBaseId);
        KnowledgeIndexSnapshotDto snapshot = knowledgeServiceClient.createIndexSnapshot(
            knowledgeBaseId,
            request == null || request.documentIds() == null ? List.of() : List.copyOf(request.documentIds())
        );
        knowledgeWorkflowGateway.startIndexBuild(knowledgeBaseId, snapshot.id());
        return snapshot;
        });
    }

    public List<KnowledgeIndexSnapshotDto> listIndexSnapshots(String knowledgeBaseId) {
        return withReadState(repo -> {
        requireKnowledgeBase(repo, knowledgeBaseId);
        return knowledgeServiceClient.listIndexSnapshots(knowledgeBaseId);
        });
    }

    public KnowledgeIndexSnapshotDto retryIndexSnapshot(String knowledgeBaseId, String snapshotId) {
        return withReadState(repo -> {
        requireKnowledgeBase(repo, knowledgeBaseId);
        KnowledgeIndexSnapshotDto snapshot = knowledgeServiceClient.retryIndexSnapshot(snapshotId);
        if (!knowledgeBaseId.equals(snapshot.knowledgeBaseId())) {
            throw new IllegalArgumentException("snapshot does not belong to the requested knowledge base");
        }
        knowledgeWorkflowGateway.startIndexBuild(knowledgeBaseId, snapshot.id());
        return snapshot;
        });
    }

    public KnowledgeRetrievalPreviewResultDto previewRetrieval(String knowledgeBaseId, KnowledgeRetrievalPreviewRequest request) {
        return withReadState(repo -> {
        requireKnowledgeBase(repo, knowledgeBaseId);
        if (request == null || request.snapshotId() == null || request.snapshotId().isBlank()) {
            throw new IllegalArgumentException("knowledge retrieval preview requires a snapshotId");
        }
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("knowledge retrieval preview requires a query");
        }
        KnowledgeIndexSnapshotDto snapshot = knowledgeServiceClient.getIndexSnapshot(request.snapshotId().trim());
        if (!knowledgeBaseId.equals(snapshot.knowledgeBaseId())) {
            throw new IllegalArgumentException("snapshot does not belong to the requested knowledge base");
        }
        if (!"READY".equalsIgnoreCase(snapshot.status())) {
            throw new IllegalStateException("knowledge snapshot is not ready: " + snapshot.id());
        }
        return knowledgeServiceClient.previewRetrieval(
            new KnowledgeRetrievalPreviewRequest(
                snapshot.id(),
                request.query().trim(),
                request.topK(),
                request.minScore(),
                request.retrievalMode()
            )
        );
        });
    }

    public KnowledgeBindingSnapshotDto resolveKnowledgeBinding(String knowledgeBaseId) {
        return withReadState(repo -> {
        KnowledgeBaseDto knowledgeBase = toKnowledgeBaseView(repo, findKnowledgeBase(repo, knowledgeBaseId));
        KnowledgeReleaseDto release = knowledgeBase.effectiveRelease();
        if (release == null) {
            throw new IllegalStateException("knowledge base has no published release: " + knowledgeBase.name());
        }
        validateSnapshotReady(release.snapshotId());
        return new KnowledgeBindingSnapshotDto(
            knowledgeBase.id(),
            knowledgeBase.name(),
            release.id(),
            release.version(),
            release.snapshotId(),
            release.retrievalProfile().defaultTopK(),
            release.retrievalProfile().retrievalMode(),
            release.retrievalProfile().minScore()
        );
        });
    }

    public boolean hasKnowledgeBasesInDomain(String domainId) {
        return withReadState(repo -> {
        return repo.listKnowledgeBases().stream().anyMatch(item -> item.domainId().equals(domainId));
        });
    }

    public boolean hasAssistantOwnedKnowledgeBases(String assistantId) {
        return withReadState(repo -> {
        return repo.listKnowledgeBases().stream().anyMatch(item -> "ASSISTANT".equals(item.ownerType()) && assistantId.equals(item.ownerId()));
        });
    }

    public String resolveDefaultKnowledgeBaseId(String preferredKnowledgeBaseId) {
        return withReadState(repo -> {
        if (preferredKnowledgeBaseId != null && repo.listKnowledgeBases().stream().anyMatch(item -> item.id().equals(preferredKnowledgeBaseId))) {
            return preferredKnowledgeBaseId;
        }
        return repo.listKnowledgeBases().stream().map(KnowledgeBaseDto::id).findFirst().orElse(null);
        });
    }

    private void recordKnowledgeEvent(String eventType, PlatformAggregateType aggregateType, String aggregateId, Map<String, Object> payload) {
        platformEventService.recordControlEvent(eventType, aggregateType, aggregateId, payload);
    }

    private void requireDomain(String domainId) {
        if (catalogRepository.findDomain(domainId).isEmpty()) {
            throw new NoSuchElementException("business domain not found: " + domainId);
        }
    }

    private void validateKnowledgeOwner(String domainId, String ownerType, String ownerId) {
        String normalizedOwnerType = requireText(ownerType, "knowledgeBase.ownerType");
        String normalizedOwnerId = requireText(ownerId, "knowledgeBase.ownerId");
        if ("DOMAIN".equals(normalizedOwnerType)) {
            if (!domainId.equals(normalizedOwnerId)) {
                throw new IllegalArgumentException("knowledgeBase ownerId must match domainId when ownerType=DOMAIN");
            }
            return;
        }
        if (!"ASSISTANT".equals(normalizedOwnerType)) {
            throw new IllegalArgumentException("knowledgeBase ownerType must be DOMAIN or ASSISTANT");
        }
        AssistantDto assistant = catalogRepository.findAssistant(normalizedOwnerId)
            .orElseThrow(() -> new NoSuchElementException("assistant not found: " + normalizedOwnerId));
        ScenarioDto scenario = catalogRepository.findScenario(assistant.scenarioId())
            .orElseThrow(() -> new NoSuchElementException("scenario not found: " + assistant.scenarioId()));
        if (!domainId.equals(scenario.domainId())) {
            throw new IllegalArgumentException("assistant owner must belong to the same business domain as knowledgeBase.domainId");
        }
    }

    private List<KnowledgeReferenceDto> listKnowledgeBaseReferences(
        KnowledgeRepository repo,
        CatalogRepository catalogState,
        KnowledgeBaseDto knowledgeBase
    ) {
        ObjectReferenceAnalysisDto analysis = analyzeKnowledgeBaseReferences(repo, catalogState, knowledgeBase);
        return analysis.relations().stream()
            .map(relation -> new KnowledgeReferenceDto(
                analysis.objectId(),
                relation.relationKind(),
                relation.targetType(),
                relation.targetId(),
                relation.targetName(),
                relation.knowledgeReleaseId(),
                relation.knowledgeReleaseVersion(),
                "BLOCKS_DELETION".equals(relation.impactLevel())
            ))
            .toList();
    }

    private String resolveSourceName(CatalogRepository catalogState, String sourceType, String sourceId) {
        return switch (sourceType) {
            case "ASSISTANT" -> catalogState.findAssistant(sourceId).map(AssistantDto::name).orElse(sourceId);
            case "AGENT" -> catalogState.findAgent(sourceId).map(AgentDto::name).orElse(sourceId);
            default -> sourceId;
        };
    }

    private String findKnowledgeBaseDeletionBlocker(KnowledgeRepository repo, String knowledgeBaseId) {
        ObjectReferenceAnalysisDto analysis = analyzeKnowledgeBaseReferences(
            repo,
            catalogRepository,
            toKnowledgeBaseView(repo, findKnowledgeBase(repo, knowledgeBaseId))
        );
        return analysis.relations().stream()
            .filter(relation -> "BLOCKS_DELETION".equals(relation.impactLevel()))
            .map(this::toKnowledgeBaseDeletionMessage)
            .findFirst()
            .orElse(null);
    }

    private String findKnowledgeReleaseDeletionBlocker(KnowledgeRepository repo, String knowledgeBaseId, String releaseId) {
        KnowledgeReleaseDto release = findKnowledgeRelease(repo, knowledgeBaseId, releaseId);
        if (release.status() == VersionStatus.PUBLISHED) {
            return "published knowledge release cannot be deleted";
        }
        return analyzeKnowledgeBaseReferences(
            repo,
            catalogRepository,
            toKnowledgeBaseView(repo, findKnowledgeBase(repo, knowledgeBaseId))
        ).relations().stream()
            .filter(relation -> "BLOCKS_DELETION".equals(relation.impactLevel()))
            .filter(relation -> releaseId.equals(relation.knowledgeReleaseId()))
            .map(relation -> "knowledge release is still referenced: " + relation.targetName())
            .findFirst()
            .orElse(null);
    }

    private ObjectReferenceAnalysisDto analyzeKnowledgeBaseReferences(
        KnowledgeRepository repo,
        CatalogRepository catalogState,
        KnowledgeBaseDto knowledgeBase
    ) {
        return ObjectReferenceAnalyzer.analyzeKnowledgeBase(
            knowledgeBase,
            catalogState.findKnowledgeBindings(knowledgeBase.id()),
            catalogState.findReleaseKnowledgeRefs(knowledgeBase.id()),
            sourceKey -> resolveSourceName(catalogState, sourceKey),
            this::resolveAssistantReleaseName,
            this::findAssistantReleaseById
        );
    }

    private String resolveSourceName(CatalogRepository catalogState, String sourceKey) {
        int separatorIndex = sourceKey.indexOf(':');
        if (separatorIndex < 0) {
            return sourceKey;
        }
        return resolveSourceName(catalogState, sourceKey.substring(0, separatorIndex), sourceKey.substring(separatorIndex + 1));
    }

    private AssistantReleaseDto findAssistantReleaseById(String releaseId) {
        return catalogRepository.findAssistantReleaseById(releaseId).orElse(null);
    }

    private String resolveAssistantReleaseName(String releaseId) {
        AssistantReleaseDto release = findAssistantReleaseById(releaseId);
        if (release == null) {
            return releaseId;
        }
        String assistantName = catalogRepository.findAssistant(release.assistantId())
            .map(AssistantDto::name)
            .orElse(release.assistantId());
        return assistantName + "@" + release.releaseVersion();
    }

    private String toKnowledgeBaseDeletionMessage(ObjectReferenceRelationDto relation) {
        return switch (relation.relationKind()) {
            case "KNOWLEDGE_BASE_EFFECTIVE_RELEASE" -> "knowledge base has a published release: " + relation.targetName();
            default -> "knowledge base is still referenced: " + relation.targetName();
        };
    }

    private KnowledgeBaseDto toKnowledgeBaseView(KnowledgeRepository repo, KnowledgeBaseDto knowledgeBase) {
        List<KnowledgeReleaseDto> releases = releasesForKnowledgeBase(repo, knowledgeBase.id());
        KnowledgeReleaseDto latestRelease = releases.isEmpty() ? null : releases.getLast();
        KnowledgeReleaseDto effectiveRelease = releases.stream()
            .filter(item -> item.status() == VersionStatus.PUBLISHED)
            .reduce((__, item) -> item)
            .orElse(null);
        return new KnowledgeBaseDto(
            knowledgeBase.id(),
            knowledgeBase.domainId(),
            knowledgeBase.name(),
            knowledgeBase.shareScope(),
            knowledgeBase.ownerType(),
            knowledgeBase.ownerId(),
            knowledgeBase.summary(),
            knowledgeBase.steward(),
            knowledgeBase.tags(),
            latestRelease,
            effectiveRelease,
            releases
        );
    }

    private KnowledgeBaseDto findKnowledgeBase(KnowledgeRepository repo, String knowledgeBaseId) {
        return repo.findKnowledgeBase(knowledgeBaseId)
            .orElseThrow(() -> new NoSuchElementException("knowledge base not found: " + knowledgeBaseId));
    }

    private KnowledgeBaseDto requireKnowledgeBase(KnowledgeRepository repo, String knowledgeBaseId) {
        return findKnowledgeBase(repo, knowledgeBaseId);
    }

    private List<KnowledgeReleaseDto> releasesForKnowledgeBase(KnowledgeRepository repo, String knowledgeBaseId) {
        return repo.listKnowledgeReleases(knowledgeBaseId).stream()
            .sorted(Comparator.comparing(KnowledgeReleaseDto::createdAt))
            .toList();
    }

    private KnowledgeReleaseDto findKnowledgeRelease(KnowledgeRepository repo, String knowledgeBaseId, String releaseId) {
        return releasesForKnowledgeBase(repo, knowledgeBaseId).stream()
            .filter(item -> item.id().equals(releaseId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("knowledge release not found: " + knowledgeBaseId + "/" + releaseId));
    }

    private String nextKnowledgeReleaseVersion(List<KnowledgeReleaseDto> releases) {
        if (releases.isEmpty()) {
            return "0.1.0";
        }
        String[] segments = releases.getLast().version().split("\\.");
        int patch = Integer.parseInt(segments[2]) + 1;
        return segments[0] + "." + segments[1] + "." + patch;
    }

    private KnowledgeRetrievalProfileDto normalizeKnowledgeRetrievalProfile(KnowledgeRetrievalProfileDto profile) {
        int defaultTopK = 5;
        String retrievalMode = "HYBRID";
        double minScore = 0.1;
        if (profile != null) {
            defaultTopK = Math.max(1, profile.defaultTopK());
            String normalizedRetrievalMode = normalizeOptionalText(profile.retrievalMode());
            if (!normalizedRetrievalMode.isBlank()) {
                retrievalMode = normalizedRetrievalMode.toUpperCase(Locale.ROOT);
            }
            minScore = Math.max(0, profile.minScore());
        }
        return new KnowledgeRetrievalProfileDto(
            defaultTopK,
            retrievalMode,
            minScore
        );
    }

    private void validateSnapshotReady(String snapshotId) {
        KnowledgeIndexSnapshotDto snapshot = knowledgeServiceClient.getIndexSnapshot(snapshotId);
        if (!"READY".equalsIgnoreCase(snapshot.status())) {
            throw new IllegalStateException("knowledge snapshot is not ready: " + snapshotId);
        }
    }

    private static String nextId(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalizeOptionalText(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class NoOpKnowledgeWorkflowGateway implements KnowledgeWorkflowGateway {
        @Override
        public void startImport(String knowledgeBaseId, String importJobId) {
        }

        @Override
        public void startIndexBuild(String knowledgeBaseId, String indexSnapshotId) {
        }
    }
}
