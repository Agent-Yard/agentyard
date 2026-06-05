package com.agentyard.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChannelOutboundApiFrameStreamClientTest {
    @Test
    void streamDoesNotApplyRequestTimeoutToLongLivedSse() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ChannelOutboundFrame frame = finalFrame();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/channel-outbound/frames/stream", exchange -> {
            try {
                assertEquals("GET", exchange.getRequestMethod());
                Thread.sleep(250);
                byte[] response = (
                    "id: cursor-1\n"
                        + "event: " + ChannelOutboundApiFrameStreamClient.CHANNEL_OUTBOUND_FRAME_EVENT + "\n"
                        + "data: " + objectMapper.writeValueAsString(frame) + "\n\n"
                ).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ChannelOutboundApiFrameStreamClient client = new ChannelOutboundApiFrameStreamClient(
                objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api",
                "token",
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(50)).build()
            );
            AtomicReference<ChannelOutboundFrame> received = new AtomicReference<>();

            assertTimeout(Duration.ofSeconds(3), () -> client.stream(
                new ChannelOutboundApiFrameStreamClient.StreamRequest("profile-1", null, null, null, null, 1),
                new ChannelOutboundApiFrameStreamClient.StreamHandler() {
                    @Override
                    public void onFrame(String streamCursor, ChannelOutboundFrame frame) {
                        assertEquals("cursor-1", streamCursor);
                        received.set(frame);
                    }

                    @Override
                    public void onFinalReplayWindowExhausted() {
                    }
                }
            ));

            assertEquals(frame.frameId(), received.get().frameId());
        } finally {
            server.stop(0);
        }
    }

    private static ChannelOutboundFrame finalFrame() {
        Instant now = Instant.parse("2026-05-05T00:00:00Z");
        return new ChannelOutboundFrame(
            "agentyard.channel-outbound-frame.v1",
            "profile-1:session-1:message-1:FINAL_DELIVERY",
            "profile-1",
            "feishu",
            "assistant-1",
            "conversation-1",
            "session-1",
            null,
            null,
            null,
            1L,
            ChannelOutboundFrameKind.FINAL_DELIVERY,
            now,
            "profile-1:session-1:message-1:FINAL_DELIVERY",
            Map.of(
                "sessionMessageId", "message-1",
                "messageSequence", 1,
                "messageBlocks", List.of(Map.of(
                    "type", "TEXT",
                    "text", "hello"
                ))
            ),
            null
        );
    }
}
