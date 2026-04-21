package com.lynxus.platform.knowledge;

import static com.lynxus.platform.catalog.CatalogDtos.*;

import com.lynxus.platform.shared.ConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
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
public class JdbcKnowledgeRepository implements KnowledgeRepository {
    private static final TypeReference<List<KnowledgeReleaseDto>> KNOWLEDGE_RELEASE_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ThreadLocal<Boolean> writeTransactionOpen = ThreadLocal.withInitial(() -> false);

    public JdbcKnowledgeRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
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
            "knowledge"
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
        reserveRevision("knowledge", expectedRevision);
        writeTransactionOpen.set(true);
        try {
            return action.get();
        } finally {
            writeTransactionOpen.remove();
        }
    }

    @Override
    public List<KnowledgeBaseDto> listKnowledgeBases() {
        return loadList("knowledge_base", KnowledgeBaseDto.class);
    }

    @Override
    public Optional<KnowledgeBaseDto> findKnowledgeBase(String knowledgeBaseId) {
        return findById("knowledge_base", knowledgeBaseId, KnowledgeBaseDto.class);
    }

    @Override
    public List<KnowledgeReleaseDto> listKnowledgeReleases(String knowledgeBaseId) {
        return findMappedList("knowledge_release", "knowledge_base_id", knowledgeBaseId, KNOWLEDGE_RELEASE_LIST).orElse(List.of());
    }

    @Override
    public void upsertKnowledgeBase(KnowledgeBaseDto knowledgeBase) {
        upsertRow("knowledge_base", knowledgeBase.id(), knowledgeBase);
    }

    @Override
    public void deleteKnowledgeBase(String knowledgeBaseId) {
        deleteRow("knowledge_base", "id", knowledgeBaseId);
    }

    @Override
    public void replaceKnowledgeReleases(String knowledgeBaseId, List<KnowledgeReleaseDto> releases) {
        replaceMappedListEntry("knowledge_release", "knowledge_base_id", knowledgeBaseId, releases);
    }

    @Override
    public void deleteKnowledgeReleases(String knowledgeBaseId) {
        deleteRow("knowledge_release", "knowledge_base_id", knowledgeBaseId);
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

    private <T> Map<String, List<T>> readMappedLists(ResultSet rs, String idColumn, TypeReference<List<T>> type) throws SQLException {
        Map<String, List<T>> items = new LinkedHashMap<>();
        while (rs.next()) {
            items.put(rs.getString(idColumn), readValue(rs.getString("payload"), type));
        }
        return items;
    }

    private void upsertRow(String table, String id, Object payload) {
        jdbcTemplate.update("delete from " + table + " where id = ?", id);
        jdbcTemplate.update(
            "insert into " + table + " (id, payload) values (?, cast(? as jsonb))",
            id,
            writeValue(payload)
        );
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
            throw new ConflictException("knowledge changed on another instance; retry the request");
        }
    }
}
