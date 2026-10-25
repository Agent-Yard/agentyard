package com.agentyard.platform.catalog;

import static com.agentyard.platform.catalog.CatalogDtos.*;
import java.util.*;
import java.util.function.Supplier;

/** In-memory transactional repository used by unit tests and local fixtures. */
public class InMemoryCatalogRepository implements CatalogRepository {
    private long revision;
    private int depth;
    private Map<String, Object> records = new LinkedHashMap<>();
    @Override public synchronized long revision() { return revision; }
    @Override public synchronized <T> T inReadTransaction(Supplier<T> action) { return action.get(); }
    @Override public synchronized <T> T inWriteTransaction(Supplier<T> action) {
        if (depth > 0) return action.get();
        Map<String, Object> previous = records;
        records = new LinkedHashMap<>(previous);
        depth++;
        try { T result = action.get(); revision++; return result; }
        catch (RuntimeException | Error error) { records = previous; throw error; }
        finally { depth--; }
    }
    private <T> List<T> list(String prefix, Class<T> type) {
        return records.entrySet().stream().filter(e -> e.getKey().startsWith(prefix + ":"))
            .map(e -> type.cast(e.getValue())).toList();
    }
    @SuppressWarnings("unchecked")
    private <T> List<T> versions(String prefix, String id) { return (List<T>) records.getOrDefault(prefix + ":" + id, List.of()); }
    @Override public synchronized List<RoleTemplateDto> listRoleTemplates() { return list("RoleTemplate", RoleTemplateDto.class); }
    @Override public synchronized void upsertRoleTemplate(RoleTemplateDto value) { records.put("RoleTemplate:" + value.id(), value); }
    @Override public synchronized void deleteRoleTemplate(String id) { records.remove("RoleTemplate:" + id); }
    @Override public synchronized List<EntryDto> listEntries() { return list("Entry", EntryDto.class); }
    @Override public synchronized void upsertEntry(EntryDto value) { records.put("Entry:" + value.id(), value); }
    @Override public synchronized void deleteEntry(String id) { records.remove("Entry:" + id); }
    @Override public synchronized List<PlaybookDto> listPlaybooks() { return list("Playbook", PlaybookDto.class); }
    @Override public synchronized void upsertPlaybook(PlaybookDto value) { records.put("Playbook:" + value.id(), value); }
    @Override public synchronized void deletePlaybook(String id) { records.remove("Playbook:" + id); }
    @Override public synchronized List<ResourceDto> listResources() { return list("Resource", ResourceDto.class); }
    @Override public synchronized void upsertResource(ResourceDto value) { records.put("Resource:" + value.id(), value); }
    @Override public synchronized void deleteResource(String id) { records.remove("Resource:" + id); }
    @Override public synchronized List<RoleTemplateVersionDto> listRoleTemplateVersions(String id) { return versions("RoleTemplateVersion", id); }
    @Override public synchronized void replaceRoleTemplateVersions(String id, List<RoleTemplateVersionDto> versions) { records.put("RoleTemplateVersion:" + id, List.copyOf(versions)); }
    @Override public synchronized void deleteRoleTemplateVersions(String id) { records.remove("RoleTemplateVersion:" + id); }
    @Override public synchronized List<PlaybookVersionDto> listPlaybookVersions(String id) { return versions("PlaybookVersion", id); }
    @Override public synchronized void replacePlaybookVersions(String id, List<PlaybookVersionDto> versions) { records.put("PlaybookVersion:" + id, List.copyOf(versions)); }
    @Override public synchronized void deletePlaybookVersions(String id) { records.remove("PlaybookVersion:" + id); }
    @Override public synchronized List<StoredResourceVersion> listResourceVersions(String id) { return versions("ResourceVersion", id); }
    @Override public synchronized void replaceResourceVersions(String id, List<StoredResourceVersion> versions) { records.put("ResourceVersion:" + id, List.copyOf(versions)); }
    @Override public synchronized void deleteResourceVersions(String id) { records.remove("ResourceVersion:" + id); }
}
