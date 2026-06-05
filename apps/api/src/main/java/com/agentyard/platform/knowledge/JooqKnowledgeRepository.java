package com.agentyard.platform.knowledge;

import static com.agentyard.platform.catalog.CatalogDtos.*;
import static com.agentyard.persistence.jooq.Tables.KNOWLEDGE_BASE;
import static com.agentyard.persistence.jooq.Tables.KNOWLEDGE_RELEASE;

import com.agentyard.contracts.runtime.WorkflowContracts.ShareScope;
import com.agentyard.contracts.runtime.WorkflowContracts.VersionStatus;
import com.agentyard.persistence.jooqsupport.JooqJsonbSupport;
import com.agentyard.persistence.jooqsupport.JooqTimeSupport;
import com.agentyard.persistence.shared.SharedStateRevisionStore;
import com.agentyard.platform.shared.ConflictException;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JooqKnowledgeRepository implements KnowledgeRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final DSLContext dsl;
    private final JooqJsonbSupport jsonbSupport;
    private final SharedStateRevisionStore revisionStore;
    private final ThreadLocal<Boolean> writeTransactionOpen = ThreadLocal.withInitial(() -> false);

    public JooqKnowledgeRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.jsonbSupport = new JooqJsonbSupport(objectMapper);
        this.revisionStore = new SharedStateRevisionStore(dsl);
    }

    @Override
    public long revision() {
        return revisionStore.revision("knowledge");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public <T> T inReadTransaction(java.util.function.Supplier<T> action) {
        return action.get();
    }

    @Override
    @Transactional
    public <T> T inWriteTransaction(java.util.function.Supplier<T> action) {
        if (writeTransactionOpen.get()) {
            return action.get();
        }
        long expectedRevision = revision();
        if (!revisionStore.reserveRevision("knowledge", expectedRevision)) {
            throw new ConflictException("knowledge changed on another instance; retry the request");
        }
        writeTransactionOpen.set(true);
        try {
            return action.get();
        } finally {
            writeTransactionOpen.remove();
        }
    }

    @Override
    public List<KnowledgeBaseDto> listKnowledgeBases() {
        return dsl.selectFrom(KNOWLEDGE_BASE)
            .orderBy(KNOWLEDGE_BASE.ID.asc())
            .fetch(record -> new KnowledgeBaseDto(
                record.getId(),
                record.getDomainId(),
                record.getName(),
                ShareScope.valueOf(record.getShareScope()),
                record.getOwnerType(),
                record.getOwnerId(),
                record.getSummary(),
                record.getSteward(),
                readStringList(record.getTags()),
                null,
                null,
                List.of()
            ));
    }

    @Override
    public Optional<KnowledgeBaseDto> findKnowledgeBase(String knowledgeBaseId) {
        return dsl.selectFrom(KNOWLEDGE_BASE)
            .where(KNOWLEDGE_BASE.ID.eq(knowledgeBaseId))
            .fetchOptional(record -> new KnowledgeBaseDto(
                record.getId(),
                record.getDomainId(),
                record.getName(),
                ShareScope.valueOf(record.getShareScope()),
                record.getOwnerType(),
                record.getOwnerId(),
                record.getSummary(),
                record.getSteward(),
                readStringList(record.getTags()),
                null,
                null,
                List.of()
            ));
    }

    @Override
    public List<KnowledgeReleaseDto> listKnowledgeReleases(String knowledgeBaseId) {
        return dsl.selectFrom(KNOWLEDGE_RELEASE)
            .where(KNOWLEDGE_RELEASE.KNOWLEDGE_BASE_ID.eq(knowledgeBaseId))
            .orderBy(KNOWLEDGE_RELEASE.CREATED_AT.asc(), KNOWLEDGE_RELEASE.ID.asc())
            .fetch(record -> new KnowledgeReleaseDto(
                record.getId(),
                record.getKnowledgeBaseId(),
                record.getVersion(),
                VersionStatus.valueOf(record.getStatus()),
                record.getSummary(),
                record.getSnapshotId(),
                jsonbSupport.read(record.getRetrievalProfile(), KnowledgeRetrievalProfileDto.class),
                JooqTimeSupport.toInstant(record.getCreatedAt()),
                JooqTimeSupport.toInstant(record.getPublishedAt())
            ));
    }

    @Override
    public void upsertKnowledgeBase(KnowledgeBaseDto knowledgeBase) {
        dsl.insertInto(KNOWLEDGE_BASE)
            .set(KNOWLEDGE_BASE.ID, knowledgeBase.id())
            .set(KNOWLEDGE_BASE.DOMAIN_ID, knowledgeBase.domainId())
            .set(KNOWLEDGE_BASE.NAME, knowledgeBase.name())
            .set(KNOWLEDGE_BASE.SHARE_SCOPE, knowledgeBase.shareScope().name())
            .set(KNOWLEDGE_BASE.OWNER_TYPE, knowledgeBase.ownerType())
            .set(KNOWLEDGE_BASE.OWNER_ID, knowledgeBase.ownerId())
            .set(KNOWLEDGE_BASE.SUMMARY, knowledgeBase.summary())
            .set(KNOWLEDGE_BASE.STEWARD, knowledgeBase.steward())
            .set(KNOWLEDGE_BASE.TAGS, jsonbSupport.toJsonb(knowledgeBase.tags() == null ? List.of() : knowledgeBase.tags()))
            .onConflict(KNOWLEDGE_BASE.ID)
            .doUpdate()
            .set(KNOWLEDGE_BASE.DOMAIN_ID, knowledgeBase.domainId())
            .set(KNOWLEDGE_BASE.NAME, knowledgeBase.name())
            .set(KNOWLEDGE_BASE.SHARE_SCOPE, knowledgeBase.shareScope().name())
            .set(KNOWLEDGE_BASE.OWNER_TYPE, knowledgeBase.ownerType())
            .set(KNOWLEDGE_BASE.OWNER_ID, knowledgeBase.ownerId())
            .set(KNOWLEDGE_BASE.SUMMARY, knowledgeBase.summary())
            .set(KNOWLEDGE_BASE.STEWARD, knowledgeBase.steward())
            .set(KNOWLEDGE_BASE.TAGS, jsonbSupport.toJsonb(knowledgeBase.tags() == null ? List.of() : knowledgeBase.tags()))
            .execute();
    }

    @Override
    public void deleteKnowledgeBase(String knowledgeBaseId) {
        dsl.deleteFrom(KNOWLEDGE_BASE)
            .where(KNOWLEDGE_BASE.ID.eq(knowledgeBaseId))
            .execute();
    }

    @Override
    public void replaceKnowledgeReleases(String knowledgeBaseId, List<KnowledgeReleaseDto> releases) {
        deleteKnowledgeReleases(knowledgeBaseId);
        for (KnowledgeReleaseDto release : releases) {
            dsl.insertInto(KNOWLEDGE_RELEASE)
                .set(KNOWLEDGE_RELEASE.ID, release.id())
                .set(KNOWLEDGE_RELEASE.KNOWLEDGE_BASE_ID, release.knowledgeBaseId())
                .set(KNOWLEDGE_RELEASE.VERSION, release.version())
                .set(KNOWLEDGE_RELEASE.STATUS, release.status().name())
                .set(KNOWLEDGE_RELEASE.SUMMARY, release.summary())
                .set(KNOWLEDGE_RELEASE.SNAPSHOT_ID, release.snapshotId())
                .set(KNOWLEDGE_RELEASE.RETRIEVAL_PROFILE, jsonbSupport.toJsonb(release.retrievalProfile()))
                .set(KNOWLEDGE_RELEASE.CREATED_AT, JooqTimeSupport.toOffsetDateTime(release.createdAt()))
                .set(KNOWLEDGE_RELEASE.PUBLISHED_AT, JooqTimeSupport.toOffsetDateTime(release.publishedAt()))
                .execute();
        }
    }

    @Override
    public void deleteKnowledgeReleases(String knowledgeBaseId) {
        dsl.deleteFrom(KNOWLEDGE_RELEASE)
            .where(KNOWLEDGE_RELEASE.KNOWLEDGE_BASE_ID.eq(knowledgeBaseId))
            .execute();
    }

    private List<String> readStringList(JSONB value) {
        List<String> items = jsonbSupport.read(value, STRING_LIST);
        return items == null ? List.of() : List.copyOf(items);
    }
}
