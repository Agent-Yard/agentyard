package com.lynxus.platform.catalog;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import java.util.List;
import java.util.Map;

public interface CatalogRepository {
    CatalogSnapshot load();

    void save(CatalogSnapshot snapshot);

    boolean isEmpty();

    record CatalogSnapshot(
        List<BusinessDomainDto> domains,
        List<ScenarioDto> scenarios,
        List<AssistantDto> assistants,
        List<AgentDto> agents,
        List<ResourceDto> resources,
        Map<String, List<StoredResourceVersion>> resourceVersions,
        Map<String, List<AssistantReleaseDto>> assistantReleases,
        Map<String, AssistantOrchestrationDto> orchestrations
    ) {
        public static CatalogSnapshot empty() {
            return new CatalogSnapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of()
            );
        }
    }
}
