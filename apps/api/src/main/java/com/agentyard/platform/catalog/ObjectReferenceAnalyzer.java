package com.agentyard.platform.catalog;

import static com.agentyard.platform.catalog.CatalogDtos.*;
import com.agentyard.contracts.runtime.WorkflowContracts.VersionStatus;
import java.util.ArrayList;
import java.util.List;

/** Reference checks follow immutable version IDs, independent of reception entries. */
public final class ObjectReferenceAnalyzer {
    private ObjectReferenceAnalyzer() {}
    public static ObjectReferenceAnalysisDto analyzeResource(ResourceDto resource, CatalogRepository catalog) {
        var relations = new ArrayList<ObjectReferenceRelationDto>();
        var versions = catalog.listResourceVersions(resource.id());
        for (var template : catalog.listRoleTemplates()) {
            for (var version : catalog.listRoleTemplateVersions(template.id())) {
                var config = version.configuration();
                var ids = new ArrayList<String>();
                ids.add(config.modelVersionId()); ids.add(config.privacyModelVersionId());
                ids.addAll(config.skillVersionIds()); ids.addAll(config.toolVersionIds());
                versions.stream().filter(v -> ids.contains(v.id())).forEach(v -> relations.add(relation(
                    "ROLE_RESOURCE_VERSION", "ROLE_TEMPLATE_VERSION", version.id(), template.name() + "@" + version.version(),
                    v.id(), v.version(), null, null)));
            }
        }
        versions.stream().filter(v -> v.status() == VersionStatus.PUBLISHED).forEach(v -> relations.add(relation(
            "PUBLISHED_RESOURCE_VERSION", "RESOURCE_VERSION", v.id(), resource.name() + "@" + v.version(), v.id(), v.version(), null, null)));
        return new ObjectReferenceAnalysisDto("RESOURCE", resource.id(), resource.name(), List.copyOf(relations));
    }
    public static ObjectReferenceAnalysisDto analyzeKnowledgeBase(KnowledgeBaseDto base, CatalogRepository catalog) {
        var relations = new ArrayList<ObjectReferenceRelationDto>();
        for (var template : catalog.listRoleTemplates()) {
            for (var version : catalog.listRoleTemplateVersions(template.id())) {
                base.releases().stream().filter(release -> release.id().equals(version.configuration().knowledgeReleaseId()))
                    .forEach(release -> relations.add(relation("ROLE_KNOWLEDGE_VERSION", "ROLE_TEMPLATE_VERSION", version.id(),
                        template.name() + "@" + version.version(), null, null, release.id(), release.version())));
            }
        }
        base.releases().stream().filter(v -> v.status() == VersionStatus.PUBLISHED).forEach(v -> relations.add(relation(
            "PUBLISHED_KNOWLEDGE_RELEASE", "KNOWLEDGE_RELEASE", v.id(), base.name() + "@" + v.version(), null, null, v.id(), v.version())));
        return new ObjectReferenceAnalysisDto("KNOWLEDGE_BASE", base.id(), base.name(), List.copyOf(relations));
    }
    public static ObjectReferenceRelationDto relation(String kind, String targetType, String targetId, String name,
        String resourceVersionId, String resourceVersion, String knowledgeReleaseId, String knowledgeReleaseVersion) {
        return new ObjectReferenceRelationDto(kind, "VERSION_REFERENCE", "DIRECT", "BLOCKS_DELETION", targetType, targetId,
            name, null, null, resourceVersionId, resourceVersion, knowledgeReleaseId, knowledgeReleaseVersion);
    }
}
