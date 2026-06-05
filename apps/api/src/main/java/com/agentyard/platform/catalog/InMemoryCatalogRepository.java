package com.agentyard.platform.catalog;

import static com.agentyard.platform.catalog.CatalogDtos.*;

import com.agentyard.platform.shared.ConflictException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class InMemoryCatalogRepository implements CatalogRepository {
    protected static final class CatalogData {
        final List<BusinessDomainDto> domains;
        final List<ScenarioDto> scenarios;
        final List<AssistantDto> assistants;
        final List<AgentDto> agents;
        final List<PlaybookDto> playbooks;
        final List<ResourceDto> resources;
        final Map<String, List<StoredResourceVersion>> resourceVersions;
        final Map<String, List<AssistantReleaseDto>> assistantReleases;

        private CatalogData() {
            this(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
        }

        private CatalogData(
            List<BusinessDomainDto> domains,
            List<ScenarioDto> scenarios,
            List<AssistantDto> assistants,
            List<AgentDto> agents,
            List<PlaybookDto> playbooks,
            List<ResourceDto> resources,
            Map<String, List<StoredResourceVersion>> resourceVersions,
            Map<String, List<AssistantReleaseDto>> assistantReleases
        ) {
            this.domains = new ArrayList<>(domains);
            this.scenarios = new ArrayList<>(scenarios);
            this.assistants = new ArrayList<>(assistants);
            this.agents = new ArrayList<>(agents);
            this.playbooks = new ArrayList<>(playbooks);
            this.resources = new ArrayList<>(resources);
            this.resourceVersions = copyMappedLists(resourceVersions);
            this.assistantReleases = copyMappedLists(assistantReleases);
        }

        private CatalogData copy() {
            return new CatalogData(domains, scenarios, assistants, agents, playbooks, resources, resourceVersions, assistantReleases);
        }
    }

    private CatalogData committedData = new CatalogData();
    private long revision;
    private final ThreadLocal<CatalogData> transactionalData = new ThreadLocal<>();
    private final ThreadLocal<CatalogData> readOnlyData = new ThreadLocal<>();

    private CatalogData activeData() {
        CatalogData current = transactionalData.get();
        if (current != null) {
            return current;
        }
        CatalogData readSnapshot = readOnlyData.get();
        return readSnapshot == null ? committedData : readSnapshot;
    }

    protected CatalogData committedCopy() {
        return committedData.copy();
    }

    protected void commit(CatalogData working, long expectedRevision) {
        if (revision != expectedRevision) {
            throw new ConflictException("catalog changed on another instance; retry the request");
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
        CatalogData working = committedData.copy();
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
    public List<BusinessDomainDto> listDomains() {
        return List.copyOf(activeData().domains);
    }

    @Override
    public List<ScenarioDto> listScenarios() {
        return List.copyOf(activeData().scenarios);
    }

    @Override
    public List<AssistantDto> listAssistants() {
        return List.copyOf(activeData().assistants);
    }

    @Override
    public List<AgentDto> listAgents() {
        return List.copyOf(activeData().agents);
    }

    @Override
    public List<PlaybookDto> listPlaybooks() {
        return List.copyOf(activeData().playbooks);
    }

    @Override
    public List<ResourceDto> listResources() {
        return List.copyOf(activeData().resources);
    }

    @Override
    public List<StoredResourceVersion> listResourceVersions(String resourceId) {
        return List.copyOf(activeData().resourceVersions.getOrDefault(resourceId, List.of()));
    }

    @Override
    public List<AssistantReleaseDto> listAssistantReleases(String assistantId) {
        return List.copyOf(activeData().assistantReleases.getOrDefault(assistantId, List.of()));
    }

    @Override
    public java.util.Optional<AssistantReleaseDto> findAssistantReleaseById(String releaseId) {
        return activeData().assistantReleases.values().stream()
            .flatMap(List::stream)
            .filter(item -> item.id().equals(releaseId))
            .findFirst();
    }

    @Override
    public void upsertDomain(BusinessDomainDto domain) {
        upsert(activeData().domains, domain, BusinessDomainDto::id);
    }

    @Override
    public void deleteDomain(String domainId) {
        activeData().domains.removeIf(item -> item.id().equals(domainId));
    }

    @Override
    public void upsertScenario(ScenarioDto scenario) {
        upsert(activeData().scenarios, scenario, ScenarioDto::id);
    }

    @Override
    public void deleteScenario(String scenarioId) {
        activeData().scenarios.removeIf(item -> item.id().equals(scenarioId));
    }

    @Override
    public void upsertAssistant(AssistantDto assistant) {
        upsert(activeData().assistants, assistant, AssistantDto::id);
    }

    @Override
    public void deleteAssistant(String assistantId) {
        activeData().assistants.removeIf(item -> item.id().equals(assistantId));
    }

    @Override
    public void upsertAgent(AgentDto agent) {
        upsert(activeData().agents, agent, AgentDto::id);
    }

    @Override
    public void deleteAgent(String agentId) {
        activeData().agents.removeIf(item -> item.id().equals(agentId));
    }

    @Override
    public void upsertPlaybook(PlaybookDto playbook) {
        upsert(activeData().playbooks, playbook, PlaybookDto::id);
    }

    @Override
    public void deletePlaybook(String playbookId) {
        activeData().playbooks.removeIf(item -> item.id().equals(playbookId));
    }

    @Override
    public void upsertResource(ResourceDto resource) {
        upsert(activeData().resources, resource, ResourceDto::id);
    }

    @Override
    public void deleteResource(String resourceId) {
        activeData().resources.removeIf(item -> item.id().equals(resourceId));
    }

    @Override
    public void replaceResourceVersions(String resourceId, List<StoredResourceVersion> versions) {
        activeData().resourceVersions.put(resourceId, new ArrayList<>(versions));
    }

    @Override
    public void deleteResourceVersions(String resourceId) {
        activeData().resourceVersions.remove(resourceId);
    }

    @Override
    public void replaceAssistantReleases(String assistantId, List<AssistantReleaseDto> releases) {
        activeData().assistantReleases.put(assistantId, new ArrayList<>(releases));
    }

    @Override
    public void deleteAssistantReleases(String assistantId) {
        activeData().assistantReleases.remove(assistantId);
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
