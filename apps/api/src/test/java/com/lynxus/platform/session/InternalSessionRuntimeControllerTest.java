package com.lynxus.platform.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.contracts.session.SessionContracts.AgentTurnTransientFrame;
import com.lynxus.platform.integration.InternalRuntimeAuth;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class InternalSessionRuntimeControllerTest {
    @Test
    void shouldIngestTransientFramesFromNdjsonUpload() throws Exception {
        SessionRuntimeStreamService streamService = mock(SessionRuntimeStreamService.class);
        when(streamService.acceptStreamFrame(any())).thenReturn(true, false);
        MockMvc mockMvc = mockMvc(streamService);

        mockMvc.perform(post("/api/internal/session-runtime/stream-frame-ingest")
                .header("Authorization", "Bearer internal-token")
                .contentType("application/x-ndjson")
                .content(turnStartedFrame("turn-1", "exec-1", 1) + "\n" + turnStartedFrame("turn-1", "exec-1", 2) + "\n"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.turnExecutionId").value("exec-1"))
            .andExpect(jsonPath("$.data.frames").value(2))
            .andExpect(jsonPath("$.data.accepted").value(1))
            .andExpect(jsonPath("$.data.duplicates").value(1));

        verify(streamService, org.mockito.Mockito.times(2)).acceptStreamFrame(any());
    }

    @Test
    void shouldRejectMixedTurnExecutionsInOneNdjsonUpload() throws Exception {
        MockMvc mockMvc = mockMvc(mock(SessionRuntimeStreamService.class));

        mockMvc.perform(post("/api/internal/session-runtime/stream-frame-ingest")
                .header("Authorization", "Bearer internal-token")
                .contentType("application/x-ndjson")
                .content(turnStartedFrame("turn-1", "exec-1", 1) + "\n" + turnStartedFrame("turn-2", "exec-2", 2) + "\n"))
            .andExpect(status().isBadRequest());
    }

    private static MockMvc mockMvc(SessionRuntimeStreamService streamService) {
        return MockMvcBuilders.standaloneSetup(new InternalSessionRuntimeController(
            mock(SessionRuntimeService.class),
            streamService,
            mock(SessionChannelOutboundRelay.class),
            new InternalRuntimeAuth("internal-token"),
            new ObjectMapper()
        )).build();
    }

    private static String turnStartedFrame(String turnId, String turnExecutionId, long seq) {
        return (
            "{\"protocol\":\"%s\",\"frameId\":\"%s:%d\",\"streamId\":\"stream-1\",\"sessionId\":\"session-1\","
                + "\"turnId\":\"%s\",\"turnExecutionId\":\"%s\",\"ownerAgentId\":\"agent-1\","
                + "\"ownershipEpoch\":1,\"seq\":%d,\"kind\":\"TURN_STARTED\",\"visibility\":\"OPERATOR\","
                + "\"occurredAt\":\"2026-05-03T00:00:00Z\","
                + "\"payload\":{\"messageId\":\"session-message-reply-1\",\"triggerType\":\"USER_MESSAGE\"}}"
        ).formatted(AgentTurnTransientFrame.PROTOCOL, turnExecutionId, seq, turnId, turnExecutionId, seq);
    }
}
