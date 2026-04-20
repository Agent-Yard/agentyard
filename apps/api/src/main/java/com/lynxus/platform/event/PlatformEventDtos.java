package com.lynxus.platform.event;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlatformEventDtos {
    private PlatformEventDtos() {
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public enum PlatformAggregateType {
        DOMAIN,
        SCENARIO,
        ASSISTANT,
        AGENT,
        PLAYBOOK,
        RESOURCE,
        KNOWLEDGE_BASE,
        SESSION,
        PLAYBOOK_RUN
    }

    public record PlatformEventDto(
        String id,
        String eventType,
        PlatformAggregateType aggregateType,
        String aggregateId,
        String actorId,
        Map<String, Object> payload,
        Instant occurredAt
    ) {
        public PlatformEventDto {
            payload = immutableObjectMap(payload);
        }
    }

    public record PlatformEventPageDto(
        List<PlatformEventDto> items,
        String nextCursor
    ) {
        public PlatformEventPageDto {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
