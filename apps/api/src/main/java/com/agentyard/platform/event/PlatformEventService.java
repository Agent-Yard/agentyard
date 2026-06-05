package com.agentyard.platform.event;

import com.agentyard.platform.auth.CurrentUserResolver;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.agentyard.platform.event.PlatformEventDtos.*;

@Service
public class PlatformEventService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final PlatformEventRepository repository;
    private final CurrentUserResolver currentUserResolver;
    private final boolean enabled;

    @Autowired
    public PlatformEventService(PlatformEventRepository repository, CurrentUserResolver currentUserResolver) {
        this.repository = repository;
        this.currentUserResolver = currentUserResolver;
        this.enabled = true;
    }

    private PlatformEventService() {
        this.repository = null;
        this.currentUserResolver = null;
        this.enabled = false;
    }

    public static PlatformEventService disabled() {
        return new PlatformEventService();
    }

    public void recordControlEvent(String eventType, PlatformAggregateType aggregateType, String aggregateId, Map<String, Object> payload) {
        if (!enabled) {
            return;
        }
        appendPlatformEvent(eventType, aggregateType, aggregateId, currentUserResolver.resolveCurrentUser().id(), payload, Instant.now());
    }

    public void appendPlatformEvent(
        String eventType,
        PlatformAggregateType aggregateType,
        String aggregateId,
        String actorId,
        Map<String, Object> payload,
        Instant occurredAt
    ) {
        if (!enabled) {
            return;
        }
        repository.append(new PlatformEventDto(
            "platform-event-" + UUID.randomUUID(),
            eventType,
            aggregateType,
            aggregateId,
            actorId,
            payload,
            occurredAt
        ));
    }

    public PlatformEventPageDto listEvents(
        PlatformAggregateType aggregateType,
        String aggregateId,
        Instant since,
        Integer limit,
        String cursor
    ) {
        if (aggregateId != null && aggregateType == null) {
            throw new IllegalArgumentException("aggregateType is required when aggregateId is provided");
        }
        if (!enabled) {
            return new PlatformEventPageDto(List.of(), null);
        }
        int boundedLimit = boundLimit(limit);
        CursorValue cursorValue = decodeCursor(cursor);
        List<PlatformEventDto> rows = repository.list(new PlatformEventRepository.PlatformEventQuery(
            aggregateType,
            aggregateId,
            since,
            cursorValue == null ? null : cursorValue.occurredAt(),
            cursorValue == null ? null : cursorValue.id(),
            boundedLimit + 1
        ));
        List<PlatformEventDto> items = rows.size() > boundedLimit ? rows.subList(0, boundedLimit) : rows;
        String nextCursor = rows.size() > boundedLimit && !items.isEmpty()
            ? encodeCursor(items.getLast().occurredAt(), items.getLast().id())
            : null;
        return new PlatformEventPageDto(items, nextCursor);
    }

    private int boundLimit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(requested, MAX_LIMIT));
    }

    private String encodeCursor(Instant occurredAt, String id) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString((occurredAt.toString() + "\n" + id).getBytes(StandardCharsets.UTF_8));
    }

    private CursorValue decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('\n');
            if (separator < 0) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new CursorValue(Instant.parse(decoded.substring(0, separator)), decoded.substring(separator + 1));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("invalid cursor", error);
        }
    }

    private record CursorValue(Instant occurredAt, String id) {
    }
}
