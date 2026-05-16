package com.lynxus.platform.shared.logging;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.platform.auth.AuthModels.AuthSource;
import com.lynxus.platform.auth.AuthModels.PlatformUser;
import com.lynxus.platform.auth.AuthModels.Role;
import com.lynxus.platform.auth.AuthModels.UserStatus;
import com.lynxus.platform.auth.CurrentUserResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

class ApiLogContextFilterTest {
    @Test
    void shouldBindTraceUserSessionAndCustomerContextFromRequest() throws Exception {
        MockMvc mockMvc = mockMvc();

        mockMvc.perform(post("/api/session-runtime/turns")
                .contentType(MediaType.APPLICATION_JSON)
                .header("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")
                .content("""
                    {
                      "sessionId": "session-1",
                      "customerId": "customer-1",
                      "turnDedupKey": "turn-1",
                      "messages": []
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string("traceparent", matchesPattern("00-0123456789abcdef0123456789abcdef-[0-9a-f]{16}-01")))
            .andExpect(jsonPath("$.traceId").value("0123456789abcdef0123456789abcdef"))
            .andExpect(jsonPath("$.sessionId").value("session-1"))
            .andExpect(jsonPath("$.customerId").value("customer-1"))
            .andExpect(jsonPath("$.userId").value("user-admin"));
    }

    @Test
    void shouldBindSessionIdFromDetailPath() throws Exception {
        MockMvc mockMvc = mockMvc();

        mockMvc.perform(get("/api/session-runtime/sessions/session-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value("session-1"))
            .andExpect(jsonPath("$.workflowId").value("null"));
    }

    private MockMvc mockMvc() {
        CurrentUserResolver currentUserResolver = () -> new PlatformUser(
            "user-admin",
            "admin",
            "平台管理员",
            "admin@lynxus.local",
            AuthSource.LOCAL_BOOTSTRAP,
            null,
            null,
            UserStatus.ACTIVE,
            Instant.parse("2026-04-01T00:00:00Z"),
            Instant.parse("2026-04-01T00:00:00Z"),
            Instant.parse("2026-04-01T00:00:00Z"),
            List.of(Role.PLATFORM_ADMIN)
        );
        return MockMvcBuilders.standaloneSetup(new EchoController())
            .addFilters(new ApiLogContextFilter(currentUserResolver, new ObjectMapper()))
            .build();
    }

    @RestController
    static class EchoController {
        @PostMapping("/api/session-runtime/turns")
        Map<String, String> message(@RequestBody Map<String, Object> payload) {
            return snapshot();
        }

        @GetMapping("/api/session-runtime/sessions/{sessionId}")
        Map<String, String> session(@PathVariable String sessionId) {
            return snapshot();
        }

        private Map<String, String> snapshot() {
            return Map.of(
                "traceId", String.valueOf(MDC.get("traceId")),
                "sessionId", String.valueOf(MDC.get("sessionId")),
                "workflowId", String.valueOf(MDC.get("workflowId")),
                "customerId", String.valueOf(MDC.get("customerId")),
                "userId", String.valueOf(MDC.get("userId"))
            );
        }
    }
}
