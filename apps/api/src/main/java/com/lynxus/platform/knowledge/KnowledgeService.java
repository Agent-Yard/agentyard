package com.lynxus.platform.knowledge;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.platform.catalog.CatalogRepository;
import com.lynxus.contracts.runtime.WorkflowContracts.ShareScope;
import com.lynxus.contracts.runtime.WorkflowContracts.VersionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeService {
    private final KnowledgeRepository repository;
    private final CatalogRepository catalogRepository;
    private final KnowledgeServiceClient knowledgeServiceClient;
    private final KnowledgeWorkflowGateway knowledgeWorkflowGateway;
    private boolean initialized;
    private final List<KnowledgeBaseDto> knowledgeBases = new ArrayList<>();
    private final Map<String, List<KnowledgeReleaseDto>> knowledgeReleases = new LinkedHashMap<>();

    public KnowledgeService() {
        this(
            new InMemoryKnowledgeRepository(),
            new com.lynxus.platform.catalog.InMemoryCatalogRepository(),
            new KnowledgeServiceClient("http://localhost:8091"),
            new NoOpKnowledgeWorkflowGateway()
        );
    }

    @Autowired
    public KnowledgeService(
        KnowledgeRepository repository,
        CatalogRepository catalogRepository,
        KnowledgeServiceClient knowledgeServiceClient,
        KnowledgeWorkflowGateway knowledgeWorkflowGateway
    ) {
        this.repository = repository;
        this.catalogRepository = catalogRepository;
        this.knowledgeServiceClient = knowledgeServiceClient;
        this.knowledgeWorkflowGateway = knowledgeWorkflowGateway;
    }

    public synchronized boolean initializeDemoDataIfEmpty() {
        ensureLoaded();
        if (!repository.isEmpty()) {
            return false;
        }
        seed();
        persistState();
        return true;
    }

    public List<KnowledgeBaseDto> listKnowledgeBases() {
        ensureLoaded();
        return knowledgeBases.stream()
            .sorted(Comparator.comparing(KnowledgeBaseDto::name))
            .map(this::toKnowledgeBaseView)
            .toList();
    }

    public List<KnowledgeBaseDto> listKnowledgeBasesByDomain(String domainId) {
        ensureLoaded();
        return knowledgeBases.stream()
            .filter(item -> item.domainId().equals(domainId))
            .sorted(Comparator.comparing(KnowledgeBaseDto::name))
            .map(this::toKnowledgeBaseView)
            .toList();
    }

    public KnowledgeBaseDto getKnowledgeBase(String knowledgeBaseId) {
        ensureLoaded();
        return toKnowledgeBaseView(findKnowledgeBase(knowledgeBaseId));
    }

    public KnowledgeBaseDto createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        ensureLoaded();
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
        knowledgeBases.add(knowledgeBase);
        persistState();
        return toKnowledgeBaseView(knowledgeBase);
    }

    public KnowledgeBaseDto updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
        ensureLoaded();
        KnowledgeBaseDto existing = findKnowledgeBase(knowledgeBaseId);
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
        replace(knowledgeBases, KnowledgeBaseDto::id, updated);
        persistState();
        return toKnowledgeBaseView(updated);
    }

    public KnowledgeBaseDto deleteKnowledgeBase(String knowledgeBaseId) {
        ensureLoaded();
        KnowledgeBaseDto existing = toKnowledgeBaseView(findKnowledgeBase(knowledgeBaseId));
        String blocker = findKnowledgeBaseDeletionBlocker(knowledgeBaseId);
        if (blocker != null) {
            throw new IllegalStateException(blocker);
        }
        knowledgeBases.removeIf(item -> item.id().equals(knowledgeBaseId));
        knowledgeReleases.remove(knowledgeBaseId);
        persistState();
        return existing;
    }

    public List<KnowledgeReleaseDto> listKnowledgeReleases(String knowledgeBaseId) {
        ensureLoaded();
        findKnowledgeBase(knowledgeBaseId);
        return releasesForKnowledgeBase(knowledgeBaseId);
    }

    public KnowledgeReleaseDto createKnowledgeRelease(String knowledgeBaseId, CreateKnowledgeReleaseRequest request) {
        ensureLoaded();
        findKnowledgeBase(knowledgeBaseId);
        KnowledgeRetrievalProfileDto retrievalProfile = normalizeKnowledgeRetrievalProfile(request == null ? null : request.retrievalProfile());
        String snapshotId = requireText(request == null ? null : request.snapshotId(), "knowledgeRelease.snapshotId");
        VersionStatus status = request == null || request.status() == null ? VersionStatus.DRAFT : request.status();
        validateSnapshotReady(snapshotId);
        List<KnowledgeReleaseDto> existingReleases = new ArrayList<>(releasesForKnowledgeBase(knowledgeBaseId));
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
        knowledgeReleases.put(knowledgeBaseId, updated);
        persistState();
        return created;
    }

    public KnowledgeReleaseDto publishKnowledgeRelease(String knowledgeBaseId, String releaseId) {
        ensureLoaded();
        findKnowledgeBase(knowledgeBaseId);
        KnowledgeReleaseDto target = findKnowledgeRelease(knowledgeBaseId, releaseId);
        validateSnapshotReady(target.snapshotId());
        List<KnowledgeReleaseDto> updated = releasesForKnowledgeBase(knowledgeBaseId).stream()
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
        knowledgeReleases.put(knowledgeBaseId, updated);
        persistState();
        return updated.stream().filter(item -> item.id().equals(releaseId)).findFirst().orElseThrow();
    }

    public KnowledgeReleaseDto deleteKnowledgeRelease(String knowledgeBaseId, String releaseId) {
        ensureLoaded();
        KnowledgeReleaseDto existing = findKnowledgeRelease(knowledgeBaseId, releaseId);
        String blocker = findKnowledgeReleaseDeletionBlocker(knowledgeBaseId, releaseId);
        if (blocker != null) {
            throw new IllegalStateException(blocker);
        }
        knowledgeReleases.put(
            knowledgeBaseId,
            releasesForKnowledgeBase(knowledgeBaseId).stream()
                .filter(item -> !item.id().equals(releaseId))
                .toList()
        );
        persistState();
        return existing;
    }

    public List<KnowledgeReferenceDto> listKnowledgeReferences(String knowledgeBaseId) {
        ensureLoaded();
        return listKnowledgeBaseReferences(toKnowledgeBaseView(findKnowledgeBase(knowledgeBaseId)));
    }

    public KnowledgeUploadSessionDto createUploadSession(String knowledgeBaseId) {
        ensureLoaded();
        requireKnowledgeBase(knowledgeBaseId);
        return knowledgeServiceClient.createUploadSession(knowledgeBaseId);
    }

    public KnowledgeUploadCompletionDto completeUpload(
        String knowledgeBaseId,
        String uploadSessionId,
        String fileName,
        String contentType,
        byte[] payload
    ) {
        ensureLoaded();
        requireKnowledgeBase(knowledgeBaseId);
        KnowledgeUploadCompletionDto completed = knowledgeServiceClient.completeUpload(knowledgeBaseId, uploadSessionId, fileName, contentType, payload);
        knowledgeWorkflowGateway.startImport(knowledgeBaseId, completed.importJob().id());
        return completed;
    }

    public KnowledgeUploadCompletionDto importUrl(String knowledgeBaseId, CreateKnowledgeUrlImportRequest request) {
        ensureLoaded();
        requireKnowledgeBase(knowledgeBaseId);
        if (request == null || request.url() == null || request.url().isBlank()) {
            throw new IllegalArgumentException("knowledge url import requires a non-empty url");
        }
        KnowledgeUploadCompletionDto completed = knowledgeServiceClient.importUrl(knowledgeBaseId, request.url().trim(), normalizeOptionalText(request.title()));
        knowledgeWorkflowGateway.startImport(knowledgeBaseId, completed.importJob().id());
        return completed;
    }

    public List<KnowledgeFileDto> listFiles(String knowledgeBaseId) {
        ensureLoaded();
        requireKnowledgeBase(knowledgeBaseId);
        return knowledgeServiceClient.listFiles(knowledgeBaseId);
    }

    public List<KnowledgeImportJobDto> listImportJobs(String knowledgeBaseId) {
        ensureLoaded();
        requireKnowledgeBase(knowledgeBaseId);
        return knowledgeServiceClient.listImportJobs(knowledgeBaseId);
    }

    public List<KnowledgeDocumentDto> listDocuments(String knowledgeBaseId) {
        ensureLoaded();
        requireKnowledgeBase(knowledgeBaseId);
        return knowledgeServiceClient.listDocuments(knowledgeBaseId);
    }

    public KnowledgeIndexSnapshotDto createIndexSnapshot(String knowledgeBaseId, CreateKnowledgeIndexSnapshotRequest request) {
        ensureLoaded();
        requireKnowledgeBase(knowledgeBaseId);
        KnowledgeIndexSnapshotDto snapshot = knowledgeServiceClient.createIndexSnapshot(
            knowledgeBaseId,
            request == null || request.documentIds() == null ? List.of() : List.copyOf(request.documentIds())
        );
        knowledgeWorkflowGateway.startIndexBuild(knowledgeBaseId, snapshot.id());
        return snapshot;
    }

    public List<KnowledgeIndexSnapshotDto> listIndexSnapshots(String knowledgeBaseId) {
        ensureLoaded();
        requireKnowledgeBase(knowledgeBaseId);
        return knowledgeServiceClient.listIndexSnapshots(knowledgeBaseId);
    }

    public KnowledgeBindingSnapshotDto resolveKnowledgeBinding(String knowledgeBaseId) {
        KnowledgeBaseDto knowledgeBase = getKnowledgeBase(knowledgeBaseId);
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
    }

    public boolean hasKnowledgeBasesInDomain(String domainId) {
        ensureLoaded();
        return knowledgeBases.stream().anyMatch(item -> item.domainId().equals(domainId));
    }

    public boolean hasAssistantOwnedKnowledgeBases(String assistantId) {
        ensureLoaded();
        return knowledgeBases.stream().anyMatch(item -> "ASSISTANT".equals(item.ownerType()) && assistantId.equals(item.ownerId()));
    }

    public String resolveDefaultKnowledgeBaseId(String preferredKnowledgeBaseId) {
        ensureLoaded();
        if (preferredKnowledgeBaseId != null && knowledgeBases.stream().anyMatch(item -> item.id().equals(preferredKnowledgeBaseId))) {
            return preferredKnowledgeBaseId;
        }
        return knowledgeBases.stream().map(KnowledgeBaseDto::id).findFirst().orElse(null);
    }

    private synchronized void ensureLoaded() {
        if (initialized) {
            return;
        }
        restore(repository.load());
        initialized = true;
    }

    private void restore(KnowledgeRepository.KnowledgeSnapshot snapshot) {
        knowledgeBases.clear();
        knowledgeBases.addAll(snapshot.knowledgeBases());
        knowledgeReleases.clear();
        snapshot.knowledgeReleases().forEach((knowledgeBaseId, releases) -> knowledgeReleases.put(
            knowledgeBaseId,
            releases.stream()
                .map(release -> new KnowledgeReleaseDto(
                    release.id(),
                    release.knowledgeBaseId(),
                    release.version(),
                    release.status(),
                    release.summary(),
                    release.snapshotId(),
                    normalizeKnowledgeRetrievalProfile(release.retrievalProfile()),
                    release.createdAt(),
                    release.publishedAt()
                ))
                .toList()
        ));
    }

    private void persistState() {
        repository.save(new KnowledgeRepository.KnowledgeSnapshot(List.copyOf(knowledgeBases), Map.copyOf(knowledgeReleases)));
    }

    private void seed() {
        KnowledgeBaseDto knowledgeBase = new KnowledgeBaseDto(
            "knowledge-base-support",
            "domain-support",
            "客服知识库",
            ShareScope.DOMAIN_SHARED,
            "DOMAIN",
            "domain-support",
            "包含 FAQ、售后规则和人工协同说明的演示知识库",
            "客服知识运营",
            List.of("FAQ", "售后", "协同"),
            null,
            null,
            List.of()
        );
        knowledgeBases.add(knowledgeBase);
        knowledgeReleases.put(
            knowledgeBase.id(),
            List.of(new KnowledgeReleaseDto(
                "knowledge-release-support-v1",
                knowledgeBase.id(),
                "1.0.0",
                VersionStatus.PUBLISHED,
                "客服知识库演示版",
                "snapshot-kb-support-v1",
                new KnowledgeRetrievalProfileDto(5, "HYBRID", 0.1),
                Instant.now(),
                Instant.now()
            ))
        );
    }

    private CatalogRepository.CatalogSnapshot catalogSnapshot() {
        return catalogRepository.load();
    }

    private void requireDomain(String domainId) {
        if (catalogSnapshot().domains().stream().noneMatch(item -> item.id().equals(domainId))) {
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
        CatalogRepository.CatalogSnapshot catalog = catalogSnapshot();
        AssistantDto assistant = catalog.assistants().stream()
            .filter(item -> item.id().equals(normalizedOwnerId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("assistant not found: " + normalizedOwnerId));
        ScenarioDto scenario = catalog.scenarios().stream()
            .filter(item -> item.id().equals(assistant.scenarioId()))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("scenario not found: " + assistant.scenarioId()));
        if (!domainId.equals(scenario.domainId())) {
            throw new IllegalArgumentException("assistant owner must belong to the same business domain as knowledgeBase.domainId");
        }
    }

    private List<KnowledgeReferenceDto> listKnowledgeBaseReferences(KnowledgeBaseDto knowledgeBase) {
        CatalogRepository.CatalogSnapshot catalog = catalogSnapshot();
        List<KnowledgeReferenceDto> references = new ArrayList<>();

        // Active bindings from projection table
        for (CatalogRepository.KnowledgeBindingRef ref : catalogRepository.findKnowledgeBindings(knowledgeBase.id())) {
            String sourceName = resolveSourceName(catalog, ref.sourceType(), ref.sourceId());
            references.add(new KnowledgeReferenceDto(
                knowledgeBase.id(), ref.bindingKind(), ref.sourceType(), ref.sourceId(), sourceName, null, null, true
            ));
        }

        // Release-frozen bindings from projection table, enriched with version info from in-memory
        for (CatalogRepository.ReleaseKnowledgeRef ref : catalogRepository.findReleaseKnowledgeRefs(knowledgeBase.id())) {
            enrichReleaseKnowledgeRefs(catalog, knowledgeBase.id(), ref, references);
        }

        references.sort(Comparator.comparing(KnowledgeReferenceDto::referenceKind).thenComparing(KnowledgeReferenceDto::sourceName));
        return references;
    }

    private void enrichReleaseKnowledgeRefs(
        CatalogRepository.CatalogSnapshot catalog,
        String knowledgeBaseId,
        CatalogRepository.ReleaseKnowledgeRef ref,
        List<KnowledgeReferenceDto> references
    ) {
        String assistantName = catalog.assistants().stream()
            .filter(a -> a.id().equals(ref.assistantId())).findFirst()
            .map(AssistantDto::name).orElse(ref.assistantId());

        List<AssistantReleaseDto> releases = catalog.assistantReleases().getOrDefault(ref.assistantId(), List.of());
        AssistantReleaseDto release = releases.stream()
            .filter(r -> r.id().equals(ref.releaseId())).findFirst().orElse(null);
        if (release == null) return;

        // Assistant-level knowledge binding
        if (release.assistantKnowledge() != null && knowledgeBaseId.equals(release.assistantKnowledge().knowledgeBaseId())) {
            references.add(new KnowledgeReferenceDto(
                knowledgeBaseId, "RELEASE_ASSISTANT_KNOWLEDGE", "ASSISTANT_RELEASE",
                release.id(), assistantName + "@" + release.releaseVersion(),
                release.assistantKnowledge().knowledgeReleaseId(),
                release.assistantKnowledge().knowledgeReleaseVersion(), true
            ));
        }
        // Agent-level knowledge bindings
        for (AssistantReleaseAgentDto releaseAgent : release.agents()) {
            if (releaseAgent.knowledge() != null && knowledgeBaseId.equals(releaseAgent.knowledge().knowledgeBaseId())) {
                references.add(new KnowledgeReferenceDto(
                    knowledgeBaseId, "RELEASE_AGENT_KNOWLEDGE", "ASSISTANT_RELEASE_AGENT",
                    releaseAgent.agentId(), assistantName + "/" + releaseAgent.name(),
                    releaseAgent.knowledge().knowledgeReleaseId(),
                    releaseAgent.knowledge().knowledgeReleaseVersion(), true
                ));
            }
        }
    }

    private String resolveSourceName(CatalogRepository.CatalogSnapshot catalog, String sourceType, String sourceId) {
        return switch (sourceType) {
            case "ASSISTANT" -> catalog.assistants().stream()
                .filter(a -> a.id().equals(sourceId)).findFirst()
                .map(AssistantDto::name).orElse(sourceId);
            case "AGENT" -> catalog.agents().stream()
                .filter(a -> a.id().equals(sourceId)).findFirst()
                .map(AgentDto::name).orElse(sourceId);
            default -> sourceId;
        };
    }

    private String findKnowledgeBaseDeletionBlocker(String knowledgeBaseId) {
        KnowledgeBaseDto knowledgeBase = toKnowledgeBaseView(findKnowledgeBase(knowledgeBaseId));
        if (knowledgeBase.effectiveRelease() != null) {
            return "knowledge base has a published release: " + knowledgeBase.name();
        }
        return listKnowledgeBaseReferences(knowledgeBase).stream()
            .filter(KnowledgeReferenceDto::blocksDeletion)
            .map(reference -> "knowledge base is still referenced: " + reference.sourceName())
            .findFirst()
            .orElse(null);
    }

    private String findKnowledgeReleaseDeletionBlocker(String knowledgeBaseId, String releaseId) {
        KnowledgeReleaseDto release = findKnowledgeRelease(knowledgeBaseId, releaseId);
        if (release.status() == VersionStatus.PUBLISHED) {
            return "published knowledge release cannot be deleted";
        }
        return catalogSnapshot().assistantReleases().values().stream()
            .flatMap(List::stream)
            .flatMap(assistantRelease -> {
                List<KnowledgeBindingSnapshotDto> bindings = new ArrayList<>();
                if (assistantRelease.assistantKnowledge() != null) {
                    bindings.add(assistantRelease.assistantKnowledge());
                }
                assistantRelease.agents().stream()
                    .map(AssistantReleaseAgentDto::knowledge)
                    .filter(item -> item != null)
                    .forEach(bindings::add);
                return bindings.stream();
            })
            .filter(binding -> release.id().equals(binding.knowledgeReleaseId()))
            .map(binding -> "knowledge release is still referenced: " + binding.knowledgeReleaseVersion())
            .findFirst()
            .orElse(null);
    }

    private KnowledgeBaseDto toKnowledgeBaseView(KnowledgeBaseDto knowledgeBase) {
        List<KnowledgeReleaseDto> releases = releasesForKnowledgeBase(knowledgeBase.id());
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

    private KnowledgeBaseDto findKnowledgeBase(String knowledgeBaseId) {
        return knowledgeBases.stream()
            .filter(item -> item.id().equals(knowledgeBaseId))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("knowledge base not found: " + knowledgeBaseId));
    }

    private KnowledgeBaseDto requireKnowledgeBase(String knowledgeBaseId) {
        return findKnowledgeBase(knowledgeBaseId);
    }

    private List<KnowledgeReleaseDto> releasesForKnowledgeBase(String knowledgeBaseId) {
        return knowledgeReleases.getOrDefault(knowledgeBaseId, List.of()).stream()
            .sorted(Comparator.comparing(KnowledgeReleaseDto::createdAt))
            .toList();
    }

    private KnowledgeReleaseDto findKnowledgeRelease(String knowledgeBaseId, String releaseId) {
        return releasesForKnowledgeBase(knowledgeBaseId).stream()
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
        return new KnowledgeRetrievalProfileDto(
            profile == null ? 5 : Math.max(1, profile.defaultTopK()),
            normalizeOptionalText(profile == null ? null : profile.retrievalMode()).isBlank()
                ? "HYBRID"
                : normalizeOptionalText(profile.retrievalMode()).toUpperCase(),
            profile == null ? 0.1 : Math.max(0, profile.minScore())
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

    private static <T, K> void replace(List<T> items, Function<T, K> keyExtractor, T replacement) {
        for (int index = 0; index < items.size(); index++) {
            if (keyExtractor.apply(items.get(index)).equals(keyExtractor.apply(replacement))) {
                items.set(index, replacement);
                return;
            }
        }
        throw new NoSuchElementException("replacement target not found");
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
