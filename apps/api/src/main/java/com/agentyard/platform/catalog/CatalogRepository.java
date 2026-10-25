package com.agentyard.platform.catalog;

import static com.agentyard.platform.catalog.CatalogDtos.*;
import java.util.*;
import java.util.function.Supplier;

public interface CatalogRepository {
    long revision();
    default <T> T inReadTransaction(Supplier<T> action) { return action.get(); }
    default <T> T inWriteTransaction(Supplier<T> action) { return action.get(); }
    List<RoleTemplateDto> listRoleTemplates();
    default Optional<RoleTemplateDto> findRoleTemplate(String id) { return listRoleTemplates().stream().filter(item -> item.id().equals(id)).findFirst(); }
    void upsertRoleTemplate(RoleTemplateDto value);
    void deleteRoleTemplate(String id);
    List<EntryDto> listEntries();
    default Optional<EntryDto> findEntry(String id) { return listEntries().stream().filter(item -> item.id().equals(id)).findFirst(); }
    void upsertEntry(EntryDto value);
    void deleteEntry(String id);
    List<PlaybookDto> listPlaybooks();
    default Optional<PlaybookDto> findPlaybook(String id) { return listPlaybooks().stream().filter(item -> item.id().equals(id)).findFirst(); }
    void upsertPlaybook(PlaybookDto value);
    void deletePlaybook(String id);
    List<ResourceDto> listResources();
    default Optional<ResourceDto> findResource(String id) { return listResources().stream().filter(item -> item.id().equals(id)).findFirst(); }
    void upsertResource(ResourceDto value);
    void deleteResource(String id);
    List<RoleTemplateVersionDto> listRoleTemplateVersions(String templateId);
    void replaceRoleTemplateVersions(String templateId, List<RoleTemplateVersionDto> versions);
    void deleteRoleTemplateVersions(String templateId);
    default Optional<RoleTemplateVersionDto> findRoleTemplateVersion(String versionId) {
        return listRoleTemplates().stream()
            .flatMap(item -> listRoleTemplateVersions(item.id()).stream()).filter(item -> item.id().equals(versionId)).findFirst();
    }
    List<PlaybookVersionDto> listPlaybookVersions(String playbookId);
    void replacePlaybookVersions(String playbookId, List<PlaybookVersionDto> versions);
    void deletePlaybookVersions(String playbookId);
    default Optional<PlaybookVersionDto> findPlaybookVersion(String versionId) {
        return listPlaybooks().stream()
            .flatMap(item -> listPlaybookVersions(item.id()).stream()).filter(item -> item.id().equals(versionId)).findFirst();
    }
    List<StoredResourceVersion> listResourceVersions(String resourceId);
    void replaceResourceVersions(String resourceId, List<StoredResourceVersion> versions);
    void deleteResourceVersions(String resourceId);
    default Optional<StoredResourceVersion> findResourceVersion(String versionId) {
        return listResources().stream()
            .flatMap(item -> listResourceVersions(item.id()).stream()).filter(item -> item.id().equals(versionId)).findFirst();
    }
}
