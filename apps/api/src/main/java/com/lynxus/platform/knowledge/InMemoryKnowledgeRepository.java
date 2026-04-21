package com.lynxus.platform.knowledge;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.platform.shared.ConflictException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class InMemoryKnowledgeRepository implements KnowledgeRepository {
    protected static final class KnowledgeData {
        final List<KnowledgeBaseDto> knowledgeBases;
        final Map<String, List<KnowledgeReleaseDto>> knowledgeReleases;

        private KnowledgeData() {
            this(List.of(), Map.of());
        }

        private KnowledgeData(List<KnowledgeBaseDto> knowledgeBases, Map<String, List<KnowledgeReleaseDto>> knowledgeReleases) {
            this.knowledgeBases = new ArrayList<>(knowledgeBases);
            this.knowledgeReleases = copyMappedLists(knowledgeReleases);
        }

        private KnowledgeData copy() {
            return new KnowledgeData(knowledgeBases, knowledgeReleases);
        }
    }

    private KnowledgeData committedData = new KnowledgeData();
    private long revision;
    private final ThreadLocal<KnowledgeData> transactionalData = new ThreadLocal<>();
    private final ThreadLocal<KnowledgeData> readOnlyData = new ThreadLocal<>();

    private KnowledgeData activeData() {
        KnowledgeData current = transactionalData.get();
        if (current != null) {
            return current;
        }
        KnowledgeData readSnapshot = readOnlyData.get();
        return readSnapshot == null ? committedData : readSnapshot;
    }

    protected KnowledgeData committedCopy() {
        return committedData.copy();
    }

    protected void commit(KnowledgeData working, long expectedRevision) {
        if (revision != expectedRevision) {
            throw new ConflictException("knowledge changed on another instance; retry the request");
        }
        committedData = working.copy();
        revision += 1;
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public <T> T inReadTransaction(java.util.function.Supplier<T> action) {
        if (transactionalData.get() != null || readOnlyData.get() != null) {
            return action.get();
        }
        readOnlyData.set(committedData.copy());
        try {
            return action.get();
        } finally {
            readOnlyData.remove();
        }
    }

    @Override
    public <T> T inWriteTransaction(java.util.function.Supplier<T> action) {
        if (transactionalData.get() != null) {
            return action.get();
        }
        KnowledgeData working = committedData.copy();
        long expectedRevision = revision;
        transactionalData.set(working);
        try {
            T result = action.get();
            commit(working, expectedRevision);
            return result;
        } finally {
            transactionalData.remove();
        }
    }

    @Override
    public List<KnowledgeBaseDto> listKnowledgeBases() {
        return List.copyOf(activeData().knowledgeBases);
    }

    @Override
    public List<KnowledgeReleaseDto> listKnowledgeReleases(String knowledgeBaseId) {
        return List.copyOf(activeData().knowledgeReleases.getOrDefault(knowledgeBaseId, List.of()));
    }

    @Override
    public void upsertKnowledgeBase(KnowledgeBaseDto knowledgeBase) {
        upsert(activeData().knowledgeBases, knowledgeBase, KnowledgeBaseDto::id);
    }

    @Override
    public void deleteKnowledgeBase(String knowledgeBaseId) {
        activeData().knowledgeBases.removeIf(item -> item.id().equals(knowledgeBaseId));
    }

    @Override
    public void replaceKnowledgeReleases(String knowledgeBaseId, List<KnowledgeReleaseDto> releases) {
        activeData().knowledgeReleases.put(knowledgeBaseId, new ArrayList<>(releases));
    }

    @Override
    public void deleteKnowledgeReleases(String knowledgeBaseId) {
        activeData().knowledgeReleases.remove(knowledgeBaseId);
    }

    private static <T, K> void upsert(List<T> items, T replacement, Function<T, K> keyExtractor) {
        K replacementKey = keyExtractor.apply(replacement);
        items.removeIf(item -> keyExtractor.apply(item).equals(replacementKey));
        items.add(replacement);
    }

    private static <T> Map<String, List<T>> copyMappedLists(Map<String, List<T>> source) {
        Map<String, List<T>> copied = new LinkedHashMap<>();
        for (Map.Entry<String, List<T>> entry : source.entrySet()) {
            copied.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copied;
    }

}
