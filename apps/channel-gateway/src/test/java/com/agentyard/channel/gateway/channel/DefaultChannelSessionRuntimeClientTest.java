package com.agentyard.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.agentyard.channel.gateway.shared.ApiResponse;
import com.agentyard.contracts.session.SessionContracts.AcceptedSessionMessageAllocation;
import com.agentyard.contracts.session.SessionContracts.ChannelInboundSessionTurnMessage;
import com.agentyard.contracts.session.SessionContracts.ChannelInboundSessionTurnRequest;
import com.agentyard.contracts.session.SessionContracts.ChannelInboundSessionTurnResponse;
import com.agentyard.contracts.session.SessionContracts.SessionMessageDeliveryStatus;
import com.agentyard.contracts.session.SessionContracts.SessionMessageInput;
import com.agentyard.contracts.session.SessionContracts.SessionMessageRole;
import com.agentyard.contracts.session.SessionContracts.SessionMessageSender;
import com.agentyard.contracts.session.SessionContracts.SessionMessageSenderType;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DefaultChannelSessionRuntimeClientTest {
    @Test
    void dispatchInboundTurnUsesInternalPathUnderApiBaseUrl() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestedPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/session-runtime/channel-inbound-turns", exchange -> {
            try {
                requestedPath.set(exchange.getRequestURI().getPath());
                assertEquals("POST", exchange.getRequestMethod());
                assertEquals("Bearer token", exchange.getRequestHeaders().getFirst("Authorization"));
                assertEquals("dedup-1", exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                ChannelInboundSessionTurnResponse response = new ChannelInboundSessionTurnResponse(
                    "session-1",
                    "turn-1",
                    SessionMessageDeliveryStatus.ACCEPTED,
                    List.of("message-1"),
                    List.of(new AcceptedSessionMessageAllocation(0, null, "message-1", 0)),
                    List.of(),
                    null
                );
                byte[] body = objectMapper.writeValueAsString(ApiResponse.ok(response)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            DefaultChannelSessionRuntimeClient client = new DefaultChannelSessionRuntimeClient(
                objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api",
                "token",
                HttpClient.newHttpClient()
            );

            ChannelInboundSessionTurnResponse response = client.dispatchInboundTurn(request());

            assertEquals("/api/internal/session-runtime/channel-inbound-turns", requestedPath.get());
            assertEquals("session-1", response.sessionId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dispatchInboundTurnIncludesRejectedResponseBodySummary() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/session-runtime/channel-inbound-turns", exchange -> {
            try {
                byte[] body = "{\"detail\":\"session has ended\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
                exchange.sendResponseHeaders(409, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            DefaultChannelSessionRuntimeClient client = new DefaultChannelSessionRuntimeClient(
                objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api",
                "token",
                HttpClient.newHttpClient()
            );

            ChannelInboundSessionRejectedException error = assertThrows(
                ChannelInboundSessionRejectedException.class,
                () -> client.dispatchInboundTurn(request())
            );

            assertEquals(
                "session runtime rejected channel inbound turn with HTTP 409: {\"detail\":\"session has ended\"}",
                error.getMessage()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dispatchInboundTurnRejectsMissingSenderNameBeforeHttp() {
        DefaultChannelSessionRuntimeClient client = new DefaultChannelSessionRuntimeClient(
            new ObjectMapper(),
            "http://127.0.0.1:1/api",
            "token",
            HttpClient.newHttpClient()
        );

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> client.dispatchInboundTurn(request(null))
        );

        assertEquals("channelInbound.messages[0].sender.senderName is required", error.getMessage());
    }

    private static ChannelInboundSessionTurnRequest request() {
        return request("customer-1");
    }

    private static ChannelInboundSessionTurnRequest request(String senderName) {
        return new ChannelInboundSessionTurnRequest(
            "channel-profile-1",
            "conversation-1",
            "dedup-1",
            "assistant-1",
            "customer-1",
            null,
            List.of(new ChannelInboundSessionTurnMessage(
                "event-1",
                "message-1",
                Instant.parse("2026-05-17T00:00:00Z"),
                SessionMessageRole.USER,
                new SessionMessageSender(SessionMessageSenderType.CUSTOMER, "customer-1", senderName),
                new SessionMessageInput(List.of(Map.of("type", "TEXT", "text", "hello")), Map.of()),
                Map.of()
            )),
            Map.of()
        );
    }
}
