package com.lynxus.platform.catalog;

public class InMemoryCatalogRepository implements CatalogRepository {
    private CatalogSnapshot snapshot = CatalogSnapshot.empty();

    @Override
    public CatalogSnapshot load() {
        return snapshot;
    }

    @Override
    public void save(CatalogSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public boolean isEmpty() {
        return snapshot.domains().isEmpty()
            && snapshot.scenarios().isEmpty()
            && snapshot.assistants().isEmpty()
            && snapshot.agents().isEmpty()
            && snapshot.resources().isEmpty()
            && snapshot.resourceVersions().isEmpty()
            && snapshot.assistantReleases().isEmpty()
            && snapshot.orchestrations().isEmpty();
    }
}
