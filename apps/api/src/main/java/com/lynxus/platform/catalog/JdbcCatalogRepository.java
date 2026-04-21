package com.lynxus.platform.catalog;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.platform.shared.ConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
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
    private final ThreadLocal<Boolean> writeTransactionOpen = ThreadLocal.withInitial(() -> false);

    public JdbcCatalogRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper.rebuild()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();
    }

    @Override
    public long revision() {
        Long revision = jdbcTemplate.queryForObject(
            "select revision from shared_state_revision where domain = ?",
            Long.class,
            "catalog"
        );
        return revision == null ? 0L : revision;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public <T> T inReadTransaction(java.util.function.Supplier<T> action) {
        return action.get();
    }

    @Override
    @Transactional
    public <T> T inWriteTransaction(java.util.function.Supplier<T> action) {
        if (writeTransactionOpen.get()) {
            return action.get();
        }
        long expectedRevision = revision();
        reserveRevision("catalog", expectedRevision);
        writeTransactionOpen.set(true);
        try {
            T result = action.get();
            refreshDerivedReferenceTables();
            return result;
        } finally {
            writeTransactionOpen.remove();
        }
    }

    @Override
    public List<BusinessDomainDto> listDomains() {
        return loadList("catalog_domain", BusinessDomainDto.class);
    }

    @Override
    public Optional<BusinessDomainDto> findDomain(String domainId) {
        return findById("catalog_domain", domainId, BusinessDomainDto.class);
    }

    @Override
    public List<ScenarioDto> listScenarios() {
        return loadList("catalog_scenario", ScenarioDto.class);
    }

    @Override
    public Optional<ScenarioDto> findScenario(String scenarioId) {
        return findById("catalog_scenario", scenarioId, ScenarioDto.class);
    }

    @Override
    public List<AssistantDto> listAssistants() {
        return loadList("catalog_assistant", AssistantDto.class);
    }

    @Override
    public Optional<AssistantDto> findAssistant(String assistantId) {
        return findById("catalog_assistant", assistantId, AssistantDto.class);
    }

    @Override
    public List<AgentDto> listAgents() {
        return loadList("catalog_agent", AgentDto.class);
    }

    @Override
    public Optional<AgentDto> findAgent(String agentId) {
        return findById("catalog_agent", agentId, AgentDto.class);
    }

    @Override
    public List<PlaybookDto> listPlaybooks() {
        return loadList("catalog_playbook", PlaybookDto.class);
    }

    @Override
    public Optional<PlaybookDto> findPlaybook(String playbookId) {
        return findById("catalog_playbook", playbookId, PlaybookDto.class);
    }

    @Override
    public List<ResourceDto> listResources() {
        return loadList("catalog_resource", ResourceDto.class);
    }

    @Override
    public Optional<ResourceDto> findResource(String resourceId) {
        return findById("catalog_resource", resourceId, ResourceDto.class);
    }

    @Override
    public List<StoredResourceVersion> listResourceVersions(String resourceId) {
        return findMappedList("catalog_resource_versions", "resource_id", resourceId, RESOURCE_VERSION_LIST).orElse(List.of());
    }

    @Override
    public List<AssistantReleaseDto> listAssistantReleases(String assistantId) {
        return findMappedList("catalog_assistant_releases", "assistant_id", assistantId, ASSISTANT_RELEASE_LIST).orElse(List.of());
    }

    @Override
    public Optional<AssistantReleaseDto> findAssistantReleaseById(String releaseId) {
        List<AssistantReleaseDto> matches = new ArrayList<>();
        jdbcTemplate.query(
            "select payload from catalog_assistant_releases order by assistant_id",
            rs -> {
                List<AssistantReleaseDto> releases = readValue(rs.getString("payload"), ASSISTANT_RELEASE_LIST);
                for (AssistantReleaseDto release : releases) {
                    if (release.id().equals(releaseId)) {
                        matches.add(release);
                        break;
                    }
                }
            }
        );
        return matches.stream().findFirst();
    }

    @Override
    public void upsertDomain(BusinessDomainDto domain) {
        upsertRow("catalog_domain", domain.id(), domain);
    }

    @Override
    public void deleteDomain(String domainId) {
        deleteRow("catalog_domain", "id", domainId);
    }

    @Override
    public void upsertScenario(ScenarioDto scenario) {
        upsertRow("catalog_scenario", scenario.id(), scenario);
    }

    @Override
    public void deleteScenario(String scenarioId) {
        deleteRow("catalog_scenario", "id", scenarioId);
    }

    @Override
    public void upsertAssistant(AssistantDto assistant) {
        upsertRow("catalog_assistant", assistant.id(), assistant);
    }

    @Override
    public void deleteAssistant(String assistantId) {
        deleteRow("catalog_assistant", "id", assistantId);
    }

    @Override
    public void upsertAgent(AgentDto agent) {
        upsertRow("catalog_agent", agent.id(), agent);
    }

    @Override
    public void deleteAgent(String agentId) {
        deleteRow("catalog_agent", "id", agentId);
    }

    @Override
    public void upsertPlaybook(PlaybookDto playbook) {
        upsertRow("catalog_playbook", playbook.id(), playbook);
    }

    @Override
    public void deletePlaybook(String playbookId) {
        deleteRow("catalog_playbook", "id", playbookId);
    }

    @Override
    public void upsertResource(ResourceDto resource) {
        upsertRow("catalog_resource", resource.id(), resource);
    }

    @Override
    public void deleteResource(String resourceId) {
        deleteRow("catalog_resource", "id", resourceId);
    }

    @Override
    public void replaceResourceVersions(String resourceId, List<StoredResourceVersion> versions) {
        replaceMappedListEntry("catalog_resource_versions", "resource_id", resourceId, versions);
    }

    @Override
    public void deleteResourceVersions(String resourceId) {
        deleteRow("catalog_resource_versions", "resource_id", resourceId);
    }

    @Override
    public void replaceAssistantReleases(String assistantId, List<AssistantReleaseDto> releases) {
        replaceMappedListEntry("catalog_assistant_releases", "assistant_id", assistantId, releases);
    }

    @Override
    public void deleteAssistantReleases(String assistantId) {
        deleteRow("catalog_assistant_releases", "assistant_id", assistantId);
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

    private <T> Optional<T> findById(String table, String id, Class<T> type) {
        return jdbcTemplate.query(
            "select payload from " + table + " where id = ?",
            (rs, rowNum) -> readValue(rs.getString("payload"), type),
            id
        ).stream().findFirst();
    }

    private <T> Map<String, List<T>> loadMappedLists(String table, String idColumn, TypeReference<List<T>> type) {
        return jdbcTemplate.query(
            "select " + idColumn + ", payload from " + table + " order by " + idColumn,
            (ResultSetExtractor<Map<String, List<T>>>) rs -> readMappedLists(rs, idColumn, type)
        );
    }

    private <T> Optional<List<T>> findMappedList(String table, String idColumn, String id, TypeReference<List<T>> type) {
        return jdbcTemplate.query(
            "select payload from " + table + " where " + idColumn + " = ?",
            (rs, rowNum) -> readValue(rs.getString("payload"), type),
            id
        ).stream().findFirst();
    }

    private void upsertRow(String table, String id, Object payload) {
        deleteRow(table, "id", id);
        jdbcTemplate.update(
            "insert into " + table + " (id, payload) values (?, cast(? as jsonb))",
            id,
            writeValue(payload)
        );
    }

    private void refreshDerivedReferenceTables() {
        replaceResourceBindings(listAssistants(), listAgents());
        replaceKnowledgeBindings(listAssistants(), listAgents());
        Map<String, List<AssistantReleaseDto>> assistantReleases = loadMappedLists("catalog_assistant_releases", "assistant_id", ASSISTANT_RELEASE_LIST);
        replaceReleaseResources(assistantReleases);
        replaceReleaseKnowledge(assistantReleases);
    }

    private void deleteRow(String table, String idColumn, String id) {
        jdbcTemplate.update("delete from " + table + " where " + idColumn + " = ?", id);
    }

    private void replaceMappedListEntry(String table, String idColumn, String id, Object payload) {
        deleteRow(table, idColumn, id);
        jdbcTemplate.update(
            "insert into " + table + " (" + idColumn + ", payload) values (?, cast(? as jsonb))",
            id,
            writeValue(payload)
        );
    }

    private <T> Map<String, List<T>> readMappedLists(ResultSet rs, String idColumn, TypeReference<List<T>> type) throws SQLException {
        Map<String, List<T>> items = new LinkedHashMap<>();
        while (rs.next()) {
            items.put(rs.getString(idColumn), readValue(rs.getString("payload"), type));
        }
        return items;
    }

    private void reserveRevision(String domain, long expectedRevision) {
        int updated = jdbcTemplate.update(
            """
                update shared_state_revision
                set revision = revision + 1,
                    updated_at = now()
                where domain = ?
                  and revision = ?
                """,
            domain,
            expectedRevision
        );
        if (updated != 1) {
            throw new ConflictException("catalog changed on another instance; retry the request");
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

    private void replaceResourceBindings(List<AssistantDto> assistants, List<AgentDto> agents) {
        jdbcTemplate.update("delete from catalog_ref_resource_binding");
        for (AssistantDto assistant : assistants) {
            if (assistant.modelPolicy() != null && assistant.modelPolicy().defaultModelResourceId() != null) {
                insertResourceBinding("ASSISTANT", assistant.id(), assistant.modelPolicy().defaultModelResourceId(), "ASSISTANT_DEFAULT_MODEL");
            }
            if (assistant.privacyModelResourceId() != null) {
                insertResourceBinding("ASSISTANT", assistant.id(), assistant.privacyModelResourceId(), "ASSISTANT_PRIVACY_MODEL");
            }
        }
        for (AgentDto agent : agents) {
            AgentExecutionPolicyDto policy = agent.executionPolicy();
            if (policy == null) continue;
            if (policy.modelResourceId() != null) {
                insertResourceBinding("AGENT", agent.id(), policy.modelResourceId(), "AGENT_OVERRIDE_MODEL");
            }
            if (policy.privacyModelResourceId() != null) {
                insertResourceBinding("AGENT", agent.id(), policy.privacyModelResourceId(), "AGENT_PRIVACY_MODEL_OVERRIDE");
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

    private void replaceKnowledgeBindings(List<AssistantDto> assistants, List<AgentDto> agents) {
        jdbcTemplate.update("delete from catalog_ref_knowledge_binding");
        for (AssistantDto assistant : assistants) {
            if (assistant.knowledgeAccessPolicy() != null && assistant.knowledgeAccessPolicy().enabled() && assistant.knowledgeAccessPolicy().knowledgeBaseId() != null) {
                insertKnowledgeBinding("ASSISTANT", assistant.id(), assistant.knowledgeAccessPolicy().knowledgeBaseId(), "ASSISTANT_DEFAULT_KNOWLEDGE_BASE");
            }
        }
        for (AgentDto agent : agents) {
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

    private void replaceReleaseResources(Map<String, List<AssistantReleaseDto>> assistantReleases) {
        jdbcTemplate.update("delete from catalog_ref_release_resource");
        for (Map.Entry<String, List<AssistantReleaseDto>> entry : assistantReleases.entrySet()) {
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

    private void replaceReleaseKnowledge(Map<String, List<AssistantReleaseDto>> assistantReleases) {
        jdbcTemplate.update("delete from catalog_ref_release_knowledge");
        for (Map.Entry<String, List<AssistantReleaseDto>> entry : assistantReleases.entrySet()) {
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
