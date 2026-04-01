package com.lynxus.platform.knowledge;

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
public class JdbcKnowledgeRepository implements KnowledgeRepository {
    private static final TypeReference<List<KnowledgeReleaseDto>> KNOWLEDGE_RELEASE_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcKnowledgeRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper.rebuild()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();
    }

    @Override
    public KnowledgeSnapshot load() {
        return new KnowledgeSnapshot(
            loadList("knowledge_base", KnowledgeBaseDto.class),
            loadMappedLists("knowledge_release", "knowledge_base_id", KNOWLEDGE_RELEASE_LIST)
        );
    }

    @Override
    @Transactional
    public void save(KnowledgeSnapshot snapshot) {
        replaceRows("knowledge_base", snapshot.knowledgeBases());
        replaceMappedLists("knowledge_release", "knowledge_base_id", snapshot.knowledgeReleases());
    }

    @Override
    public boolean isEmpty() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from knowledge_base", Integer.class);
        return count == null || count == 0;
    }

    private <T> List<T> loadList(String table, Class<T> type) {
        return jdbcTemplate.query(
            "select payload from " + table + " order by id",
            (rs, rowNum) -> readValue(rs.getString("payload"), type)
        );
    }

    private <T> Map<String, List<T>> loadMappedLists(String table, String idColumn, TypeReference<List<T>> type) {
        return jdbcTemplate.query(
            "select " + idColumn + ", payload from " + table + " order by " + idColumn,
            (ResultSetExtractor<Map<String, List<T>>>) rs -> readMappedLists(rs, idColumn, type)
        );
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

    private String readId(Object item) {
        try {
            return (String) item.getClass().getMethod("id").invoke(item);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("knowledge payload does not expose an id accessor", error);
        }
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to serialize knowledge payload", error);
        }
    }

    private <T> T readValue(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to deserialize knowledge payload", error);
        }
    }

    private <T> List<T> readValue(String payload, TypeReference<List<T>> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to deserialize knowledge payload list", error);
        }
    }
}
