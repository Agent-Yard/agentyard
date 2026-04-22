package com.lynxus.platform.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.lynxus.persistence.jooqsupport.JooqJsonbSupport;
import com.lynxus.persistence.usage.LlmUsageStore;
import com.lynxus.platform.testing.EmbeddedPostgresTestDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LlmUsageStoreTest {
    private static EmbeddedPostgresTestDatabase database;

    private LlmUsageStore store;

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
        store = new LlmUsageStore(database.dsl(), new JooqJsonbSupport(new ObjectMapper()));
    }

    @Test
    void shouldCreateLlmUsageTableAndIndexes() {
        Integer tableCount = ((Number) database.dsl()
            .fetchOne("select count(*) from information_schema.tables where table_name = 'llm_usage_record'")
            .get(0)).intValue();
        Integer sessionIndexCount = ((Number) database.dsl()
            .fetchOne("select count(*) from pg_indexes where indexname = 'idx_llm_usage_record_session_occurred'")
            .get(0)).intValue();
        Integer playbookIndexCount = ((Number) database.dsl()
            .fetchOne("select count(*) from pg_indexes where indexname = 'idx_llm_usage_record_playbook_occurred'")
            .get(0)).intValue();
        Integer assistantIndexCount = ((Number) database.dsl()
            .fetchOne("select count(*) from pg_indexes where indexname = 'idx_llm_usage_record_assistant_occurred'")
            .get(0)).intValue();
        Integer scenarioIndexCount = ((Number) database.dsl()
            .fetchOne("select count(*) from pg_indexes where indexname = 'idx_llm_usage_record_scenario_occurred'")
            .get(0)).intValue();
        Integer customerIndexCount = ((Number) database.dsl()
            .fetchOne("select count(*) from pg_indexes where indexname = 'idx_llm_usage_record_customer_occurred'")
            .get(0)).intValue();

        assertEquals(1, tableCount);
        assertEquals(1, sessionIndexCount);
        assertEquals(1, playbookIndexCount);
        assertEquals(1, assistantIndexCount);
        assertEquals(1, scenarioIndexCount);
        assertEquals(1, customerIndexCount);
    }

    @Test
    void shouldAppendRecordsAndIgnoreDuplicateIds() {
        LlmUsageStore.LlmUsageData ownerRecord = record(
            "usage-1",
            "SESSION_OWNER_MODEL",
            "run-1",
            1,
            0,
            Instant.parse("2026-04-22T08:00:00Z")
        );
        LlmUsageStore.LlmUsageData privacyRecord = record(
            "usage-2",
            "SESSION_PRIVACY_MODEL",
            null,
            2,
            0,
            Instant.parse("2026-04-22T08:00:01Z")
        );

        store.append(List.of(ownerRecord, privacyRecord));
        store.append(List.of(ownerRecord));

        Integer count = ((Number) database.dsl().fetchOne("select count(*) from llm_usage_record").get(0)).intValue();
        assertEquals(2, count);

        var owner = database.dsl().fetchOne(
            "select source_type, playbook_run_id, prompt_tokens, total_tokens, raw_usage from llm_usage_record where id = ?",
            "usage-1"
        );
        assertNotNull(owner);
        assertEquals("SESSION_OWNER_MODEL", owner.get("source_type"));
        assertEquals("run-1", owner.get("playbook_run_id"));
        assertEquals(11, ((Number) owner.get("prompt_tokens")).intValue());
        assertEquals(18, ((Number) owner.get("total_tokens")).intValue());
        assertNotNull(owner.get("raw_usage"));
    }

    private static LlmUsageStore.LlmUsageData record(
        String id,
        String sourceType,
        String playbookRunId,
        int callSequence,
        int toolLoopStep,
        Instant occurredAt
    ) {
        return new LlmUsageStore.LlmUsageData(
            id,
            sourceType,
            "session-1",
            "event-1",
            "USER_MESSAGE",
            playbookRunId,
            "scenario-1",
            "customer-1",
            "assistant-1",
            "2026.04.22",
            "agent-1",
            "OPENAI_COMPATIBLE",
            "model-1",
            "model-ver-1",
            "gpt-test",
            true,
            11,
            7,
            18,
            Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18),
            callSequence,
            toolLoopStep,
            occurredAt
        );
    }
}
