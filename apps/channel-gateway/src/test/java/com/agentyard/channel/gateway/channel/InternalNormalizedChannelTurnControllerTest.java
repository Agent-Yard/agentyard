package com.agentyard.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentyard.channel.gateway.shared.ApiExceptionHandler;
import com.agentyard.contracts.channel.ChannelContracts.ChannelInboundTurnStatus;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelInboundTurn;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelInboundTurnResult;
import com.agentyard.extension.sdk.protocol.AgentYardExtensionHeaders;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class InternalNormalizedChannelTurnControllerTest {
    @Test
    void passesDescriptorTraceAndIdempotencyHeadersAndReturnsSynchronousDispatchResult() throws Exception {
        NormalizedChannelTurnIngestService service = mock(NormalizedChannelTurnIngestService.class);
        ChannelInboundTurnIngestResult ingestResult = ingestResult();
        when(service.ingest(any(), any())).thenReturn(ingestResult);
        ChannelInboundSessionDispatcher dispatcher = mock(ChannelInboundSessionDispatcher.class);
        when(dispatcher.dispatch(any(), any())).thenReturn(new NormalizedChannelInboundTurnResult(
            "channel-inbound-turn-1",
            "session-1",
            ChannelInboundTurnStatus.DISPATCHED,
            false,
            List.of("session-message-1"),
            List.of(),
            null
        ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InternalNormalizedChannelTurnController(service, dispatcher, new ObjectMapper()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/internal/channel-turns/normalized")
                .contentType(MediaType.APPLICATION_JSON)
                .header(AgentYardExtensionHeaders.REGISTRATION_ID, "acme-channel-provider")
                .header(AgentYardExtensionHeaders.DESCRIPTOR_TYPE, "CHANNEL_PROVIDER")
                .header(AgentYardExtensionHeaders.DESCRIPTOR_ID, "enterprise.acme.internal-im")
                .header(AgentYardExtensionHeaders.TRACE_ID, "trace-1")
                .header(AgentYardExtensionHeaders.REQUEST_ID, "request-1")
                .header(AgentYardExtensionHeaders.IDEMPOTENCY_KEY, "enterprise.acme.internal-im:turn:1")
                .content(validTurnRequest()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.turnId").value("channel-inbound-turn-1"))
            .andExpect(jsonPath("$.data.sessionId").value("session-1"))
            .andExpect(jsonPath("$.data.status").value("DISPATCHED"))
            .andExpect(jsonPath("$.data.acceptedMessageIds[0]").value("session-message-1"));

        ArgumentCaptor<NormalizedChannelEventHeaders> headers = ArgumentCaptor.forClass(NormalizedChannelEventHeaders.class);
        ArgumentCaptor<NormalizedChannelInboundTurn> turn = ArgumentCaptor.forClass(NormalizedChannelInboundTurn.class);
        verify(service).ingest(turn.capture(), headers.capture());
        verify(dispatcher).dispatch(turn.getValue(), ingestResult);
        assertEquals("enterprise.acme.internal-im:turn:1", headers.getValue().idempotencyKey());
    }

    @Test
    void rejectsMissingNormalizedPayloadBeforeIngestService() throws Exception {
        NormalizedChannelTurnIngestService service = mock(NormalizedChannelTurnIngestService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InternalNormalizedChannelTurnController(
                service,
                mock(ChannelInboundSessionDispatcher.class),
                new ObjectMapper()
            ))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/internal/channel-turns/normalized")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validTurnRequest().replace("\"normalizedPayload\": {},\n", "")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("normalized turn request.normalizedPayload is required"));

        verify(service, never()).ingest(any(), any());
    }

    private static String validTurnRequest() {
        return """
            {
              "providerType": "enterprise.acme.internal-im",
              "channelProfileId": "channel-profile-1",
              "dedupKey": "enterprise.acme.internal-im:turn:1",
              "externalConversationId": "chat-1",
              "externalUserId": "user-1",
              "conversation": {
                "externalConversationId": "chat-1",
                "type": "GROUP",
                "title": "Support",
                "metadata": {}
              },
              "sender": {
                "senderType": "CUSTOMER",
                "senderId": "user-1",
                "senderName": "Alice",
                "metadata": {}
              },
              "messages": [
                {
                  "externalEventId": "evt-1",
                  "externalMessageId": "msg-1",
                  "occurredAt": "2026-04-30T00:00:01Z",
                  "role": "USER",
                  "type": "TEXT",
                  "text": "hello",
                  "attachments": [],
                  "metadata": {}
                }
              ],
              "normalizedPayload": {},
              "rawPayload": {},
              "traceContext": {
                "traceparent": "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
              },
              "metadata": {}
            }
            """;
    }

    private static ChannelInboundTurnIngestResult ingestResult() {
        Instant now = Instant.parse("2026-04-30T00:00:00Z");
        return new ChannelInboundTurnIngestResult(new ChannelInboundTurnAudit(
            "channel-inbound-turn-1",
            "channel-profile-1",
            "enterprise.acme.internal-im",
            "enterprise.acme.internal-im:turn:1",
            "chat-1",
            "user-1",
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            ChannelInboundTurnStatus.RECEIVED,
            null,
            now,
            now
        ), false, null);
    }
}
