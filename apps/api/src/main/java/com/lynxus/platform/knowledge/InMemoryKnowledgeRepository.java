package com.lynxus.platform.knowledge;

public class InMemoryKnowledgeRepository implements KnowledgeRepository {
    private KnowledgeSnapshot snapshot = KnowledgeSnapshot.empty();

    @Override
    public KnowledgeSnapshot load() {
        return snapshot;
    }

    @Override
    public void save(KnowledgeSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public boolean isEmpty() {
        return snapshot.knowledgeBases().isEmpty() && snapshot.knowledgeReleases().isEmpty();
    }
}
