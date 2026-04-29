package com.lynxus.channel.gateway.channel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.channel.gateway.shared.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class InternalChannelOutboundControllerTest {
    @Test
    void rejectsTopLevelExternalSecretRefBeforeExecutionService() throws Exception {
        OutboundDeliveryExecutionService service = mock(OutboundDeliveryExecutionService.class);
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(post("/internal/channel-outbound/deliveries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest().replace("\"messageBlock\":", "\"externalSecretRef\": \"vault://must-not-pass\", \"messageBlock\":")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("outbound delivery request must not contain externalSecretRef"));

        verify(service, never()).deliver(any());
    }

    @Test
    void rejectsTopLevelProviderNativeTemplateFieldBeforeExecutionService() throws Exception {
        OutboundDeliveryExecutionService service = mock(OutboundDeliveryExecutionService.class);
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(post("/internal/channel-outbound/deliveries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest().replace("\"messageBlock\":", "\"templateKey\": \"provider-native\", \"messageBlock\":")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("outbound delivery request must not contain templateKey"));

        verify(service, never()).deliver(any());
    }

    @Test
    void rejectsNonObjectMessageBlockBeforeExecutionService() throws Exception {
        OutboundDeliveryExecutionService service = mock(OutboundDeliveryExecutionService.class);
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(post("/internal/channel-outbound/deliveries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest().replace("""
                      "messageBlock": {
                        "type": "TEXT",
                        "text": "hello"
                      }
                    """, """
                      "messageBlock": null
                    """)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("outbound delivery request.messageBlock must be an object"));

        verify(service, never()).deliver(any());
    }

    private static MockMvc mockMvc(OutboundDeliveryExecutionService service) {
        return MockMvcBuilders.standaloneSetup(new InternalChannelOutboundController(service, new ObjectMapper()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    private static String validRequest() {
        return """
            {
              "channelProfileId": "channel-profile-1",
              "assistantId": "assistant-1",
              "externalConversationId": "chat-1",
              "sessionId": "session-1",
              "sessionMessageId": "message-1",
              "messageBlock": {
                "type": "TEXT",
                "text": "hello"
              }
            }
            """;
    }
}
