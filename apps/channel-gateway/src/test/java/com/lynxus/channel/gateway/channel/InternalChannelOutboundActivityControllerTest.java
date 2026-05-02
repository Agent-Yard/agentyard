package com.lynxus.channel.gateway.channel;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.channel.gateway.shared.ApiExceptionHandler;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityResponse;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityResponseStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class InternalChannelOutboundActivityControllerTest {
    @Test
    void acceptsTypingStartActivity() throws Exception {
        OutboundActivityExecutionService service = mock(OutboundActivityExecutionService.class);
        when(service.send(org.mockito.ArgumentMatchers.any())).thenReturn(
            new ChannelOutboundActivityResponse(ChannelOutboundActivityResponseStatus.ACCEPTED, false, Map.of())
        );

        mockMvc(service).perform(post("/internal/channel-outbound/activities")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "channelProfileId": "channel-profile-1",
                      "assistantId": "assistant-1",
                      "externalConversationId": "chat-1",
                      "sessionId": "session-1",
                      "turnId": "turn-1",
                      "frameId": "exec-1:1",
                      "activityType": "TYPING_START",
                      "idempotencyKey": "activity:exec-1:1:TYPING_START",
                      "payload": {}
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ACCEPTED"));

        verify(service).send(argThat(request ->
            request.activityType() == ChannelOutboundActivityType.TYPING_START
                && "activity:exec-1:1:TYPING_START".equals(request.idempotencyKey())
        ));
    }

    private static MockMvc mockMvc(OutboundActivityExecutionService service) {
        return MockMvcBuilders.standaloneSetup(new InternalChannelOutboundActivityController(service, new ObjectMapper()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }
}
