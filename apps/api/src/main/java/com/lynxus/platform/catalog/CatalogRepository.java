package com.lynxus.platform.catalog;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import java.util.List;
import java.util.Map;

public interface CatalogRepository {
    CatalogSnapshot load();

    void save(CatalogSnapshot snapshot);

    boolean isEmpty();

    // --- Reference projection queries ---

    record ResourceBindingRef(String sourceType, String sourceId, String resourceId, String bindingKind) {}
    record ReleaseResourceRef(String releaseId, String assistantId, String resourceId, String resourceVersionId, String resourceVersion) {}
    record KnowledgeBindingRef(String sourceType, String sourceId, String knowledgeBaseId, String bindingKind) {}
    record ReleaseKnowledgeRef(String releaseId, String assistantId, String knowledgeBaseId, String knowledgeReleaseId) {}

    /** Active resource bindings for a given resource. */
    default List<ResourceBindingRef> findResourceBindings(String resourceId) {
        CatalogSnapshot snapshot = load();
        List<ResourceBindingRef> refs = new java.util.ArrayList<>();
        for (AssistantDto a : snapshot.assistants()) {
            if (a.modelPolicy() != null && resourceId.equals(a.modelPolicy().defaultModelResourceId())) {
                refs.add(new ResourceBindingRef("ASSISTANT", a.id(), resourceId, "ASSISTANT_DEFAULT_MODEL"));
            }
            if (resourceId.equals(a.privacyModelResourceId())) {
                refs.add(new ResourceBindingRef("ASSISTANT", a.id(), resourceId, "ASSISTANT_PRIVACY_MODEL"));
            }
        }
        for (AgentDto a : snapshot.agents()) {
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
        CatalogSnapshot snapshot = load();
        List<ReleaseResourceRef> refs = new java.util.ArrayList<>();
        for (Map.Entry<String, List<AssistantReleaseDto>> entry : snapshot.assistantReleases().entrySet()) {
            for (AssistantReleaseDto release : entry.getValue()) {
                if (release.resources() == null) continue;
                for (AssistantReleaseResourceDto res : release.resources()) {
                    if (resourceId.equals(res.resourceId())) {
                        refs.add(new ReleaseResourceRef(release.id(), entry.getKey(), res.resourceId(), res.resourceVersionId(), res.resourceVersion()));
                    }
                }
            }
        }
        return refs;
    }

    /** Active knowledge base bindings for a given knowledge base. */
    default List<KnowledgeBindingRef> findKnowledgeBindings(String knowledgeBaseId) {
        CatalogSnapshot snapshot = load();
        List<KnowledgeBindingRef> refs = new java.util.ArrayList<>();
        for (AssistantDto a : snapshot.assistants()) {
            if (a.knowledgeAccessPolicy() != null && a.knowledgeAccessPolicy().enabled() && knowledgeBaseId.equals(a.knowledgeAccessPolicy().knowledgeBaseId())) {
                refs.add(new KnowledgeBindingRef("ASSISTANT", a.id(), knowledgeBaseId, "ASSISTANT_DEFAULT_KNOWLEDGE_BASE"));
            }
        }
        for (AgentDto a : snapshot.agents()) {
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
        CatalogSnapshot snapshot = load();
        List<ReleaseKnowledgeRef> refs = new java.util.ArrayList<>();
        for (Map.Entry<String, List<AssistantReleaseDto>> entry : snapshot.assistantReleases().entrySet()) {
            for (AssistantReleaseDto release : entry.getValue()) {
                if (release.assistantKnowledgeBinding() != null && knowledgeBaseId.equals(release.assistantKnowledgeBinding().knowledgeBaseId())) {
                    refs.add(new ReleaseKnowledgeRef(release.id(), entry.getKey(), knowledgeBaseId, release.assistantKnowledgeBinding().knowledgeReleaseId()));
                }
                if (release.agents() != null) {
                    for (AssistantReleaseAgentDto agent : release.agents()) {
                        if (agent.knowledgeBinding() != null && knowledgeBaseId.equals(agent.knowledgeBinding().knowledgeBaseId())) {
                            refs.add(new ReleaseKnowledgeRef(release.id(), entry.getKey(), knowledgeBaseId, agent.knowledgeBinding().knowledgeReleaseId()));
                        }
                    }
                }
            }
        }
        return refs;
    }

    // --- All bindings (for resourceCenter / bulk queries) ---

    default List<ResourceBindingRef> findAllResourceBindings() {
        CatalogSnapshot snapshot = load();
        List<ResourceBindingRef> refs = new java.util.ArrayList<>();
        for (AssistantDto a : snapshot.assistants()) {
            if (a.modelPolicy() != null && a.modelPolicy().defaultModelResourceId() != null) {
                refs.add(new ResourceBindingRef("ASSISTANT", a.id(), a.modelPolicy().defaultModelResourceId(), "ASSISTANT_DEFAULT_MODEL"));
            }
            if (a.privacyModelResourceId() != null) {
                refs.add(new ResourceBindingRef("ASSISTANT", a.id(), a.privacyModelResourceId(), "ASSISTANT_PRIVACY_MODEL"));
            }
        }
        for (AgentDto a : snapshot.agents()) {
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
        CatalogSnapshot snapshot = load();
        List<ReleaseResourceRef> refs = new java.util.ArrayList<>();
        for (Map.Entry<String, List<AssistantReleaseDto>> entry : snapshot.assistantReleases().entrySet()) {
            for (AssistantReleaseDto release : entry.getValue()) {
                if (release.resources() == null) continue;
                for (AssistantReleaseResourceDto res : release.resources()) {
                    refs.add(new ReleaseResourceRef(release.id(), entry.getKey(), res.resourceId(), res.resourceVersionId(), res.resourceVersion()));
                }
            }
        }
        return refs;
    }

    record CatalogSnapshot(
        List<BusinessDomainDto> domains,
        List<ScenarioDto> scenarios,
        List<AssistantDto> assistants,
        List<AgentDto> agents,
        List<PlaybookDto> playbooks,
        List<ResourceDto> resources,
        Map<String, List<StoredResourceVersion>> resourceVersions,
        Map<String, List<AssistantReleaseDto>> assistantReleases
    ) {
        public static CatalogSnapshot empty() {
            return new CatalogSnapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of()
            );
        }
    }
}
