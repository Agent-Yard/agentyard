package com.agentyard.persistence.event;

import com.agentyard.persistence.jooqsupport.JooqJsonbSupport;
import com.agentyard.persistence.jooqsupport.JooqTimeSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jooq.Condition;
import org.jooq.DSLContext;

import static com.agentyard.persistence.jooq.Tables.PLATFORM_EVENT;

public final class PlatformEventStore {
    private final DSLContext dsl;
    private final JooqJsonbSupport jsonbSupport;

    public PlatformEventStore(DSLContext dsl, JooqJsonbSupport jsonbSupport) {
        this.dsl = dsl;
        this.jsonbSupport = jsonbSupport;
    }

    public void append(PlatformEventData event) {
        dsl.insertInto(PLATFORM_EVENT)
            .set(PLATFORM_EVENT.ID, event.id())
            .set(PLATFORM_EVENT.EVENT_TYPE, event.eventType())
            .set(PLATFORM_EVENT.AGGREGATE_TYPE, event.aggregateType())
            .set(PLATFORM_EVENT.AGGREGATE_ID, event.aggregateId())
            .set(PLATFORM_EVENT.ACTOR_ID, event.actorId())
            .set(PLATFORM_EVENT.PAYLOAD, jsonbSupport.toJsonb(event.payload() == null ? Map.of() : event.payload()))
            .set(PLATFORM_EVENT.OCCURRED_AT, JooqTimeSupport.toOffsetDateTime(event.occurredAt()))
            .onConflict(PLATFORM_EVENT.ID)
            .doNothing()
            .execute();
    }

    public List<PlatformEventData> list(PlatformEventQuery query) {
        Condition condition = org.jooq.impl.DSL.trueCondition();
        if (query.aggregateType() != null) {
            condition = condition.and(PLATFORM_EVENT.AGGREGATE_TYPE.eq(query.aggregateType()));
        }
        if (query.aggregateId() != null) {
            condition = condition.and(PLATFORM_EVENT.AGGREGATE_ID.eq(query.aggregateId()));
        }
        if (query.since() != null) {
            condition = condition.and(PLATFORM_EVENT.OCCURRED_AT.ge(JooqTimeSupport.toOffsetDateTime(query.since())));
        }
        if (query.cursorOccurredAt() != null && query.cursorId() != null) {
            condition = condition.and(
                PLATFORM_EVENT.OCCURRED_AT.lt(JooqTimeSupport.toOffsetDateTime(query.cursorOccurredAt()))
                    .or(
                        PLATFORM_EVENT.OCCURRED_AT.eq(JooqTimeSupport.toOffsetDateTime(query.cursorOccurredAt()))
                            .and(PLATFORM_EVENT.ID.lt(query.cursorId()))
                    )
            );
        }

        return dsl.selectFrom(PLATFORM_EVENT)
            .where(condition)
            .orderBy(PLATFORM_EVENT.OCCURRED_AT.desc(), PLATFORM_EVENT.ID.desc())
            .limit(query.limit())
            .fetch(record -> new PlatformEventData(
                record.getId(),
                record.getEventType(),
                record.getAggregateType(),
                record.getAggregateId(),
                record.getActorId(),
                jsonbSupport.readObjectMap(record.getPayload()),
                JooqTimeSupport.toInstant(record.getOccurredAt())
            ));
    }

    public record PlatformEventData(
        String id,
        String eventType,
        String aggregateType,
        String aggregateId,
        String actorId,
        Map<String, Object> payload,
        Instant occurredAt
    ) {
    }

    public record PlatformEventQuery(
        String aggregateType,
        String aggregateId,
        Instant since,
        Instant cursorOccurredAt,
        String cursorId,
        int limit
    ) {
    }
}
