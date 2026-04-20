package com.lynxus.platform.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import static com.lynxus.platform.event.PlatformEventDtos.*;

@Testcontainers(disabledWithoutDocker = true)
class JdbcPlatformEventRepositoryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcPlatformEventRepository repository;
    private NamedParameterJdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure()
            .cleanDisabled(false)
            .dataSource(dataSource)
            .load()
            .clean();
        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate();
        jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        repository = new JdbcPlatformEventRepository(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void shouldCreatePlatformEventTableAndIndexes() {
        Integer tableCount = jdbcTemplate.getJdbcTemplate().queryForObject(
            "select count(*) from information_schema.tables where table_name = 'platform_event'",
            Integer.class
        );
        Integer aggregateIndexCount = jdbcTemplate.getJdbcTemplate().queryForObject(
            "select count(*) from pg_indexes where indexname = 'idx_platform_event_aggregate_occurred'",
            Integer.class
        );
        Integer occurredIndexCount = jdbcTemplate.getJdbcTemplate().queryForObject(
            "select count(*) from pg_indexes where indexname = 'idx_platform_event_occurred'",
            Integer.class
        );

        assertEquals(1, tableCount);
        assertEquals(1, aggregateIndexCount);
        assertEquals(1, occurredIndexCount);
    }

    @Test
    void shouldFilterAndPaginatePlatformEvents() {
        repository.append(event("platform-event-1", "DOMAIN_CREATED", PlatformAggregateType.DOMAIN, "domain-1", "user-1", "2026-04-01T00:00:00Z"));
        repository.append(event("platform-event-2", "DOMAIN_UPDATED", PlatformAggregateType.DOMAIN, "domain-1", "user-1", "2026-04-02T00:00:00Z"));
        repository.append(event("platform-event-3", "SCENARIO_CREATED", PlatformAggregateType.SCENARIO, "scenario-1", "user-2", "2026-04-03T00:00:00Z"));

        var filtered = repository.list(new PlatformEventRepository.PlatformEventQuery(
            PlatformAggregateType.DOMAIN,
            "domain-1",
            Instant.parse("2026-04-01T12:00:00Z"),
            null,
            null,
            10
        ));

        assertEquals(1, filtered.size());
        assertEquals("platform-event-2", filtered.getFirst().id());

        var paged = repository.list(new PlatformEventRepository.PlatformEventQuery(
            null,
            null,
            null,
            Instant.parse("2026-04-03T00:00:00Z"),
            "platform-event-3",
            10
        ));

        assertEquals(2, paged.size());
        assertEquals("platform-event-2", paged.getFirst().id());
        assertEquals("platform-event-1", paged.getLast().id());
        assertNotNull(paged.getFirst().payload());
        assertTrue(paged.getFirst().payload().containsKey("name"));
    }

    private static PlatformEventDto event(
        String id,
        String eventType,
        PlatformAggregateType aggregateType,
        String aggregateId,
        String actorId,
        String occurredAt
    ) {
        return new PlatformEventDto(
            id,
            eventType,
            aggregateType,
            aggregateId,
            actorId,
            Map.of("name", eventType),
            Instant.parse(occurredAt)
        );
    }
}
