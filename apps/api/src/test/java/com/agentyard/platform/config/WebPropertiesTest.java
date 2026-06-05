package com.agentyard.platform.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class WebPropertiesTest {
    @Test
    void shouldFallbackToDefaultOriginsWhenInputIsMissing() {
        WebProperties properties = new WebProperties(null);

        assertEquals(List.of("http://localhost:5173", "http://127.0.0.1:5173"), properties.allowedOrigins());
    }

    @Test
    void shouldTrimDropBlanksAndDeduplicateOrigins() {
        WebProperties properties = new WebProperties(List.of(
            " http://devbox.example.com:8080 ",
            "",
            "http://devbox.example.com:8080",
            "http://localhost:5173"
        ));

        assertEquals(List.of("http://devbox.example.com:8080", "http://localhost:5173"), properties.allowedOrigins());
    }
}
