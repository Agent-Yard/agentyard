package com.agentyard.platform.catalog;

import static com.agentyard.platform.catalog.CatalogDtos.*;
import com.agentyard.contracts.runtime.WorkflowContracts.ShareScope;
import com.agentyard.contracts.task.TaskContracts.Permission;
import com.agentyard.platform.auth.AuthModels.Role;
import com.agentyard.platform.auth.CurrentUserResolver;
import com.agentyard.platform.task.CollaborationService;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Human configuration access. Runtime tool authorization is a separate, live boundary. */
@Component("catalogAccess")
public class CatalogAccess {
    private final CurrentUserResolver users;
    private final CollaborationService collaboration;
    private final CatalogService catalog;
    public CatalogAccess(CurrentUserResolver users, CollaborationService collaboration, CatalogService catalog) {
        this.users = users; this.collaboration = collaboration; this.catalog = catalog;
    }
    public boolean isPlatformAdmin() { var user = users.resolveCurrentUser(); return user.isActive() && user.roles().contains(Role.PLATFORM_ADMIN); }
    private record Ownership(String type, String id, ShareScope shareScope) {}
    private Ownership ownership(Object object) {
        return switch (object) {
            case RoleTemplateDto x -> new Ownership(x.ownerType(), x.ownerId(), ShareScope.PRIVATE);
            case CreateRoleTemplateRequest x -> new Ownership(x.ownerType(), x.ownerId(), ShareScope.PRIVATE);
            case UpdateRoleTemplateRequest x -> new Ownership(x.ownerType(), x.ownerId(), ShareScope.PRIVATE);
            case EntryDto x -> new Ownership(x.ownerType(), x.ownerId(), x.shareScope());
            case EntryRequest x -> new Ownership(x.ownerType(), x.ownerId(), x.shareScope());
            case PlaybookDto x -> new Ownership(x.ownerType(), x.ownerId(), ShareScope.PRIVATE);
            case CreatePlaybookRequest x -> new Ownership(x.ownerType(), x.ownerId(), ShareScope.PRIVATE);
            case UpdatePlaybookRequest x -> new Ownership(x.ownerType(), x.ownerId(), ShareScope.PRIVATE);
            case ResourceDto x -> new Ownership(x.ownerType(), x.ownerId(), x.shareScope());
            case CreateResourceRequest x -> new Ownership(x.ownerType(), x.ownerId(), x.shareScope());
            case UpdateResourceRequest x -> new Ownership(x.ownerType(), x.ownerId(), x.shareScope());
            case KnowledgeBaseDto x -> new Ownership(x.ownerType(), x.ownerId(), x.shareScope());
            case CreateKnowledgeBaseRequest x -> new Ownership(x.ownerType(), x.ownerId(), x.shareScope());
            case UpdateKnowledgeBaseRequest x -> new Ownership(x.ownerType(), x.ownerId(), x.shareScope());
            default -> throw new IllegalArgumentException("unsupported owned configuration");
        };
    }
    private boolean allowed(Object object, boolean write) {
        var user = users.resolveCurrentUser();
        if (!user.isActive()) return false;
        if (isPlatformAdmin()) return true;
        var owner = ownership(object);
        if ("PERSON".equals(owner.type())) return user.id().equals(owner.id());
        if ("PLATFORM".equals(owner.type())) return !write && owner.shareScope() == ShareScope.SHARED;
        if ("ORGANIZATION".equals(owner.type())) {
            try { return collaboration.organization(owner.id()).permissions().contains(write ? Permission.MANAGE : Permission.VIEW); }
            catch (NoSuchElementException | AccessDeniedException error) { return false; }
            catch (org.springframework.web.server.ResponseStatusException error) {
                if (error.getStatusCode().value() == 403 || error.getStatusCode().value() == 404) return false;
                throw error;
            }
        }
        return false;
    }
    public <T> T read(T value) {
        if (!allowed(value, false)) throw new AccessDeniedException("configuration is outside your ownership scope");
        return value;
    }
    public void write(Object value) {
        if (!allowed(value, true)) throw new AccessDeniedException("configuration is outside your management scope");
    }
    public <T> List<T> visible(List<T> values) { return values.stream().filter(value -> allowed(value, false)).toList(); }
    public void roleReferences(RoleConfigurationDto config) {
        if (config == null) return;
        var ids = new java.util.ArrayList<String>();
        ids.add(config.modelVersionId()); ids.add(config.privacyModelVersionId());
        if (config.skillVersionIds() != null) ids.addAll(config.skillVersionIds());
        if (config.toolVersionIds() != null) ids.addAll(config.toolVersionIds());
        ids.stream().filter(id -> id != null && !id.isBlank()).forEach(id -> read(catalog.getResource(catalog.getResourceVersion(id).resourceId())));
        if (config.playbookVersionIds() != null) config.playbookVersionIds().forEach(id -> read(catalog.getPlaybook(catalog.getPlaybookVersion(id).playbookId())));
        if (config.knowledgeReleaseId() != null && !config.knowledgeReleaseId().isBlank()) {
            var base = catalog.knowledgeService().listKnowledgeBases().stream()
                .filter(k -> k.releases().stream().anyMatch(r -> r.id().equals(config.knowledgeReleaseId())))
                .findFirst().orElseThrow(() -> new NoSuchElementException("knowledge release not found"));
            read(base);
        }
    }
    public void playbookReferences(PlaybookDefinitionDto definition) {
        if (definition == null || definition.nodes() == null) return;
        definition.nodes().stream().map(PlaybookNodeDto::toolId).filter(id -> id != null && !id.isBlank()).forEach(id -> read(catalog.getResource(id)));
    }
    public CatalogSummaryDto summary() {
        var all = catalog.summary();
        var resources = visible(all.resources());
        var ids = resources.stream().map(ResourceDto::id).collect(java.util.stream.Collectors.toSet());
        var center = new ResourceCenterDto(resources.size(), (int) resources.stream().filter(r -> r.shareScope() == ShareScope.SHARED).count(),
            (int) resources.stream().filter(r -> r.shareScope() == ShareScope.PRIVATE).count(), all.resourceCenter().references().stream().filter(r -> ids.contains(r.resourceId())).map(this::resourceReference).toList());
        return new CatalogSummaryDto(visible(all.roleTemplates()), visible(all.entries()), visible(all.playbooks()), resources,
            visible(all.knowledgeBases()), center, all.resourceBlueprints());
    }
    private boolean canReadReference(String type,String id) {
        try {
            Object value = switch(type) {
                case "ROLE_TEMPLATE" -> catalog.getRoleTemplate(id);
                case "ROLE_TEMPLATE_VERSION" -> catalog.getRoleTemplate(catalog.getRoleTemplateVersion(id).templateId());
                case "ENTRY" -> catalog.getEntry(id);
                case "PLAYBOOK" -> catalog.getPlaybook(id);
                case "PLAYBOOK_VERSION" -> catalog.getPlaybook(catalog.getPlaybookVersion(id).playbookId());
                case "RESOURCE" -> catalog.getResource(id);
                case "RESOURCE_VERSION" -> catalog.getResource(catalog.getResourceVersion(id).resourceId());
                case "KNOWLEDGE_BASE" -> catalog.knowledgeService().getKnowledgeBase(id);
                case "KNOWLEDGE_RELEASE" -> catalog.knowledgeService().listKnowledgeBases().stream().filter(base -> base.releases().stream().anyMatch(v -> v.id().equals(id))).findFirst().orElseThrow();
                default -> null;
            };
            return value != null && allowed(value,false);
        } catch(NoSuchElementException | AccessDeniedException error) { return false; }
    }
    private ResourceReferenceDto resourceReference(ResourceReferenceDto value) {
        if(canReadReference(value.sourceType(),value.sourceId())) return value;
        return new ResourceReferenceDto(value.resourceId(),value.resourceName(),value.type(),value.shareScope(),value.ownerLabel(),value.latestVersion(),value.effectiveVersion(),value.referenceKind(),value.sourceType(),"","受限配置",value.resourceVersionId(),value.resourceVersion(),value.blocksDeletion());
    }
    public List<KnowledgeReferenceDto> knowledgeReferences(List<KnowledgeReferenceDto> values) {
        return values.stream().map(value -> canReadReference(value.sourceType(),value.sourceId()) ? value :
            new KnowledgeReferenceDto(value.knowledgeBaseId(),value.referenceKind(),value.sourceType(),"","受限配置",value.knowledgeReleaseId(),value.knowledgeReleaseVersion(),value.blocksDeletion())).toList();
    }
    private List<ObjectReferenceRelationDto> relations(List<ObjectReferenceRelationDto> values) {
        return values.stream().map(value -> canReadReference(value.targetType(),value.targetId()) ? value :
            new ObjectReferenceRelationDto(value.relationKind(),value.relationRole(),value.relationMode(),value.impactLevel(),value.targetType(),"","受限配置",value.releaseId(),value.releaseVersion(),value.resourceVersionId(),value.resourceVersion(),value.knowledgeReleaseId(),value.knowledgeReleaseVersion())).toList();
    }
    public ObjectReferenceAnalysisDto references(String type,String id) {
        object(type,id,false); var value=catalog.objectReferences(type,id);
        return new ObjectReferenceAnalysisDto(value.objectType(),value.objectId(),value.objectName(),relations(value.relations()));
    }
    public DeletionImpactPreviewDto deletionPreview(String type,String id) {
        object(type,id,false); var value=catalog.deletionPreview(type,id);
        return new DeletionImpactPreviewDto(value.objectType(),value.objectId(),value.objectName(),value.canDelete(),relations(value.blockers()),relations(value.advisories()),value.cascadeDeletes());
    }
    public void object(String type, String id, boolean write) {
        Object value = switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case "ROLE_TEMPLATE" -> catalog.getRoleTemplate(id);
            case "ENTRY" -> catalog.getEntry(id);
            case "PLAYBOOK" -> catalog.getPlaybook(id);
            case "RESOURCE" -> catalog.getResource(id);
            case "KNOWLEDGE_BASE" -> catalog.knowledgeService().getKnowledgeBase(id);
            default -> throw new IllegalArgumentException("unsupported object type");
        };
        if (write) write(value); else read(value);
    }
}
