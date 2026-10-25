package com.agentyard.persistence.usage;

import static com.agentyard.persistence.jooq.Tables.LLM_USAGE_RECORD;
import static com.agentyard.persistence.jooqsupport.JooqTimeSupport.toOffsetDateTime;
import com.agentyard.contracts.agent.AgentContracts.*;
import com.agentyard.contracts.runtime.RuntimeContracts.LlmUsageEntry;
import com.agentyard.persistence.jooqsupport.JooqJsonbSupport;
import java.util.List;
import org.jooq.DSLContext;

/** Usage has the same concrete execution identity as the transcript and tool receipts. */
public final class LlmUsageStore {
    private final DSLContext db;
    private final JooqJsonbSupport json;
    public LlmUsageStore(DSLContext db,JooqJsonbSupport json) {this.db=db;this.json=json;}
    public void append(Session session,Activation activation,String taskId,List<LlmUsageEntry> entries) {
        var t=LLM_USAGE_RECORD;
        for(var usage:entries) db.insertInto(t).set(t.ID,activation.id()+":"+usage.callSequence()+":"+usage.sourceType())
            .set(t.SOURCE_TYPE,usage.sourceType().name()).set(t.SESSION_ID,session.id()).set(t.PARTICIPANT_ID,session.participantId())
            .set(t.ACTIVATION_ID,activation.id()).set(t.TASK_ID,taskId).set(t.TEMPLATE_VERSION_ID,session.templateVersionId())
            .set(t.PROVIDER_TYPE,usage.providerType()).set(t.MODEL_RESOURCE_ID,usage.modelResourceId())
            .set(t.MODEL_RESOURCE_VERSION_ID,usage.modelResourceVersionId()).set(t.MODEL_ID,usage.modelId())
            .set(t.USAGE_AVAILABLE,usage.usageAvailable()).set(t.PROMPT_TOKENS,usage.promptTokens()).set(t.COMPLETION_TOKENS,usage.completionTokens())
            .set(t.TOTAL_TOKENS,usage.totalTokens()).set(t.RAW_USAGE,json.toJsonb(usage.rawUsage()))
            .set(t.CALL_SEQUENCE,usage.callSequence()).set(t.TOOL_LOOP_STEP,usage.toolLoopStep()).set(t.OCCURRED_AT,toOffsetDateTime(usage.occurredAt()))
            .onConflict(t.ID).doNothing().execute();
    }
}
