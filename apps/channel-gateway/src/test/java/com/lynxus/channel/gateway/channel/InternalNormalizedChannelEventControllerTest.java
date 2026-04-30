package com.lynxus.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.channel.gateway.shared.ApiExceptionHandler;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEventResult;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHeaders;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class InternalNormalizedChannelEventControllerTest {
    @Test
    void passesDescriptorTraceAndIdempotencyHeadersToIngestService() throws Exception {
        NormalizedChannelEventIngestService service = mock(NormalizedChannelEventIngestService.class);
        when(service.ingest(any(), any())).thenReturn(new NormalizedChannelInboundEventResult("channel-inbound-event-1", false));
        ChannelInboundSessionDispatcher dispatcher = mock(ChannelInboundSessionDispatcher.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InternalNormalizedChannelEventController(service, dispatcher, new ObjectMapper()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/internal/channel-events/normalized")
                .contentType(MediaType.APPLICATION_JSON)
                .header(LynxusExtensionHeaders.REGISTRATION_ID, "acme-channel-provider")
                .header(LynxusExtensionHeaders.DESCRIPTOR_TYPE, "CHANNEL_PROVIDER")
                .header(LynxusExtensionHeaders.DESCRIPTOR_ID, "enterprise.acme.internal-im")
                .header(LynxusExtensionHeaders.TRACE_ID, "trace-1")
                .header(LynxusExtensionHeaders.REQUEST_ID, "request-1")
                .header(LynxusExtensionHeaders.IDEMPOTENCY_KEY, "enterprise.acme.internal-im:message:msg-1")
                .content(validMessageRequest()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.eventId").value("channel-inbound-event-1"))
            .andExpect(jsonPath("$.data.duplicate").value(false));

        ArgumentCaptor<NormalizedChannelEventHeaders> headers = ArgumentCaptor.forClass(NormalizedChannelEventHeaders.class);
        ArgumentCaptor<NormalizedChannelInboundEvent> event = ArgumentCaptor.forClass(NormalizedChannelInboundEvent.class);
        verify(service).ingest(event.capture(), headers.capture());
        verify(dispatcher).dispatchAsync(event.getValue(), new NormalizedChannelInboundEventResult("channel-inbound-event-1", false));
        assertEquals(0, event.getValue().normalizedPayload().size());
        assertEquals("acme-channel-provider", headers.getValue().registrationId());
        assertEquals("CHANNEL_PROVIDER", headers.getValue().descriptorType());
        assertEquals("enterprise.acme.internal-im:message:msg-1", headers.getValue().idempotencyKey());
    }

    @Test
    void rejectsMissingNormalizedPayloadBeforeIngestService() throws Exception {
        NormalizedChannelEventIngestService service = mock(NormalizedChannelEventIngestService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InternalNormalizedChannelEventController(service, mock(ChannelInboundSessionDispatcher.class), new ObjectMapper()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/internal/channel-events/normalized")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validMessageRequest().replace("\"normalizedPayload\": {},\n", "")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("normalized event request.normalizedPayload is required"));

        verify(service, never()).ingest(any(), any());
    }

    @Test
    void rejectsNullNormalizedPayloadBeforeIngestService() throws Exception {
        NormalizedChannelEventIngestService service = mock(NormalizedChannelEventIngestService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InternalNormalizedChannelEventController(service, mock(ChannelInboundSessionDispatcher.class), new ObjectMapper()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/internal/channel-events/normalized")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validMessageRequest().replace("\"normalizedPayload\": {}", "\"normalizedPayload\": null")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("normalized event request.normalizedPayload must be an object"));

        verify(service, never()).ingest(any(), any());
    }

    @Test
    void acceptsExplicitEmptyNormalizedPayload() throws Exception {
        NormalizedChannelEventIngestService service = mock(NormalizedChannelEventIngestService.class);
        when(service.ingest(any(), any())).thenReturn(new NormalizedChannelInboundEventResult("channel-inbound-event-1", false));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InternalNormalizedChannelEventController(service, mock(ChannelInboundSessionDispatcher.class), new ObjectMapper()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/internal/channel-events/normalized")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validMessageRequest()))
            .andExpect(status().isOk());

        ArgumentCaptor<NormalizedChannelInboundEvent> event = ArgumentCaptor.forClass(NormalizedChannelInboundEvent.class);
        verify(service).ingest(event.capture(), any());
        assertEquals(0, event.getValue().normalizedPayload().size());
    }

    @Test
    void rejectsTopLevelExternalSecretRefBeforeIngestService() throws Exception {
        NormalizedChannelEventIngestService service = mock(NormalizedChannelEventIngestService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InternalNormalizedChannelEventController(service, mock(ChannelInboundSessionDispatcher.class), new ObjectMapper()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/internal/channel-events/normalized")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "providerType": "enterprise.acme.internal-im",
                      "channelProfileId": "channel-profile-1",
                      "eventType": "UNKNOWN",
                      "dedupKey": "enterprise.acme.internal-im:unknown:1",
                      "externalSecretRef": "vault://must-not-pass",
                      "normalizedPayload": {},
                      "traceContext": {
                        "traceparent": "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
                      }
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("normalized event request must not contain externalSecretRef"));
    }

    private static String validMessageRequest() {
        return """
            {
              "providerType": "enterprise.acme.internal-im",
              "channelProfileId": "channel-profile-1",
              "eventType": "MESSAGE_RECEIVED",
              "externalEventId": "evt-1",
              "externalConversationId": "chat-1",
              "externalMessageId": "msg-1",
              "externalUserId": "user-1",
              "dedupKey": "enterprise.acme.internal-im:message:msg-1",
              "conversation": {
                "externalConversationId": "chat-1",
                "type": "GROUP",
                "title": "Support",
                "metadata": {}
              },
              "sender": {
                "externalUserId": "user-1",
                "displayName": "Alice",
                "metadata": {}
              },
              "message": {
                "externalMessageId": "msg-1",
                "type": "TEXT",
                "text": "hello",
                "attachments": [],
                "metadata": {}
              },
              "normalizedPayload": {},
              "rawPayload": {},
              "traceContext": {
                "traceparent": "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
              },
              "metadata": {}
            }
            """;
    }
}
