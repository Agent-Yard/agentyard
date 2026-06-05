package com.agentyard.contracts.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HttpUrlsTest {
    @Test
    void shouldJoinBaseUrlAndPathWithSingleSlash() {
        assertEquals(
            "http://127.0.0.1:8080/internal/status",
            HttpUrls.joinToString("http://127.0.0.1:8080/", "internal/status")
        );
    }

    @Test
    void shouldJoinControlPlaneBaseUrlAndInternalPath() {
        assertEquals(
            "http://127.0.0.1:8080/api/internal/session-runtime/stream-frame-ingest",
            HttpUrls.joinToString(
                "http://127.0.0.1:8080/api",
                "/internal/session-runtime/stream-frame-ingest"
            )
        );
    }

    @Test
    void shouldPreserveRegularJoinSemanticsForExternalUrls() {
        assertEquals(
            "http://provider.local/api/api/send",
            HttpUrls.joinToString("http://provider.local/api", "/api/send")
        );
    }

    @Test
    void shouldRejectBlankInputs() {
        assertThrows(IllegalArgumentException.class, () -> HttpUrls.joinToString("", "/internal/status"));
        assertThrows(IllegalArgumentException.class, () -> HttpUrls.joinToString("http://127.0.0.1:8080", " "));
    }
}
