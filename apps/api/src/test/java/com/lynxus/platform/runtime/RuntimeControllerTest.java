package com.lynxus.platform.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.platform.shared.ApiExceptionHandler;
import com.lynxus.platform.shared.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RuntimeControllerTest {
    @Test
    void shouldReturnConflictWhenSessionAlreadyHasActiveWorkflow() throws Exception {
        RuntimeService runtimeService = mock(RuntimeService.class);
        when(runtimeService.sendMessage(eq("session-1"), any(RuntimeDtos.ConversationMessageRequest.class)))
            .thenThrow(new ConflictException("session has an active workflow"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RuntimeController(runtimeService))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/api/runtime/sessions/session-1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "customerId": "customer-1",
                      "payloadType": "TEXT",
                      "payload": {
                        "text": "第二条消息"
                      }
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value("session has an active workflow"));
    }
}
