package com.agentyard.platform.session;

import java.lang.reflect.Array;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import static com.agentyard.platform.session.SessionRuntimeDtos.ExternalCallbackRequest;

@Component
public class ExternalCallbackIdempotencyKeyFactory {
    private final ObjectMapper objectMapper;

    public ExternalCallbackIdempotencyKeyFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String resolve(String sessionId, ExternalCallbackRequest request, String explicitIdempotencyKey) {
        if (explicitIdempotencyKey != null && !explicitIdempotencyKey.isBlank()) {
            return explicitIdempotencyKey.trim();
        }
        String playbookRunId = request == null || request.playbookRunId() == null ? "null" : request.playbookRunId();
        Object payload = request == null || request.payload() == null ? Map.of() : request.payload();
        return "external-callback:" + sessionId + ":" + playbookRunId + ":" + hashIdempotencySeed(stableStringify(payload));
    }

    String stableStringify(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = map.entrySet().stream()
                .sorted((left, right) -> String.valueOf(left.getKey()).compareTo(String.valueOf(right.getKey())))
                .collect(Collectors.toList());
            StringBuilder builder = new StringBuilder("{");
            for (int index = 0; index < entries.size(); index += 1) {
                if (index > 0) {
                    builder.append(',');
                }
                Map.Entry<?, ?> entry = entries.get(index);
                builder.append(writeJson(String.valueOf(entry.getKey())));
                builder.append(':');
                builder.append(stableStringify(entry.getValue()));
            }
            return builder.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder("[");
            int index = 0;
            for (Object item : iterable) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(stableStringify(item));
                index += 1;
            }
            return builder.append(']').toString();
        }
        if (value.getClass().isArray()) {
            StringBuilder builder = new StringBuilder("[");
            int length = Array.getLength(value);
            for (int index = 0; index < length; index += 1) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(stableStringify(Array.get(value, index)));
            }
            return builder.append(']').toString();
        }
        return writeJson(value);
    }

    static String hashIdempotencySeed(String seed) {
        int hash = 0x811c9dc5;
        for (int index = 0; index < seed.length(); index += 1) {
            hash ^= seed.charAt(index);
            hash *= 0x01000193;
        }
        return String.format("%08x", hash);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) { // noqa: BLE001
            throw new IllegalStateException("failed to serialize external callback idempotency payload", error);
        }
    }
}
