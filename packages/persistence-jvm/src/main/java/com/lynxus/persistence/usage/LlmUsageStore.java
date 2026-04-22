package com.lynxus.persistence.usage;

import com.lynxus.persistence.jooqsupport.JooqJsonbSupport;
import com.lynxus.persistence.jooqsupport.JooqTimeSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Query;

import static com.lynxus.persistence.jooq.Tables.LLM_USAGE_RECORD;

public final class LlmUsageStore {
    private final DSLContext dsl;
    private final JooqJsonbSupport jsonbSupport;

    public LlmUsageStore(DSLContext dsl, JooqJsonbSupport jsonbSupport) {
        this.dsl = dsl;
        this.jsonbSupport = jsonbSupport;
    }

    public void append(List<LlmUsageData> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Query> queries = records.stream().map(record -> (Query) dsl.insertInto(LLM_USAGE_RECORD)
            .set(LLM_USAGE_RECORD.ID, record.id())
            .set(LLM_USAGE_RECORD.SOURCE_TYPE, record.sourceType())
            .set(LLM_USAGE_RECORD.SESSION_ID, record.sessionId())
            .set(LLM_USAGE_RECORD.TRIGGER_EVENT_ID, record.triggerEventId())
            .set(LLM_USAGE_RECORD.TRIGGER_TYPE, record.triggerType())
            .set(LLM_USAGE_RECORD.PLAYBOOK_RUN_ID, record.playbookRunId())
            .set(LLM_USAGE_RECORD.SCENARIO_ID, record.scenarioId())
            .set(LLM_USAGE_RECORD.CUSTOMER_ID, record.customerId())
            .set(LLM_USAGE_RECORD.ASSISTANT_ID, record.assistantId())
            .set(LLM_USAGE_RECORD.ASSISTANT_RELEASE_VERSION, record.assistantReleaseVersion())
            .set(LLM_USAGE_RECORD.AGENT_ID, record.agentId())
            .set(LLM_USAGE_RECORD.PROVIDER_TYPE, record.providerType())
            .set(LLM_USAGE_RECORD.MODEL_RESOURCE_ID, record.modelResourceId())
            .set(LLM_USAGE_RECORD.MODEL_RESOURCE_VERSION_ID, record.modelResourceVersionId())
            .set(LLM_USAGE_RECORD.MODEL_ID, record.modelId())
            .set(LLM_USAGE_RECORD.USAGE_AVAILABLE, record.usageAvailable())
            .set(LLM_USAGE_RECORD.PROMPT_TOKENS, record.promptTokens())
            .set(LLM_USAGE_RECORD.COMPLETION_TOKENS, record.completionTokens())
            .set(LLM_USAGE_RECORD.TOTAL_TOKENS, record.totalTokens())
            .set(LLM_USAGE_RECORD.RAW_USAGE, jsonbSupport.toJsonb(record.rawUsage() == null ? Map.of() : record.rawUsage()))
            .set(LLM_USAGE_RECORD.CALL_SEQUENCE, record.callSequence())
            .set(LLM_USAGE_RECORD.TOOL_LOOP_STEP, record.toolLoopStep())
            .set(LLM_USAGE_RECORD.OCCURRED_AT, JooqTimeSupport.toOffsetDateTime(record.occurredAt()))
            .onConflict(LLM_USAGE_RECORD.ID)
            .doNothing()).toList();
        dsl.batch(queries).execute();
    }

    public record LlmUsageData(
        String id,
        String sourceType,
        String sessionId,
        String triggerEventId,
        String triggerType,
        String playbookRunId,
        String scenarioId,
        String customerId,
        String assistantId,
        String assistantReleaseVersion,
        String agentId,
        String providerType,
        String modelResourceId,
        String modelResourceVersionId,
        String modelId,
        boolean usageAvailable,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Map<String, Object> rawUsage,
        int callSequence,
        int toolLoopStep,
        Instant occurredAt
    ) {
    }
}
