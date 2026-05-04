package com.lynxus.platform.event;

import java.time.Instant;
import java.util.List;

import static com.lynxus.platform.event.PlatformEventDtos.*;

public interface PlatformEventRepository {
    void append(PlatformEventDto event);

    List<PlatformEventDto> list(PlatformEventQuery query);

    record PlatformEventQuery(
        PlatformAggregateType aggregateType,
        String aggregateId,
        Instant since,
        Instant cursorOccurredAt,
        String cursorId,
        int limit
    ) {
    }

    final class InMemoryPlatformEventRepository implements PlatformEventRepository {
        private final List<PlatformEventDto> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void append(PlatformEventDto event) {
            events.add(event);
            events.sort(java.util.Comparator.comparing(PlatformEventDto::occurredAt).thenComparing(PlatformEventDto::id).reversed());
        }

        @Override
        public List<PlatformEventDto> list(PlatformEventQuery query) {
            return events.stream()
                .filter(event -> query.aggregateType() == null || event.aggregateType() == query.aggregateType())
                .filter(event -> query.aggregateId() == null || query.aggregateId().equals(event.aggregateId()))
                .filter(event -> query.since() == null || !event.occurredAt().isBefore(query.since()))
                .filter(event -> {
                    if (query.cursorOccurredAt() == null || query.cursorId() == null) {
                        return true;
                    }
                    if (event.occurredAt().isBefore(query.cursorOccurredAt())) {
                        return true;
                    }
                    return event.occurredAt().equals(query.cursorOccurredAt()) && event.id().compareTo(query.cursorId()) < 0;
                })
                .limit(query.limit())
                .toList();
        }
    }
}
