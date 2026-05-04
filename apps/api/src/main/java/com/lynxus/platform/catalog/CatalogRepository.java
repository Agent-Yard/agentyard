package com.lynxus.platform.catalog;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public interface CatalogRepository {
    long revision();

    default <T> T inReadTransaction(Supplier<T> action) {
        return action.get();
    }

    default <T> T inWriteTransaction(Supplier<T> action) {
        return action.get();
    }

    void upsertDomain(BusinessDomainDto domain);

    void deleteDomain(String domainId);

    void upsertScenario(ScenarioDto scenario);

    void deleteScenario(String scenarioId);

    void upsertAssistant(AssistantDto assistant);

    void deleteAssistant(String assistantId);

    void upsertAgent(AgentDto agent);

    void deleteAgent(String agentId);

    void upsertPlaybook(PlaybookDto playbook);

    void deletePlaybook(String playbookId);

    void upsertResource(ResourceDto resource);

    void deleteResource(String resourceId);

    void replaceResourceVersions(String resourceId, List<StoredResourceVersion> versions);

    void deleteResourceVersions(String resourceId);

    void replaceAssistantReleases(String assistantId, List<AssistantReleaseDto> releases);

    void deleteAssistantReleases(String assistantId);

    List<BusinessDomainDto> listDomains();

    default Optional<BusinessDomainDto> findDomain(String domainId) {
        return listDomains().stream().filter(item -> item.id().equals(domainId)).findFirst();
    }

    List<ScenarioDto> listScenarios();

    default Optional<ScenarioDto> findScenario(String scenarioId) {
        return listScenarios().stream().filter(item -> item.id().equals(scenarioId)).findFirst();
    }

    List<AssistantDto> listAssistants();

    default Optional<AssistantDto> findAssistant(String assistantId) {
        return listAssistants().stream().filter(item -> item.id().equals(assistantId)).findFirst();
    }

    List<AgentDto> listAgents();

    default Optional<AgentDto> findAgent(String agentId) {
        return listAgents().stream().filter(item -> item.id().equals(agentId)).findFirst();
    }

    List<PlaybookDto> listPlaybooks();

    default Optional<PlaybookDto> findPlaybook(String playbookId) {
        return listPlaybooks().stream().filter(item -> item.id().equals(playbookId)).findFirst();
    }

    List<ResourceDto> listResources();

    default Optional<ResourceDto> findResource(String resourceId) {
        return listResources().stream().filter(item -> item.id().equals(resourceId)).findFirst();
    }

    List<StoredResourceVersion> listResourceVersions(String resourceId);

    List<AssistantReleaseDto> listAssistantReleases(String assistantId);

    Optional<AssistantReleaseDto> findAssistantReleaseById(String releaseId);

    // --- Reference projection queries ---

    record ResourceBindingRef(String sourceType, String sourceId, String resourceId, String bindingKind) {}
    record ReleaseResourceRef(String releaseId, String assistantId, String resourceId, String resourceVersionId, String resourceVersion) {}
    record KnowledgeBindingRef(String sourceType, String sourceId, String knowledgeBaseId, String bindingKind) {}
    record ReleaseKnowledgeRef(String releaseId, String assistantId, String knowledgeBaseId, String knowledgeReleaseId) {}

    /** Active resource bindings for a given resource. */
    default List<ResourceBindingRef> findResourceBindings(String resourceId) {
        List<ResourceBindingRef> refs = new java.util.ArrayList<>();
        for (AssistantDto a : listAssistants()) {
            if (a.modelPolicy() != null && resourceId.equals(a.modelPolicy().defaultModelResourceId())) {
                refs.add(new ResourceBindingRef("ASSISTANT", a.id(), resourceId, "ASSISTANT_DEFAULT_MODEL"));
            }
            if (resourceId.equals(a.privacyModelResourceId())) {
                refs.add(new ResourceBindingRef("ASSISTANT", a.id(), resourceId, "ASSISTANT_PRIVACY_MODEL"));
            }
        }
        for (AgentDto a : listAgents()) {
            if (a.executionPolicy() == null) continue;
            if (resourceId.equals(a.executionPolicy().modelResourceId())) {
                refs.add(new ResourceBindingRef("AGENT", a.id(), resourceId, "AGENT_OVERRIDE_MODEL"));
            }
            if (resourceId.equals(a.executionPolicy().privacyModelResourceId())) {
                refs.add(new ResourceBindingRef("AGENT", a.id(), resourceId, "AGENT_PRIVACY_MODEL_OVERRIDE"));
            }
            if (a.executionPolicy().skillResourceIds() != null && a.executionPolicy().skillResourceIds().contains(resourceId)) {
                refs.add(new ResourceBindingRef("AGENT", a.id(), resourceId, "AGENT_SKILL_ENABLED"));
            }
            if (a.executionPolicy().toolResourceIds() != null && a.executionPolicy().toolResourceIds().contains(resourceId)) {
                refs.add(new ResourceBindingRef("AGENT", a.id(), resourceId, "AGENT_TOOL_ENABLED"));
            }
        }
        return refs;
    }

    /** Release-frozen resource references for a given resource. */
    default List<ReleaseResourceRef> findReleaseResourceRefs(String resourceId) {
        List<ReleaseResourceRef> refs = new java.util.ArrayList<>();
        for (AssistantDto assistant : listAssistants()) {
            for (AssistantReleaseDto release : listAssistantReleases(assistant.id())) {
                if (release.resources() == null) continue;
                for (AssistantReleaseResourceDto res : release.resources()) {
                    if (resourceId.equals(res.resourceId())) {
                        refs.add(new ReleaseResourceRef(release.id(), assistant.id(), res.resourceId(), res.resourceVersionId(), res.resourceVersion()));
                    }
                }
            }
        }
        return refs;
    }

    /** Active knowledge base bindings for a given knowledge base. */
    default List<KnowledgeBindingRef> findKnowledgeBindings(String knowledgeBaseId) {
        List<KnowledgeBindingRef> refs = new java.util.ArrayList<>();
        for (AssistantDto a : listAssistants()) {
            if (a.knowledgeAccessPolicy() != null && a.knowledgeAccessPolicy().enabled() && knowledgeBaseId.equals(a.knowledgeAccessPolicy().knowledgeBaseId())) {
                refs.add(new KnowledgeBindingRef("ASSISTANT", a.id(), knowledgeBaseId, "ASSISTANT_DEFAULT_KNOWLEDGE_BASE"));
            }
        }
        for (AgentDto a : listAgents()) {
            if (a.executionPolicy() != null && a.executionPolicy().knowledgeEnabled()
                && !a.executionPolicy().inheritAssistantKnowledge()
                && knowledgeBaseId.equals(a.executionPolicy().knowledgeBaseId())) {
                refs.add(new KnowledgeBindingRef("AGENT", a.id(), knowledgeBaseId, "AGENT_OVERRIDE_KNOWLEDGE_BASE"));
            }
        }
        return refs;
    }

    /** Release-frozen knowledge references for a given knowledge base. */
    default List<ReleaseKnowledgeRef> findReleaseKnowledgeRefs(String knowledgeBaseId) {
        List<ReleaseKnowledgeRef> refs = new java.util.ArrayList<>();
        for (AssistantDto assistant : listAssistants()) {
            for (AssistantReleaseDto release : listAssistantReleases(assistant.id())) {
                if (release.assistantKnowledgeBinding() != null && knowledgeBaseId.equals(release.assistantKnowledgeBinding().knowledgeBaseId())) {
                    refs.add(new ReleaseKnowledgeRef(release.id(), assistant.id(), knowledgeBaseId, release.assistantKnowledgeBinding().knowledgeReleaseId()));
                }
                if (release.agents() != null) {
                    for (AssistantReleaseAgentDto agent : release.agents()) {
                        if (agent.knowledgeBinding() != null && knowledgeBaseId.equals(agent.knowledgeBinding().knowledgeBaseId())) {
                            refs.add(new ReleaseKnowledgeRef(release.id(), assistant.id(), knowledgeBaseId, agent.knowledgeBinding().knowledgeReleaseId()));
                        }
                    }
                }
            }
        }
        return refs;
    }

    // --- All bindings (for resourceCenter / bulk queries) ---

    default List<ResourceBindingRef> findAllResourceBindings() {
        List<ResourceBindingRef> refs = new java.util.ArrayList<>();
        for (AssistantDto a : listAssistants()) {
            if (a.modelPolicy() != null && a.modelPolicy().defaultModelResourceId() != null) {
                refs.add(new ResourceBindingRef("ASSISTANT", a.id(), a.modelPolicy().defaultModelResourceId(), "ASSISTANT_DEFAULT_MODEL"));
            }
            if (a.privacyModelResourceId() != null) {
                refs.add(new ResourceBindingRef("ASSISTANT", a.id(), a.privacyModelResourceId(), "ASSISTANT_PRIVACY_MODEL"));
            }
        }
        for (AgentDto a : listAgents()) {
            if (a.executionPolicy() == null) continue;
            if (a.executionPolicy().modelResourceId() != null) {
                refs.add(new ResourceBindingRef("AGENT", a.id(), a.executionPolicy().modelResourceId(), "AGENT_OVERRIDE_MODEL"));
            }
            if (a.executionPolicy().privacyModelResourceId() != null) {
                refs.add(new ResourceBindingRef("AGENT", a.id(), a.executionPolicy().privacyModelResourceId(), "AGENT_PRIVACY_MODEL_OVERRIDE"));
            }
            if (a.executionPolicy().skillResourceIds() != null) {
                for (String sid : a.executionPolicy().skillResourceIds()) {
                    refs.add(new ResourceBindingRef("AGENT", a.id(), sid, "AGENT_SKILL_ENABLED"));
                }
            }
            if (a.executionPolicy().toolResourceIds() != null) {
                for (String tid : a.executionPolicy().toolResourceIds()) {
                    refs.add(new ResourceBindingRef("AGENT", a.id(), tid, "AGENT_TOOL_ENABLED"));
                }
            }
        }
        return refs;
    }

    default List<ReleaseResourceRef> findAllReleaseResourceRefs() {
        List<ReleaseResourceRef> refs = new java.util.ArrayList<>();
        for (AssistantDto assistant : listAssistants()) {
            for (AssistantReleaseDto release : listAssistantReleases(assistant.id())) {
                if (release.resources() == null) continue;
                for (AssistantReleaseResourceDto res : release.resources()) {
                    refs.add(new ReleaseResourceRef(release.id(), assistant.id(), res.resourceId(), res.resourceVersionId(), res.resourceVersion()));
                }
            }
        }
        return refs;
    }
}
