package com.lynxus.platform.catalog;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.platform.catalog.CatalogRepository.KnowledgeBindingRef;
import com.lynxus.platform.catalog.CatalogRepository.ReleaseKnowledgeRef;
import com.lynxus.platform.catalog.CatalogRepository.ReleaseResourceRef;
import com.lynxus.platform.catalog.CatalogRepository.ResourceBindingRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class ObjectReferenceAnalyzer {
    private ObjectReferenceAnalyzer() {
    }

    public static ObjectReferenceAnalysisDto analyzeDomain(
        BusinessDomainDto domain,
        List<ScenarioDto> scenarios,
        List<ResourceDto> resources,
        List<KnowledgeBaseDto> knowledgeBases
    ) {
        List<ObjectReferenceRelationDto> relations = new ArrayList<>();
        scenarios.stream()
            .filter(item -> item.domainId().equals(domain.id()))
            .sorted(Comparator.comparing(ScenarioDto::name))
            .forEach(item -> relations.add(relation(
                "DOMAIN_SCENARIO",
                "CONTAINS",
                "DIRECT",
                "BLOCKS_DELETION",
                "SCENARIO",
                item.id(),
                item.name()
            )));
        resources.stream()
            .filter(item -> item.domainId().equals(domain.id()))
            .sorted(Comparator.comparing(ResourceDto::name))
            .forEach(item -> relations.add(relation(
                "DOMAIN_RESOURCE",
                "CONTAINS",
                "DIRECT",
                "BLOCKS_DELETION",
                "RESOURCE",
                item.id(),
                item.name()
            )));
        knowledgeBases.stream()
            .filter(item -> item.domainId().equals(domain.id()))
            .sorted(Comparator.comparing(KnowledgeBaseDto::name))
            .forEach(item -> relations.add(relation(
                "DOMAIN_KNOWLEDGE_BASE",
                "CONTAINS",
                "DIRECT",
                "BLOCKS_DELETION",
                "KNOWLEDGE_BASE",
                item.id(),
                item.name()
            )));
        return analysis("DOMAIN", domain.id(), domain.name(), relations);
    }

    public static ObjectReferenceAnalysisDto analyzeScenario(ScenarioDto scenario, List<AssistantDto> assistants) {
        List<ObjectReferenceRelationDto> relations = assistants.stream()
            .filter(item -> item.scenarioId().equals(scenario.id()))
            .sorted(Comparator.comparing(AssistantDto::name))
            .map(item -> relation(
                "SCENARIO_ASSISTANT",
                "CONTAINS",
                "DIRECT",
                "BLOCKS_DELETION",
                "ASSISTANT",
                item.id(),
                item.name()
            ))
            .toList();
        return analysis("SCENARIO", scenario.id(), scenario.name(), relations);
    }

    public static ObjectReferenceAnalysisDto analyzeAssistant(
        AssistantDto assistant,
        List<AgentDto> agents,
        List<PlaybookDto> playbooks,
        List<ResourceDto> resources,
        List<KnowledgeBaseDto> knowledgeBases,
        List<AssistantReleaseDto> releases
    ) {
        List<ObjectReferenceRelationDto> relations = new ArrayList<>();
        agents.stream()
            .filter(item -> item.assistantId().equals(assistant.id()))
            .sorted(Comparator.comparing(AgentDto::name))
            .forEach(item -> relations.add(relation(
                "ASSISTANT_AGENT",
                "CONTAINS",
                "DIRECT",
                "BLOCKS_DELETION",
                "AGENT",
                item.id(),
                item.name()
            )));
        playbooks.stream()
            .filter(item -> item.assistantId().equals(assistant.id()))
            .sorted(Comparator.comparing(PlaybookDto::name))
            .forEach(item -> relations.add(relation(
                "ASSISTANT_PLAYBOOK",
                "CONTAINS",
                "DIRECT",
                "BLOCKS_DELETION",
                "PLAYBOOK",
                item.id(),
                item.name()
            )));
        resources.stream()
            .filter(item -> "ASSISTANT".equals(item.ownerType()) && assistant.id().equals(item.ownerId()))
            .sorted(Comparator.comparing(ResourceDto::name))
            .forEach(item -> relations.add(relation(
                "ASSISTANT_PRIVATE_RESOURCE",
                "OWNS",
                "DIRECT",
                "BLOCKS_DELETION",
                "RESOURCE",
                item.id(),
                item.name()
            )));
        knowledgeBases.stream()
            .filter(item -> "ASSISTANT".equals(item.ownerType()) && assistant.id().equals(item.ownerId()))
            .sorted(Comparator.comparing(KnowledgeBaseDto::name))
            .forEach(item -> relations.add(relation(
                "ASSISTANT_PRIVATE_KNOWLEDGE_BASE",
                "OWNS",
                "DIRECT",
                "BLOCKS_DELETION",
                "KNOWLEDGE_BASE",
                item.id(),
                item.name()
            )));
        if (assistant.privacyModelResourceId() != null) {
            ResourceDto resource = findById(resources, assistant.privacyModelResourceId(), ResourceDto::id);
            if (resource != null) {
                relations.add(relation(
                    "ASSISTANT_PRIVACY_MODEL",
                    "ACTIVE_BINDING",
                    "DIRECT",
                    "ADVISORY",
                    "RESOURCE",
                    resource.id(),
                    resource.name()
                ));
            }
        }
        releases.stream()
            .sorted(Comparator.comparing(AssistantReleaseDto::createdAt).reversed())
            .forEach(release -> relations.add(relation(
                "ASSISTANT_RELEASE",
                "RELEASE_SNAPSHOT",
                "DIRECT",
                "ADVISORY",
                "ASSISTANT_RELEASE",
                release.id(),
                assistant.name() + "@" + release.releaseVersion(),
                release.id(),
                release.releaseVersion(),
                null,
                null,
                null,
                null
            )));
        return analysis("ASSISTANT", assistant.id(), assistant.name(), relations);
    }

    public static ObjectReferenceAnalysisDto analyzePlaybook(
        PlaybookDto playbook,
        String assistantName,
        List<AgentDto> agents,
        List<AssistantReleaseDto> releases
    ) {
        List<ObjectReferenceRelationDto> relations = new ArrayList<>();
        relations.add(relation(
            "PLAYBOOK_ASSISTANT",
            "BELONGS_TO",
            "DIRECT",
            "ADVISORY",
            "ASSISTANT",
            playbook.assistantId(),
            assistantName
        ));
        agents.stream()
            .filter(agent -> agent.assistantId().equals(playbook.assistantId()) && agent.playbookIds().contains(playbook.id()))
            .sorted(Comparator.comparing(AgentDto::name))
            .forEach(agent -> relations.add(relation(
                "PLAYBOOK_AGENT_ENABLED",
                "ACTIVE_BINDING",
                "DIRECT",
                "BLOCKS_DELETION",
                "AGENT",
                agent.id(),
                agent.name()
            )));
        releases.stream()
            .filter(release -> release.playbooks().stream().anyMatch(item -> item.id().equals(playbook.id())))
            .sorted(Comparator.comparing(AssistantReleaseDto::createdAt).reversed())
            .forEach(release -> relations.add(relation(
                "PLAYBOOK_RELEASE_FROZEN",
                "RELEASE_SNAPSHOT",
                "INDIRECT",
                "ADVISORY",
                "ASSISTANT_RELEASE",
                release.id(),
                assistantName + "@" + release.releaseVersion(),
                release.id(),
                release.releaseVersion(),
                null,
                null,
                null,
                null
            )));
        return analysis("PLAYBOOK", playbook.id(), playbook.name(), relations);
    }

    public static ObjectReferenceAnalysisDto analyzeAgent(
        AgentDto agent,
        String assistantName,
        List<ResourceDto> resources,
        List<KnowledgeBaseDto> knowledgeBases,
        List<AssistantReleaseDto> releases
    ) {
        List<ObjectReferenceRelationDto> relations = new ArrayList<>();
        if (agent.executionPolicy() != null && agent.executionPolicy().modelResourceId() != null) {
            ResourceDto resource = findById(resources, agent.executionPolicy().modelResourceId(), ResourceDto::id);
            if (resource != null) {
                relations.add(relation(
                    "AGENT_OVERRIDE_MODEL",
                    "ACTIVE_BINDING",
                    "DIRECT",
                    "ADVISORY",
                    "RESOURCE",
                    resource.id(),
                    resource.name()
                ));
            }
        }
        if (agent.executionPolicy() != null && agent.executionPolicy().privacyModelResourceId() != null) {
            ResourceDto resource = findById(resources, agent.executionPolicy().privacyModelResourceId(), ResourceDto::id);
            if (resource != null) {
                relations.add(relation(
                    "AGENT_PRIVACY_MODEL_OVERRIDE",
                    "ACTIVE_BINDING",
                    "DIRECT",
                    "ADVISORY",
                    "RESOURCE",
                    resource.id(),
                    resource.name()
                ));
            }
        }
        if (agent.executionPolicy() != null) {
            for (String skillResourceId : agent.executionPolicy().skillResourceIds()) {
                ResourceDto resource = findById(resources, skillResourceId, ResourceDto::id);
                if (resource != null) {
                    relations.add(relation(
                        "AGENT_SKILL_ENABLED",
                        "ACTIVE_BINDING",
                        "DIRECT",
                        "ADVISORY",
                        "RESOURCE",
                        resource.id(),
                        resource.name()
                    ));
                }
            }
            for (String toolResourceId : agent.executionPolicy().toolResourceIds()) {
                ResourceDto resource = findById(resources, toolResourceId, ResourceDto::id);
                if (resource != null) {
                    relations.add(relation(
                        "AGENT_TOOL_ENABLED",
                        "ACTIVE_BINDING",
                        "DIRECT",
                        "ADVISORY",
                        "RESOURCE",
                        resource.id(),
                        resource.name()
                    ));
                }
            }
            if (agent.executionPolicy().knowledgeEnabled()
                && !agent.executionPolicy().inheritAssistantKnowledge()
                && agent.executionPolicy().knowledgeBaseId() != null) {
                KnowledgeBaseDto knowledgeBase = findById(knowledgeBases, agent.executionPolicy().knowledgeBaseId(), KnowledgeBaseDto::id);
                if (knowledgeBase != null) {
                    relations.add(relation(
                        "AGENT_OVERRIDE_KNOWLEDGE_BASE",
                        "ACTIVE_BINDING",
                        "DIRECT",
                        "ADVISORY",
                        "KNOWLEDGE_BASE",
                        knowledgeBase.id(),
                        knowledgeBase.name()
                    ));
                }
            }
        }
        releases.stream()
            .filter(release -> release.agents().stream().anyMatch(item -> item.agentId().equals(agent.id())))
            .sorted(Comparator.comparing(AssistantReleaseDto::createdAt).reversed())
            .forEach(release -> relations.add(relation(
                "AGENT_RELEASE_FROZEN",
                "RELEASE_SNAPSHOT",
                "INDIRECT",
                "ADVISORY",
                "ASSISTANT_RELEASE",
                release.id(),
                assistantName + "@" + release.releaseVersion(),
                release.id(),
                release.releaseVersion(),
                null,
                null,
                null,
                null
            )));
        return analysis("AGENT", agent.id(), agent.name(), relations);
    }

    public static ObjectReferenceAnalysisDto analyzeResource(
        ResourceDto resource,
        List<ResourceBindingRef> bindingRefs,
        List<ReleaseResourceRef> releaseRefs,
        Function<String, String> sourceNameResolver,
        Function<ReleaseResourceRef, String> releaseNameResolver
    ) {
        List<ObjectReferenceRelationDto> relations = new ArrayList<>();
        bindingRefs.stream()
            .sorted(Comparator.comparing(ResourceBindingRef::bindingKind).thenComparing(ref -> sourceNameResolver.apply(ref.sourceType() + ":" + ref.sourceId())))
            .forEach(ref -> relations.add(relation(
                ref.bindingKind(),
                "ACTIVE_BINDING",
                "DIRECT",
                "BLOCKS_DELETION",
                ref.sourceType(),
                ref.sourceId(),
                sourceNameResolver.apply(ref.sourceType() + ":" + ref.sourceId())
            )));
        releaseRefs.stream()
            .sorted(Comparator.comparing(releaseNameResolver))
            .forEach(ref -> relations.add(relation(
                "RELEASE_FROZEN",
                "RELEASE_SNAPSHOT",
                "INDIRECT",
                "ADVISORY",
                "ASSISTANT_RELEASE",
                ref.releaseId(),
                releaseNameResolver.apply(ref),
                ref.releaseId(),
                null,
                ref.resourceVersionId(),
                ref.resourceVersion(),
                null,
                null
            )));
        return analysis("RESOURCE", resource.id(), resource.name(), relations);
    }

    public static ObjectReferenceAnalysisDto analyzeKnowledgeBase(
        KnowledgeBaseDto knowledgeBase,
        List<KnowledgeBindingRef> bindingRefs,
        List<ReleaseKnowledgeRef> releaseRefs,
        Function<String, String> sourceNameResolver,
        Function<String, String> assistantReleaseNameResolver,
        Function<String, AssistantReleaseDto> assistantReleaseResolver
    ) {
        List<ObjectReferenceRelationDto> relations = new ArrayList<>();
        if (knowledgeBase.effectiveRelease() != null) {
            KnowledgeReleaseDto effectiveRelease = knowledgeBase.effectiveRelease();
            relations.add(relation(
                "KNOWLEDGE_BASE_EFFECTIVE_RELEASE",
                "LIFECYCLE",
                "DIRECT",
                "BLOCKS_DELETION",
                "KNOWLEDGE_RELEASE",
                effectiveRelease.id(),
                knowledgeBase.name() + "@" + effectiveRelease.version(),
                null,
                null,
                null,
                null,
                effectiveRelease.id(),
                effectiveRelease.version()
            ));
        }
        bindingRefs.stream()
            .sorted(Comparator.comparing(KnowledgeBindingRef::bindingKind).thenComparing(ref -> sourceNameResolver.apply(ref.sourceType() + ":" + ref.sourceId())))
            .forEach(ref -> relations.add(relation(
                ref.bindingKind(),
                "ACTIVE_BINDING",
                "DIRECT",
                "BLOCKS_DELETION",
                ref.sourceType(),
                ref.sourceId(),
                sourceNameResolver.apply(ref.sourceType() + ":" + ref.sourceId()),
                null,
                null,
                null,
                null,
                null,
                null
            )));
        releaseRefs.stream()
            .sorted(Comparator.comparing(ref -> assistantReleaseNameResolver.apply(ref.releaseId())))
            .forEach(ref -> {
                AssistantReleaseDto release = assistantReleaseResolver.apply(ref.releaseId());
                if (release == null) {
                    return;
                }
                if (release.assistantKnowledgeBinding() != null && knowledgeBase.id().equals(release.assistantKnowledgeBinding().knowledgeBaseId())) {
                    relations.add(relation(
                        "RELEASE_ASSISTANT_KNOWLEDGE",
                        "RELEASE_SNAPSHOT",
                        "INDIRECT",
                        "BLOCKS_DELETION",
                        "ASSISTANT_RELEASE",
                        release.id(),
                        assistantReleaseNameResolver.apply(release.id()),
                        release.id(),
                        release.releaseVersion(),
                        null,
                        null,
                        release.assistantKnowledgeBinding().knowledgeReleaseId(),
                        release.assistantKnowledgeBinding().knowledgeReleaseVersion()
                    ));
                }
                release.agents().stream()
                    .filter(item -> item.knowledgeBinding() != null && knowledgeBase.id().equals(item.knowledgeBinding().knowledgeBaseId()))
                    .forEach(item -> relations.add(relation(
                        "RELEASE_AGENT_KNOWLEDGE",
                        "RELEASE_SNAPSHOT",
                        "INDIRECT",
                        "BLOCKS_DELETION",
                        "ASSISTANT_RELEASE_AGENT",
                        item.agentId(),
                        assistantReleaseNameResolver.apply(release.id()) + " / " + item.name(),
                        release.id(),
                        release.releaseVersion(),
                        null,
                        null,
                        item.knowledgeBinding().knowledgeReleaseId(),
                        item.knowledgeBinding().knowledgeReleaseVersion()
                    )));
            });
        return analysis("KNOWLEDGE_BASE", knowledgeBase.id(), knowledgeBase.name(), relations);
    }

    public static ObjectReferenceAnalysisDto analysis(String objectType, String objectId, String objectName, List<ObjectReferenceRelationDto> relations) {
        return new ObjectReferenceAnalysisDto(objectType, objectId, objectName, sortRelations(relations));
    }

    public static ObjectReferenceRelationDto relation(
        String relationKind,
        String relationRole,
        String relationMode,
        String impactLevel,
        String targetType,
        String targetId,
        String targetName
    ) {
        return relation(
            relationKind,
            relationRole,
            relationMode,
            impactLevel,
            targetType,
            targetId,
            targetName,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static ObjectReferenceRelationDto relation(
        String relationKind,
        String relationRole,
        String relationMode,
        String impactLevel,
        String targetType,
        String targetId,
        String targetName,
        String releaseId,
        String releaseVersion,
        String resourceVersionId,
        String resourceVersion,
        String knowledgeReleaseId,
        String knowledgeReleaseVersion
    ) {
        return new ObjectReferenceRelationDto(
            relationKind,
            relationRole,
            relationMode,
            impactLevel,
            targetType,
            targetId,
            targetName,
            releaseId,
            releaseVersion,
            resourceVersionId,
            resourceVersion,
            knowledgeReleaseId,
            knowledgeReleaseVersion
        );
    }

    private static List<ObjectReferenceRelationDto> sortRelations(List<ObjectReferenceRelationDto> relations) {
        return relations.stream()
            .sorted(Comparator
                .comparing((ObjectReferenceRelationDto relation) -> !"BLOCKS_DELETION".equals(relation.impactLevel()))
                .thenComparing(ObjectReferenceRelationDto::relationMode)
                .thenComparing(ObjectReferenceRelationDto::relationKind)
                .thenComparing(ObjectReferenceRelationDto::targetType)
                .thenComparing(ObjectReferenceRelationDto::targetName))
            .toList();
    }

    private static <T> T findById(List<T> items, String id, Function<T, String> idGetter) {
        return items.stream()
            .filter(item -> id.equals(idGetter.apply(item)))
            .findFirst()
            .orElse(null);
    }
}
