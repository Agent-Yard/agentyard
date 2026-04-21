package com.lynxus.platform.knowledge;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public interface KnowledgeRepository {
    long revision();

    default <T> T inReadTransaction(Supplier<T> action) {
        return action.get();
    }

    default <T> T inWriteTransaction(Supplier<T> action) {
        return action.get();
    }

    void upsertKnowledgeBase(KnowledgeBaseDto knowledgeBase);

    void deleteKnowledgeBase(String knowledgeBaseId);

    void replaceKnowledgeReleases(String knowledgeBaseId, List<KnowledgeReleaseDto> releases);

    void deleteKnowledgeReleases(String knowledgeBaseId);

    List<KnowledgeBaseDto> listKnowledgeBases();

    default Optional<KnowledgeBaseDto> findKnowledgeBase(String knowledgeBaseId) {
        return listKnowledgeBases().stream().filter(item -> item.id().equals(knowledgeBaseId)).findFirst();
    }

    List<KnowledgeReleaseDto> listKnowledgeReleases(String knowledgeBaseId);
}
