package com.lynxus.platform.event;

import com.lynxus.persistence.event.PlatformEventStore;
import com.lynxus.persistence.jooqsupport.JooqJsonbSupport;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import static com.lynxus.platform.event.PlatformEventDtos.*;

@Repository
public class JooqPlatformEventRepository implements PlatformEventRepository {
    private final PlatformEventStore store;

    public JooqPlatformEventRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.store = new PlatformEventStore(dsl, new JooqJsonbSupport(objectMapper));
    }

    @Override
    public void append(PlatformEventDto event) {
        store.append(new PlatformEventStore.PlatformEventData(
            event.id(),
            event.eventType(),
            event.aggregateType().name(),
            event.aggregateId(),
            event.actorId(),
            event.payload(),
            event.occurredAt()
        ));
    }

    @Override
    public List<PlatformEventDto> list(PlatformEventQuery query) {
        return store.list(new PlatformEventStore.PlatformEventQuery(
                query.aggregateType() == null ? null : query.aggregateType().name(),
                query.aggregateId(),
                query.since(),
                query.cursorOccurredAt(),
                query.cursorId(),
                query.limit()
            ))
            .stream()
            .map(item -> new PlatformEventDto(
                item.id(),
                item.eventType(),
                PlatformAggregateType.valueOf(item.aggregateType()),
                item.aggregateId(),
                item.actorId(),
                item.payload(),
                item.occurredAt()
            ))
            .toList();
    }
}
