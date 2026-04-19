package com.lynxus.platform.catalog;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcCatalogRepository implements CatalogRepository {
    private static final TypeReference<List<StoredResourceVersion>> RESOURCE_VERSION_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<AssistantReleaseDto>> ASSISTANT_RELEASE_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcCatalogRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper.rebuild()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();
    }

    @Override
    public CatalogSnapshot load() {
        return new CatalogSnapshot(
            loadList("catalog_domain", BusinessDomainDto.class),
            loadList("catalog_scenario", ScenarioDto.class),
            loadList("catalog_assistant", AssistantDto.class),
            loadList("catalog_agent", AgentDto.class),
            loadList("catalog_playbook", PlaybookDto.class),
            loadList("catalog_resource", ResourceDto.class),
            loadMappedLists("catalog_resource_versions", "resource_id", RESOURCE_VERSION_LIST),
            loadMappedLists("catalog_assistant_releases", "assistant_id", ASSISTANT_RELEASE_LIST)
        );
    }

    @Override
    @Transactional
    public void save(CatalogSnapshot snapshot) {
        replaceRows("catalog_domain", snapshot.domains());
        replaceRows("catalog_scenario", snapshot.scenarios());
        replaceRows("catalog_assistant", snapshot.assistants());
        replaceRows("catalog_agent", snapshot.agents());
        replaceRows("catalog_playbook", snapshot.playbooks());
        replaceRows("catalog_resource", snapshot.resources());
        replaceMappedLists("catalog_resource_versions", "resource_id", snapshot.resourceVersions());
        replaceMappedLists("catalog_assistant_releases", "assistant_id", snapshot.assistantReleases());

        replaceResourceBindings(snapshot);
        replaceKnowledgeBindings(snapshot);
        replaceReleaseResources(snapshot);
        replaceReleaseKnowledge(snapshot);
    }

    @Override
    public boolean isEmpty() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from catalog_domain", Integer.class);
        return count == null || count == 0;
    }

    @Override
    public List<ResourceBindingRef> findResourceBindings(String resourceId) {
        return jdbcTemplate.query(
            "select source_type, source_id, resource_id, binding_kind from catalog_ref_resource_binding where resource_id = ?",
            (rs, rowNum) -> new ResourceBindingRef(rs.getString("source_type"), rs.getString("source_id"), rs.getString("resource_id"), rs.getString("binding_kind")),
            resourceId
        );
    }

    @Override
    public List<ReleaseResourceRef> findReleaseResourceRefs(String resourceId) {
        return jdbcTemplate.query(
            "select release_id, assistant_id, resource_id, resource_version_id, resource_version from catalog_ref_release_resource where resource_id = ?",
            (rs, rowNum) -> new ReleaseResourceRef(rs.getString("release_id"), rs.getString("assistant_id"), rs.getString("resource_id"), rs.getString("resource_version_id"), rs.getString("resource_version")),
            resourceId
        );
    }

    @Override
    public List<KnowledgeBindingRef> findKnowledgeBindings(String knowledgeBaseId) {
        return jdbcTemplate.query(
            "select source_type, source_id, knowledge_base_id, binding_kind from catalog_ref_knowledge_binding where knowledge_base_id = ?",
            (rs, rowNum) -> new KnowledgeBindingRef(rs.getString("source_type"), rs.getString("source_id"), rs.getString("knowledge_base_id"), rs.getString("binding_kind")),
            knowledgeBaseId
        );
    }

    @Override
    public List<ReleaseKnowledgeRef> findReleaseKnowledgeRefs(String knowledgeBaseId) {
        return jdbcTemplate.query(
            "select release_id, assistant_id, knowledge_base_id, knowledge_release_id from catalog_ref_release_knowledge where knowledge_base_id = ?",
            (rs, rowNum) -> new ReleaseKnowledgeRef(rs.getString("release_id"), rs.getString("assistant_id"), rs.getString("knowledge_base_id"), rs.getString("knowledge_release_id")),
            knowledgeBaseId
        );
    }

    @Override
    public List<ResourceBindingRef> findAllResourceBindings() {
        return jdbcTemplate.query(
            "select source_type, source_id, resource_id, binding_kind from catalog_ref_resource_binding",
            (rs, rowNum) -> new ResourceBindingRef(rs.getString("source_type"), rs.getString("source_id"), rs.getString("resource_id"), rs.getString("binding_kind"))
        );
    }

    @Override
    public List<ReleaseResourceRef> findAllReleaseResourceRefs() {
        return jdbcTemplate.query(
            "select release_id, assistant_id, resource_id, resource_version_id, resource_version from catalog_ref_release_resource",
            (rs, rowNum) -> new ReleaseResourceRef(rs.getString("release_id"), rs.getString("assistant_id"), rs.getString("resource_id"), rs.getString("resource_version_id"), rs.getString("resource_version"))
        );
    }

    private <T> List<T> loadList(String table, Class<T> type) {
        return jdbcTemplate.query(
            "select payload from " + table + " order by id",
            (rs, rowNum) -> readValue(rs.getString("payload"), type)
        );
    }

    private <T> Map<String, T> loadMappedObjects(String table, String idColumn, Class<T> type) {
        return jdbcTemplate.query(
            "select " + idColumn + ", payload from " + table + " order by " + idColumn,
            (ResultSetExtractor<Map<String, T>>) rs -> readMappedObjects(rs, idColumn, type)
        );
    }

    private <T> Map<String, List<T>> loadMappedLists(String table, String idColumn, TypeReference<List<T>> type) {
        return jdbcTemplate.query(
            "select " + idColumn + ", payload from " + table + " order by " + idColumn,
            (ResultSetExtractor<Map<String, List<T>>>) rs -> readMappedLists(rs, idColumn, type)
        );
    }

    private <T> Map<String, T> readMappedObjects(ResultSet rs, String idColumn, Class<T> type) throws SQLException {
        Map<String, T> items = new LinkedHashMap<>();
        while (rs.next()) {
            items.put(rs.getString(idColumn), readValue(rs.getString("payload"), type));
        }
        return items;
    }

    private <T> Map<String, List<T>> readMappedLists(ResultSet rs, String idColumn, TypeReference<List<T>> type) throws SQLException {
        Map<String, List<T>> items = new LinkedHashMap<>();
        while (rs.next()) {
            items.put(rs.getString(idColumn), readValue(rs.getString("payload"), type));
        }
        return items;
    }

    private void replaceRows(String table, List<?> items) {
        jdbcTemplate.update("delete from " + table);
        for (Object item : items) {
            jdbcTemplate.update(
                "insert into " + table + " (id, payload) values (?, cast(? as jsonb))",
                readId(item),
                writeValue(item)
            );
        }
    }

    private void replaceMappedLists(String table, String idColumn, Map<String, ?> items) {
        jdbcTemplate.update("delete from " + table);
        for (Map.Entry<String, ?> entry : items.entrySet()) {
            jdbcTemplate.update(
                "insert into " + table + " (" + idColumn + ", payload) values (?, cast(? as jsonb))",
                entry.getKey(),
                writeValue(entry.getValue())
            );
        }
    }

    private void replaceMappedObjects(String table, String idColumn, Map<String, ?> items) {
        jdbcTemplate.update("delete from " + table);
        for (Map.Entry<String, ?> entry : items.entrySet()) {
            jdbcTemplate.update(
                "insert into " + table + " (" + idColumn + ", payload) values (?, cast(? as jsonb))",
                entry.getKey(),
                writeValue(entry.getValue())
            );
        }
    }

    private String readId(Object item) {
        try {
            return (String) item.getClass().getMethod("id").invoke(item);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("catalog payload does not expose an id accessor", error);
        }
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to serialize catalog payload", error);
        }
    }

    private <T> T readValue(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to deserialize catalog payload", error);
        }
    }

    private <T> List<T> readValue(String payload, TypeReference<List<T>> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to deserialize catalog payload list", error);
        }
    }

    private void replaceResourceBindings(CatalogSnapshot snapshot) {
        jdbcTemplate.update("delete from catalog_ref_resource_binding");
        for (AssistantDto assistant : snapshot.assistants()) {
            if (assistant.modelPolicy() != null && assistant.modelPolicy().defaultModelResourceId() != null) {
                insertResourceBinding("ASSISTANT", assistant.id(), assistant.modelPolicy().defaultModelResourceId(), "ASSISTANT_DEFAULT_MODEL");
            }
        }
        for (AgentDto agent : snapshot.agents()) {
            AgentExecutionPolicyDto policy = agent.executionPolicy();
            if (policy == null) continue;
            if (policy.modelResourceId() != null) {
                insertResourceBinding("AGENT", agent.id(), policy.modelResourceId(), "AGENT_OVERRIDE_MODEL");
            }
            for (String skillId : safe(policy.skillResourceIds())) {
                insertResourceBinding("AGENT", agent.id(), skillId, "AGENT_SKILL_ENABLED");
            }
            for (String toolId : safe(policy.toolResourceIds())) {
                insertResourceBinding("AGENT", agent.id(), toolId, "AGENT_TOOL_ENABLED");
            }
        }
    }

    private void insertResourceBinding(String sourceType, String sourceId, String resourceId, String bindingKind) {
        jdbcTemplate.update(
            "insert into catalog_ref_resource_binding (source_type, source_id, resource_id, binding_kind) values (?, ?, ?, ?)",
            sourceType, sourceId, resourceId, bindingKind
        );
    }

    private void replaceKnowledgeBindings(CatalogSnapshot snapshot) {
        jdbcTemplate.update("delete from catalog_ref_knowledge_binding");
        for (AssistantDto assistant : snapshot.assistants()) {
            if (assistant.knowledgeAccessPolicy() != null && assistant.knowledgeAccessPolicy().enabled() && assistant.knowledgeAccessPolicy().knowledgeBaseId() != null) {
                insertKnowledgeBinding("ASSISTANT", assistant.id(), assistant.knowledgeAccessPolicy().knowledgeBaseId(), "ASSISTANT_DEFAULT_KNOWLEDGE_BASE");
            }
        }
        for (AgentDto agent : snapshot.agents()) {
            AgentExecutionPolicyDto policy = agent.executionPolicy();
            if (policy == null) continue;
            if (policy.knowledgeEnabled() && !policy.inheritAssistantKnowledge() && policy.knowledgeBaseId() != null) {
                insertKnowledgeBinding("AGENT", agent.id(), policy.knowledgeBaseId(), "AGENT_OVERRIDE_KNOWLEDGE_BASE");
            }
        }
    }

    private void insertKnowledgeBinding(String sourceType, String sourceId, String knowledgeBaseId, String bindingKind) {
        jdbcTemplate.update(
            "insert into catalog_ref_knowledge_binding (source_type, source_id, knowledge_base_id, binding_kind) values (?, ?, ?, ?)",
            sourceType, sourceId, knowledgeBaseId, bindingKind
        );
    }

    private void replaceReleaseResources(CatalogSnapshot snapshot) {
        jdbcTemplate.update("delete from catalog_ref_release_resource");
        for (Map.Entry<String, List<AssistantReleaseDto>> entry : snapshot.assistantReleases().entrySet()) {
            String assistantId = entry.getKey();
            for (AssistantReleaseDto release : entry.getValue()) {
                for (AssistantReleaseResourceDto res : safe(release.resources())) {
                    jdbcTemplate.update(
                        "insert into catalog_ref_release_resource (release_id, assistant_id, resource_id, resource_version_id, resource_version) values (?, ?, ?, ?, ?)",
                        release.id(), assistantId, res.resourceId(), res.resourceVersionId(), res.resourceVersion()
                    );
                }
            }
        }
    }

    private void replaceReleaseKnowledge(CatalogSnapshot snapshot) {
        jdbcTemplate.update("delete from catalog_ref_release_knowledge");
        for (Map.Entry<String, List<AssistantReleaseDto>> entry : snapshot.assistantReleases().entrySet()) {
            String assistantId = entry.getKey();
            for (AssistantReleaseDto release : entry.getValue()) {
                insertReleaseKnowledgeIfPresent(release.id(), assistantId, release.assistantKnowledgeBinding());
                for (AssistantReleaseAgentDto agent : safe(release.agents())) {
                    insertReleaseKnowledgeIfPresent(release.id(), assistantId, agent.knowledgeBinding());
                }
            }
        }
    }

    private void insertReleaseKnowledgeIfPresent(String releaseId, String assistantId, KnowledgeBindingSnapshotDto binding) {
        if (binding == null || binding.knowledgeBaseId() == null) return;
        jdbcTemplate.update(
            "insert into catalog_ref_release_knowledge (release_id, assistant_id, knowledge_base_id, knowledge_release_id) values (?, ?, ?, ?) on conflict do nothing",
            releaseId, assistantId, binding.knowledgeBaseId(), binding.knowledgeReleaseId()
        );
    }

    private <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
