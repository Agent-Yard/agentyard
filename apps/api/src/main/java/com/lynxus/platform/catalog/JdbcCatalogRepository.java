package com.lynxus.platform.catalog;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcCatalogRepository implements CatalogRepository {
    private static final TypeReference<List<ResourceVersionDto>> RESOURCE_VERSION_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<AssistantReleaseDto>> ASSISTANT_RELEASE_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcCatalogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Override
    public CatalogSnapshot load() {
        return new CatalogSnapshot(
            loadList("catalog_domain", BusinessDomainDto.class),
            loadList("catalog_scenario", ScenarioDto.class),
            loadList("catalog_assistant", AssistantDto.class),
            loadList("catalog_agent", AgentDto.class),
            loadList("catalog_resource", ResourceDto.class),
            loadMappedLists("catalog_resource_versions", "resource_id", RESOURCE_VERSION_LIST),
            loadMappedLists("catalog_assistant_releases", "assistant_id", ASSISTANT_RELEASE_LIST),
            loadMappedObjects("catalog_orchestration", "assistant_id", AssistantOrchestrationDto.class)
        );
    }

    @Override
    @Transactional
    public void save(CatalogSnapshot snapshot) {
        replaceRows("catalog_domain", snapshot.domains());
        replaceRows("catalog_scenario", snapshot.scenarios());
        replaceRows("catalog_assistant", snapshot.assistants());
        replaceRows("catalog_agent", snapshot.agents());
        replaceRows("catalog_resource", snapshot.resources());
        replaceMappedLists("catalog_resource_versions", "resource_id", snapshot.resourceVersions());
        replaceMappedLists("catalog_assistant_releases", "assistant_id", snapshot.assistantReleases());
        replaceMappedObjects("catalog_orchestration", "assistant_id", snapshot.orchestrations());
    }

    @Override
    public boolean isEmpty() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from catalog_domain", Integer.class);
        return count == null || count == 0;
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
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize catalog payload", error);
        }
    }

    private <T> T readValue(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to deserialize catalog payload", error);
        }
    }

    private <T> List<T> readValue(String payload, TypeReference<List<T>> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to deserialize catalog payload list", error);
        }
    }
}
