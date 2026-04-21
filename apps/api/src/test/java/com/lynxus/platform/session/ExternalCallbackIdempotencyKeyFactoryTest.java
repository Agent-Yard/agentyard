package com.lynxus.platform.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static com.lynxus.platform.session.SessionRuntimeDtos.ExternalCallbackRequest;

class ExternalCallbackIdempotencyKeyFactoryTest {
    private final ExternalCallbackIdempotencyKeyFactory factory = new ExternalCallbackIdempotencyKeyFactory(new ObjectMapper());

    @Test
    void shouldReuseExplicitIdempotencyKeyAfterTrimming() {
        String key = factory.resolve("session-1", new ExternalCallbackRequest("playbook-1", Map.of("ok", true)), "  custom-key  ");

        assertEquals("custom-key", key);
    }

    @Test
    void shouldGenerateStableKeyForEquivalentPayloads() {
        ExternalCallbackRequest first = new ExternalCallbackRequest(
            "playbook-1",
            Map.of(
                "nested", Map.of("b", 2, "a", 1),
                "items", List.of(Map.of("y", 2, "x", 1), "done"),
                "flag", true
            )
        );
        ExternalCallbackRequest second = new ExternalCallbackRequest(
            "playbook-1",
            Map.of(
                "flag", true,
                "items", List.of(Map.of("x", 1, "y", 2), "done"),
                "nested", Map.of("a", 1, "b", 2)
            )
        );

        String firstKey = factory.resolve("session-1", first, null);
        String secondKey = factory.resolve("session-1", second, null);

        assertEquals(firstKey, secondKey);
    }

    @Test
    void shouldDifferentiateDifferentPayloads() {
        String firstKey = factory.resolve("session-1", new ExternalCallbackRequest("playbook-1", Map.of("status", "approved")), null);
        String secondKey = factory.resolve("session-1", new ExternalCallbackRequest("playbook-1", Map.of("status", "rejected")), null);

        assertNotEquals(firstKey, secondKey);
    }
}
