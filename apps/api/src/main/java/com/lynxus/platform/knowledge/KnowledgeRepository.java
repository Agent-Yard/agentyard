package com.lynxus.platform.knowledge;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import java.util.List;
import java.util.Map;

public interface KnowledgeRepository {
    KnowledgeSnapshot load();

    void save(KnowledgeSnapshot snapshot);

    boolean isEmpty();

    record KnowledgeSnapshot(
        List<KnowledgeBaseDto> knowledgeBases,
        Map<String, List<KnowledgeReleaseDto>> knowledgeReleases
    ) {
        public static KnowledgeSnapshot empty() {
            return new KnowledgeSnapshot(List.of(), Map.of());
        }
    }
}
