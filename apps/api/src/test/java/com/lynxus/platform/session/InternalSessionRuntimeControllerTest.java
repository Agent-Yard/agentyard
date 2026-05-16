package com.lynxus.platform.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.contracts.session.SessionContracts.AgentTurnTransientFrame;
import com.lynxus.contracts.session.SessionContracts.SendSessionTurnResponse;
import com.lynxus.contracts.session.SessionContracts.SessionMessageDeliveryStatus;
import com.lynxus.contracts.session.SessionContracts.TrustedImportSessionTurnRequest;
import com.lynxus.platform.integration.InternalRuntimeAuth;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class InternalSessionRuntimeControllerTest {
    @Test
    void shouldAcceptTrustedImportTurnJsonWithoutTargetDiscriminator() throws Exception {
        SessionRuntimeService sessionRuntimeService = mock(SessionRuntimeService.class);
        when(sessionRuntimeService.importTurn(any(), eq("turn-1"))).thenReturn(new SendSessionTurnResponse(
            "session-1",
            "turn-1",
            SessionMessageDeliveryStatus.ACCEPTED,
            java.util.List.of("message-1"),
            java.util.List.of(),
            java.util.List.of(),
            null
        ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InternalSessionRuntimeController(
            sessionRuntimeService,
            mock(SessionRuntimeStreamService.class),
            new InternalRuntimeAuth("internal-token"),
            new ObjectMapper()
        )).build();

        mockMvc.perform(post("/api/internal/session-runtime/import-turns")
                .header("Authorization", "Bearer internal-token")
                .header("Idempotency-Key", "turn-1")
                .contentType("application/json")
                .content("""
                    {
                      "target": {
                        "sessionId": "session-1",
                        "customerId": "customer-1",
                        "assistantId": "ast-1"
                      },
                      "turnDedupKey": "turn-1",
                      "importBatchId": "batch-1",
                      "sourceSystem": "crm",
                      "messages": [
                        {
                          "importMessageId": "import-1",
                          "occurredAt": "2026-04-01T00:00:00Z",
                          "role": "ASSISTANT",
                          "sender": {
                            "senderType": "AGENT",
                            "senderId": "agent-1",
                            "senderName": "Agent"
                          },
                          "message": {
                            "blocks": [
                              {
                                "type": "TEXT",
                                "text": "历史回复"
                              }
                            ],
                            "metadata": {}
                          },
                          "metadata": {}
                        }
                      ],
                      "metadata": {}
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sessionId").value("session-1"));

        verify(sessionRuntimeService).importTurn(any(TrustedImportSessionTurnRequest.class), eq("turn-1"));
    }

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
                + "\"payload\":{\"replyMessageId\":\"session-message-reply-1\",\"triggerType\":\"USER_MESSAGE\","
                + "\"inputMessageCount\":1}}"
        ).formatted(AgentTurnTransientFrame.PROTOCOL, turnExecutionId, seq, turnId, turnExecutionId, seq);
    }
}
