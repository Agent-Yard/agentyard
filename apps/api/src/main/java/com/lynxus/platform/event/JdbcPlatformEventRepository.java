package com.lynxus.platform.event;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static com.lynxus.platform.event.PlatformEventDtos.*;

@Repository
public class JdbcPlatformEventRepository implements PlatformEventRepository {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcPlatformEventRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(PlatformEventDto event) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", event.id())
            .addValue("eventType", event.eventType())
            .addValue("aggregateType", event.aggregateType().name())
            .addValue("aggregateId", event.aggregateId())
            .addValue("actorId", event.actorId())
            .addValue("payload", writeJson(event.payload()))
            .addValue("occurredAt", toTimestamp(event.occurredAt()));
        jdbcTemplate.update(
            """
                insert into platform_event (
                    id, event_type, aggregate_type, aggregate_id, actor_id, payload, occurred_at
                ) values (
                    :id, :eventType, :aggregateType, :aggregateId, :actorId, cast(:payload as jsonb), :occurredAt
                )
                on conflict (id) do nothing
                """,
            parameters
        );
    }

    @Override
    public List<PlatformEventDto> list(PlatformEventQuery query) {
        StringBuilder sql = new StringBuilder(
            """
                select id, event_type, aggregate_type, aggregate_id, actor_id, payload, occurred_at
                from platform_event
                where 1 = 1
                """
        );
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("limit", query.limit());
        if (query.aggregateType() != null) {
            sql.append(" and aggregate_type = :aggregateType");
            parameters.addValue("aggregateType", query.aggregateType().name());
        }
        if (query.aggregateId() != null) {
            sql.append(" and aggregate_id = :aggregateId");
            parameters.addValue("aggregateId", query.aggregateId());
        }
        if (query.since() != null) {
            sql.append(" and occurred_at >= :since");
            parameters.addValue("since", toTimestamp(query.since()));
        }
        if (query.cursorOccurredAt() != null && query.cursorId() != null) {
            sql.append(" and (occurred_at < :cursorOccurredAt or (occurred_at = :cursorOccurredAt and id < :cursorId))");
            parameters.addValue("cursorOccurredAt", toTimestamp(query.cursorOccurredAt()));
            parameters.addValue("cursorId", query.cursorId());
        }
        sql.append(" order by occurred_at desc, id desc limit :limit");
        return jdbcTemplate.query(sql.toString(), parameters, (rs, rowNum) -> mapEvent(rs));
    }

    private PlatformEventDto mapEvent(ResultSet rs) throws SQLException {
        return new PlatformEventDto(
            rs.getString("id"),
            rs.getString("event_type"),
            PlatformAggregateType.valueOf(rs.getString("aggregate_type")),
            rs.getString("aggregate_id"),
            rs.getString("actor_id"),
            readObjectMap(rs.getString("payload")),
            toInstant(rs.getTimestamp("occurred_at"))
        );
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to serialize platform event payload", error);
        }
    }

    private Map<String, Object> readObjectMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, OBJECT_MAP);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to deserialize platform event payload", error);
        }
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
