package com.agentyard.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.agentyard.channel.gateway.shared.ApiResponse;
import com.agentyard.contracts.channel.ChannelContracts.ChannelReceptionAllocation;
import com.agentyard.contracts.channel.ChannelContracts.ChannelReceptionMessage;
import com.agentyard.contracts.channel.ChannelContracts.ChannelReceptionRequest;
import com.agentyard.contracts.channel.ChannelContracts.ChannelReceptionResponse;
import com.agentyard.contracts.runtime.RuntimeContracts.MessageContent;
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

class DefaultChannelReceptionClientTest {
    @Test
    void dispatchInboundTurnUsesInternalPathUnderApiBaseUrl() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestedPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/channel-reception/messages", exchange -> {
            try {
                requestedPath.set(exchange.getRequestURI().getPath());
                assertEquals("POST", exchange.getRequestMethod());
                assertEquals("Bearer token", exchange.getRequestHeaders().getFirst("Authorization"));
                assertEquals("dedup-1", exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                ChannelReceptionResponse response = new ChannelReceptionResponse(
                    "session-1",
                    List.of(new ChannelReceptionAllocation(0, "message-1", "session-1", "participant-1")),
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
            DefaultChannelReceptionClient client = new DefaultChannelReceptionClient(
                objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api",
                "token",
                HttpClient.newHttpClient()
            );

            ChannelReceptionResponse response = client.dispatchInboundTurn(request());

            assertEquals("/api/internal/channel-reception/messages", requestedPath.get());
            assertEquals("session-1", response.sessionId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dispatchInboundTurnIncludesRejectedResponseBodySummary() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/channel-reception/messages", exchange -> {
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
            DefaultChannelReceptionClient client = new DefaultChannelReceptionClient(
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
                "Agent reception rejected channel inbound turn with HTTP 409: {\"detail\":\"session has ended\"}",
                error.getMessage()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dispatchInboundTurnRejectsMissingSenderNameBeforeHttp() {
        DefaultChannelReceptionClient client = new DefaultChannelReceptionClient(
            new ObjectMapper(),
            "http://127.0.0.1:1/api",
            "token",
            HttpClient.newHttpClient()
        );

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> client.dispatchInboundTurn(request(null))
        );

        assertEquals("channelInbound.messages[0].displayName is required", error.getMessage());
    }

    private static ChannelReceptionRequest request() {
        return request("customer-1");
    }

    private static ChannelReceptionRequest request(String senderName) {
        return new ChannelReceptionRequest(
            "channel-profile-1",
            "conversation-1",
            "dedup-1",
            "entry-1",
            List.of(new ChannelReceptionMessage(
                "message-1", "customer-1", senderName,
                new MessageContent(List.of(Map.of("type", "TEXT", "text", "hello")), Map.of())
            )),
            Map.of()
        );
    }
}
