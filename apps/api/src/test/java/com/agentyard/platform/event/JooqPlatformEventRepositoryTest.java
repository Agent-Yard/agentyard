package com.agentyard.platform.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentyard.platform.testing.EmbeddedPostgresTestDatabase;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static com.agentyard.platform.event.PlatformEventDtos.*;

class JooqPlatformEventRepositoryTest {
    private static EmbeddedPostgresTestDatabase database;

    private JooqPlatformEventRepository repository;

    @BeforeAll
    static void startDatabase() throws Exception {
        database = new EmbeddedPostgresTestDatabase();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        database.close();
    }

    @BeforeEach
    void setUp() {
        database.reset();
        repository = new JooqPlatformEventRepository(database.dsl(), new ObjectMapper());
    }

    @Test
    void shouldCreatePlatformEventTableAndIndexes() {
        Integer tableCount = ((Number) database.dsl()
            .fetchOne("select count(*) from information_schema.tables where table_name = 'platform_event'")
            .get(0)).intValue();
        Integer aggregateIndexCount = ((Number) database.dsl()
            .fetchOne("select count(*) from pg_indexes where indexname = 'idx_platform_event_aggregate_occurred'")
            .get(0)).intValue();
        Integer occurredIndexCount = ((Number) database.dsl()
            .fetchOne("select count(*) from pg_indexes where indexname = 'idx_platform_event_occurred'")
            .get(0)).intValue();

        assertEquals(1, tableCount);
        assertEquals(1, aggregateIndexCount);
        assertEquals(1, occurredIndexCount);
    }

    @Test
    void shouldFilterAndPaginatePlatformEvents() {
        repository.append(event("platform-event-1", "ROLE_TEMPLATE_CREATED", PlatformAggregateType.ROLE_TEMPLATE, "template-1", "user-1", "2026-04-01T00:00:00Z"));
        repository.append(event("platform-event-2", "ROLE_TEMPLATE_UPDATED", PlatformAggregateType.ROLE_TEMPLATE, "template-1", "user-1", "2026-04-02T00:00:00Z"));
        repository.append(event("platform-event-3", "ENTRY_CREATED", PlatformAggregateType.ENTRY, "entry-1", "user-2", "2026-04-03T00:00:00Z"));

        var filtered = repository.list(new PlatformEventRepository.PlatformEventQuery(
            PlatformAggregateType.ROLE_TEMPLATE,
            "template-1",
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
