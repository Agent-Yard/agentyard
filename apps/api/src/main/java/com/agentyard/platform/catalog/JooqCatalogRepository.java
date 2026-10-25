package com.agentyard.platform.catalog;

import static com.agentyard.platform.catalog.CatalogDtos.*;
import static com.agentyard.persistence.jooq.Tables.*;
import com.agentyard.contracts.agent.AgentContracts.GrantRequest;
import com.agentyard.contracts.runtime.WorkflowContracts.*;
import com.agentyard.persistence.jooqsupport.*;
import com.agentyard.persistence.shared.SharedStateRevisionStore;
import com.agentyard.platform.shared.ConflictException;
import java.util.*;
import java.util.function.Supplier;
import org.jooq.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JooqCatalogRepository implements CatalogRepository {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<List<GrantRequest>> GRANTS = new TypeReference<>() {};
    private final DSLContext dsl;
    private final JooqJsonbSupport json;
    private final SharedStateRevisionStore revisionStore;
    private final ThreadLocal<Boolean> writing = ThreadLocal.withInitial(() -> false);
    public JooqCatalogRepository(DSLContext dsl, ObjectMapper mapper) {
        this.dsl = dsl; this.json = new JooqJsonbSupport(mapper); this.revisionStore = new SharedStateRevisionStore(dsl);
    }
    @Override public long revision() { return revisionStore.revision("catalog"); }
    @Override @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public <T> T inReadTransaction(Supplier<T> action) { return action.get(); }
    @Override @Transactional
    public <T> T inWriteTransaction(Supplier<T> action) {
        if (writing.get()) return action.get();
        if (!revisionStore.reserveRevision("catalog", revision())) throw new ConflictException("catalog changed; retry the request");
        writing.set(true);
        try { return action.get(); } finally { writing.remove(); }
    }
    @Override public List<RoleTemplateDto> listRoleTemplates() {
        return dsl.selectFrom(CATALOG_ROLE_TEMPLATE).orderBy(CATALOG_ROLE_TEMPLATE.ID).fetch(r -> new RoleTemplateDto(
            r.getId(), r.getName(), r.getDescription(), r.getOwnerType(), r.getOwnerId(), null, null, List.of()));
    }
    @Override public void upsertRoleTemplate(RoleTemplateDto value) {
        dsl.insertInto(CATALOG_ROLE_TEMPLATE).set(CATALOG_ROLE_TEMPLATE.ID, value.id())
            .set(CATALOG_ROLE_TEMPLATE.NAME, value.name()).set(CATALOG_ROLE_TEMPLATE.DESCRIPTION, value.description())
            .set(CATALOG_ROLE_TEMPLATE.OWNER_TYPE, value.ownerType()).set(CATALOG_ROLE_TEMPLATE.OWNER_ID, value.ownerId())
            .onConflict(CATALOG_ROLE_TEMPLATE.ID).doUpdate().set(CATALOG_ROLE_TEMPLATE.NAME, value.name()).set(CATALOG_ROLE_TEMPLATE.DESCRIPTION, value.description())
            .set(CATALOG_ROLE_TEMPLATE.OWNER_TYPE, value.ownerType()).set(CATALOG_ROLE_TEMPLATE.OWNER_ID, value.ownerId()).execute();
    }
    @Override public void deleteRoleTemplate(String id) { dsl.deleteFrom(CATALOG_ROLE_TEMPLATE).where(CATALOG_ROLE_TEMPLATE.ID.eq(id)).execute(); }
    @Override public List<PlaybookDto> listPlaybooks() {
        return dsl.selectFrom(CATALOG_PLAYBOOK).orderBy(CATALOG_PLAYBOOK.ID).fetch(r -> new PlaybookDto(
            r.getId(), r.getName(), r.getDescription(), r.getOwnerType(), r.getOwnerId(), null, null, List.of()));
    }
    @Override public void upsertPlaybook(PlaybookDto value) {
        dsl.insertInto(CATALOG_PLAYBOOK).set(CATALOG_PLAYBOOK.ID, value.id())
            .set(CATALOG_PLAYBOOK.NAME, value.name()).set(CATALOG_PLAYBOOK.DESCRIPTION, value.description())
            .set(CATALOG_PLAYBOOK.OWNER_TYPE, value.ownerType()).set(CATALOG_PLAYBOOK.OWNER_ID, value.ownerId())
            .onConflict(CATALOG_PLAYBOOK.ID).doUpdate().set(CATALOG_PLAYBOOK.NAME, value.name()).set(CATALOG_PLAYBOOK.DESCRIPTION, value.description())
            .set(CATALOG_PLAYBOOK.OWNER_TYPE, value.ownerType()).set(CATALOG_PLAYBOOK.OWNER_ID, value.ownerId()).execute();
    }
    @Override public void deletePlaybook(String id) { dsl.deleteFrom(CATALOG_PLAYBOOK).where(CATALOG_PLAYBOOK.ID.eq(id)).execute(); }
    @Override public List<RoleTemplateVersionDto> listRoleTemplateVersions(String id) {
        return dsl.selectFrom(CATALOG_ROLE_TEMPLATE_VERSION).where(CATALOG_ROLE_TEMPLATE_VERSION.TEMPLATE_ID.eq(id)).orderBy(CATALOG_ROLE_TEMPLATE_VERSION.CREATED_AT, CATALOG_ROLE_TEMPLATE_VERSION.ID)
            .fetch(r -> new RoleTemplateVersionDto(r.getId(), r.getTemplateId(), r.getVersion(), VersionStatus.valueOf(r.getStatus()),
                JooqTimeSupport.toInstant(r.getCreatedAt()), JooqTimeSupport.toInstant(r.getPublishedAt()), json.read(r.getConfiguration(), RoleConfigurationDto.class)));
    }
    @Override public void replaceRoleTemplateVersions(String id, List<RoleTemplateVersionDto> versions) {
        var keep = versions.stream().map(RoleTemplateVersionDto::id).toList();
        dsl.deleteFrom(CATALOG_ROLE_TEMPLATE_VERSION).where(CATALOG_ROLE_TEMPLATE_VERSION.TEMPLATE_ID.eq(id).and(CATALOG_ROLE_TEMPLATE_VERSION.ID.notIn(keep))).execute();
        for (var value : versions) {
            dsl.insertInto(CATALOG_ROLE_TEMPLATE_VERSION).set(CATALOG_ROLE_TEMPLATE_VERSION.ID, value.id()).set(CATALOG_ROLE_TEMPLATE_VERSION.TEMPLATE_ID, id)
                .set(CATALOG_ROLE_TEMPLATE_VERSION.VERSION, value.version()).set(CATALOG_ROLE_TEMPLATE_VERSION.STATUS, value.status().name())
                .set(CATALOG_ROLE_TEMPLATE_VERSION.CREATED_AT, JooqTimeSupport.toOffsetDateTime(value.createdAt()))
                .set(CATALOG_ROLE_TEMPLATE_VERSION.PUBLISHED_AT, JooqTimeSupport.toOffsetDateTime(value.publishedAt())).set(CATALOG_ROLE_TEMPLATE_VERSION.CONFIGURATION, json.toJsonb(value.configuration()))
                .onConflict(CATALOG_ROLE_TEMPLATE_VERSION.ID).doUpdate().set(CATALOG_ROLE_TEMPLATE_VERSION.STATUS, value.status().name())
                .set(CATALOG_ROLE_TEMPLATE_VERSION.PUBLISHED_AT, JooqTimeSupport.toOffsetDateTime(value.publishedAt())).set(CATALOG_ROLE_TEMPLATE_VERSION.CONFIGURATION, json.toJsonb(value.configuration())).execute();
        }
    }
    @Override public void deleteRoleTemplateVersions(String id) { dsl.deleteFrom(CATALOG_ROLE_TEMPLATE_VERSION).where(CATALOG_ROLE_TEMPLATE_VERSION.TEMPLATE_ID.eq(id)).execute(); }
    @Override public List<PlaybookVersionDto> listPlaybookVersions(String id) {
        return dsl.selectFrom(CATALOG_PLAYBOOK_VERSION).where(CATALOG_PLAYBOOK_VERSION.PLAYBOOK_ID.eq(id)).orderBy(CATALOG_PLAYBOOK_VERSION.CREATED_AT, CATALOG_PLAYBOOK_VERSION.ID)
            .fetch(r -> new PlaybookVersionDto(r.getId(), r.getPlaybookId(), r.getVersion(), VersionStatus.valueOf(r.getStatus()),
                JooqTimeSupport.toInstant(r.getCreatedAt()), JooqTimeSupport.toInstant(r.getPublishedAt()), json.read(r.getDefinition(), PlaybookDefinitionDto.class)));
    }
    @Override public void replacePlaybookVersions(String id, List<PlaybookVersionDto> versions) {
        var keep = versions.stream().map(PlaybookVersionDto::id).toList();
        dsl.deleteFrom(CATALOG_PLAYBOOK_VERSION).where(CATALOG_PLAYBOOK_VERSION.PLAYBOOK_ID.eq(id).and(CATALOG_PLAYBOOK_VERSION.ID.notIn(keep))).execute();
        for (var value : versions) {
            dsl.insertInto(CATALOG_PLAYBOOK_VERSION).set(CATALOG_PLAYBOOK_VERSION.ID, value.id()).set(CATALOG_PLAYBOOK_VERSION.PLAYBOOK_ID, id)
                .set(CATALOG_PLAYBOOK_VERSION.VERSION, value.version()).set(CATALOG_PLAYBOOK_VERSION.STATUS, value.status().name())
                .set(CATALOG_PLAYBOOK_VERSION.CREATED_AT, JooqTimeSupport.toOffsetDateTime(value.createdAt()))
                .set(CATALOG_PLAYBOOK_VERSION.PUBLISHED_AT, JooqTimeSupport.toOffsetDateTime(value.publishedAt())).set(CATALOG_PLAYBOOK_VERSION.DEFINITION, json.toJsonb(value.definition()))
                .onConflict(CATALOG_PLAYBOOK_VERSION.ID).doUpdate().set(CATALOG_PLAYBOOK_VERSION.STATUS, value.status().name())
                .set(CATALOG_PLAYBOOK_VERSION.PUBLISHED_AT, JooqTimeSupport.toOffsetDateTime(value.publishedAt())).set(CATALOG_PLAYBOOK_VERSION.DEFINITION, json.toJsonb(value.definition())).execute();
        }
    }
    @Override public void deletePlaybookVersions(String id) { dsl.deleteFrom(CATALOG_PLAYBOOK_VERSION).where(CATALOG_PLAYBOOK_VERSION.PLAYBOOK_ID.eq(id)).execute(); }
    @Override public List<EntryDto> listEntries() {
        return dsl.selectFrom(CATALOG_ENTRY).orderBy(CATALOG_ENTRY.ID).fetch(r -> new EntryDto(r.getId(), r.getName(), r.getDescription(),
            r.getOwnerType(), r.getOwnerId(), ShareScope.valueOf(r.getShareScope()), r.getReceptionTemplateVersionId(), r.getEnabled(),
            json.read(r.getAllowedTemplateIds(), STRINGS), json.read(r.getCapabilityGrants(), GRANTS)));
    }
    @Override public void upsertEntry(EntryDto value) {
        var record = dsl.newRecord(CATALOG_ENTRY);
        record.setId(value.id()); record.setName(value.name()); record.setDescription(value.description());
        record.setOwnerType(value.ownerType()); record.setOwnerId(value.ownerId()); record.setShareScope(value.shareScope().name());
        record.setReceptionTemplateVersionId(value.receptionTemplateVersionId()); record.setEnabled(value.enabled());
        record.setAllowedTemplateIds(json.toJsonb(value.allowedTemplateIds())); record.setCapabilityGrants(json.toJsonb(value.capabilityGrants()));
        dsl.insertInto(CATALOG_ENTRY).set(record).onConflict(CATALOG_ENTRY.ID).doUpdate().set(record).execute();
    }
    @Override public void deleteEntry(String id) { dsl.deleteFrom(CATALOG_ENTRY).where(CATALOG_ENTRY.ID.eq(id)).execute(); }
    @Override public List<ResourceDto> listResources() {
        return dsl.selectFrom(CATALOG_RESOURCE).orderBy(CATALOG_RESOURCE.ID).fetch(r -> new ResourceDto(r.getId(), r.getName(),
            ResourceType.valueOf(r.getType()), ShareScope.valueOf(r.getShareScope()), r.getOwnerType(), r.getOwnerId(),
            r.getSummary(), r.getSteward(), json.read(r.getTags(), STRINGS), null, null, List.of()));
    }
    @Override public void upsertResource(ResourceDto value) {
        var record = dsl.newRecord(CATALOG_RESOURCE);
        record.setId(value.id()); record.setName(value.name()); record.setType(value.type().name());
        record.setShareScope(value.shareScope().name()); record.setOwnerType(value.ownerType()); record.setOwnerId(value.ownerId());
        record.setSummary(value.summary()); record.setSteward(value.steward()); record.setTags(json.toJsonb(value.tags()));
        dsl.insertInto(CATALOG_RESOURCE).set(record).onConflict(CATALOG_RESOURCE.ID).doUpdate().set(record).execute();
    }
    @Override public void deleteResource(String id) { dsl.deleteFrom(CATALOG_RESOURCE).where(CATALOG_RESOURCE.ID.eq(id)).execute(); }
    @Override public List<StoredResourceVersion> listResourceVersions(String id) {
        return dsl.selectFrom(CATALOG_RESOURCE_VERSIONS).where(CATALOG_RESOURCE_VERSIONS.RESOURCE_ID.eq(id))
            .orderBy(CATALOG_RESOURCE_VERSIONS.CREATED_AT, CATALOG_RESOURCE_VERSIONS.ID).fetch(r -> new StoredResourceVersion(
                r.getId(), r.getResourceId(), r.getVersion(), VersionStatus.valueOf(r.getStatus()), r.getSummary(), r.getConfigDigest(),
                JooqTimeSupport.toInstant(r.getCreatedAt()), JooqTimeSupport.toInstant(r.getPublishedAt()), json.read(r.getConfiguration(), ResourceVersionConfigurationDto.class)));
    }
    @Override public void replaceResourceVersions(String id, List<StoredResourceVersion> versions) {
        var keep = versions.stream().map(StoredResourceVersion::id).toList();
        dsl.deleteFrom(CATALOG_RESOURCE_VERSIONS).where(CATALOG_RESOURCE_VERSIONS.RESOURCE_ID.eq(id).and(CATALOG_RESOURCE_VERSIONS.ID.notIn(keep))).execute();
        for (var value : versions) {
            var record = dsl.newRecord(CATALOG_RESOURCE_VERSIONS);
            record.setId(value.id()); record.setResourceId(id); record.setVersion(value.version()); record.setStatus(value.status().name());
            record.setSummary(value.summary()); record.setConfigDigest(value.configDigest());
            record.setCreatedAt(JooqTimeSupport.toOffsetDateTime(value.createdAt())); record.setPublishedAt(JooqTimeSupport.toOffsetDateTime(value.publishedAt()));
            record.setConfiguration(json.toJsonb(value.configuration()));
            dsl.insertInto(CATALOG_RESOURCE_VERSIONS).set(record).onConflict(CATALOG_RESOURCE_VERSIONS.ID).doUpdate().set(record).execute();
        }
    }
    @Override public void deleteResourceVersions(String id) { dsl.deleteFrom(CATALOG_RESOURCE_VERSIONS).where(CATALOG_RESOURCE_VERSIONS.RESOURCE_ID.eq(id)).execute(); }
}
