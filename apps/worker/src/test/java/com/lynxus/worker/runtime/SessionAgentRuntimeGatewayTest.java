package com.lynxus.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lynxus.contracts.session.SessionContracts.AgentConfig;
import com.lynxus.contracts.session.SessionContracts.AgentTurnRequest;
import com.lynxus.contracts.session.SessionContracts.AgentTurnStreamFrame;
import com.lynxus.contracts.session.SessionContracts.SessionTrigger;
import com.lynxus.contracts.session.SessionContracts.SessionTriggerType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SessionAgentRuntimeGatewayTest {
    @Test
    void shouldFailAndRelaySyntheticErrorWhenRuntimeStreamMissesFinalOutcome() throws Exception {
        CopyOnWriteArrayList<String> relayedFrames = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/agent-turns/execute-stream", exchange -> writeNdjson(exchange, turnStartedFrame()));
        server.createContext("/api/internal/session-runtime/stream-frames", exchange -> {
            relayedFrames.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, "{\"success\":true}");
        });
        server.start();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        try {
            SessionAgentRuntimeGateway gateway = gateway(server, meterRegistry, Duration.ofMillis(500));

            IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> gateway.executeTurnStream(request())
            );

            assertEquals("agent-runtime stream ended without FINAL_OUTCOME", error.getMessage());
            assertEquals(2, relayedFrames.size());
            assertEquals(1.0, meterRegistry.get("lynxus.runtime_stream.missing_final_outcome").counter().count());
            org.assertj.core.api.Assertions.assertThat(relayedFrames.get(1)).contains("\"kind\":\"ERROR\"");
            org.assertj.core.api.Assertions.assertThat(relayedFrames.get(1)).contains("\"code\":\"MISSING_FINAL_OUTCOME\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFailAndRelaySyntheticErrorWhenRuntimeStreamStalls() throws Exception {
        CopyOnWriteArrayList<String> relayedFrames = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/agent-turns/execute-stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(500);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.createContext("/api/internal/session-runtime/stream-frames", exchange -> {
            relayedFrames.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, "{\"success\":true}");
        });
        server.start();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        try {
            SessionAgentRuntimeGateway gateway = gateway(server, meterRegistry, Duration.ofMillis(100));

            IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> gateway.executeTurnStream(request())
            );

            org.assertj.core.api.Assertions.assertThat(error)
                .hasRootCauseMessage("agent-runtime stream stalled for PT0.1S");
            assertEquals(1.0, meterRegistry.get("lynxus.runtime_stream.stream_stall").counter().count());
            assertEquals(1, relayedFrames.size());
            org.assertj.core.api.Assertions.assertThat(relayedFrames.getFirst()).contains("\"code\":\"WORKER_STREAM_STALL\"");
        } finally {
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void shouldFailAndRelaySyntheticErrorWhenRuntimeStreamIsMalformed() throws Exception {
        CopyOnWriteArrayList<String> relayedFrames = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/agent-turns/execute-stream", exchange -> writeNdjson(exchange, "{malformed-json}\n"));
        server.createContext("/api/internal/session-runtime/stream-frames", exchange -> {
            relayedFrames.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, "{\"success\":true}");
        });
        server.start();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        try {
            SessionAgentRuntimeGateway gateway = gateway(server, meterRegistry, Duration.ofMillis(500));

            assertThrows(IllegalStateException.class, () -> gateway.executeTurnStream(request()));

            assertEquals(1, relayedFrames.size());
            org.assertj.core.api.Assertions.assertThat(relayedFrames.getFirst()).contains("\"kind\":\"ERROR\"");
            org.assertj.core.api.Assertions.assertThat(relayedFrames.getFirst()).contains("\"code\":\"WORKER_STREAM_ABORTED\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRelaySyntheticErrorWhenRuntimeStreamReturnsDuplicateFinalOutcome() throws Exception {
        CopyOnWriteArrayList<String> relayedFrames = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
            "/agent-turns/execute-stream",
            exchange -> writeNdjson(
                exchange,
                turnStartedFrame() + "\n" + finalOutcomeFrame(2, true) + "\n" + finalOutcomeFrame(3, true)
            )
        );
        server.createContext("/api/internal/session-runtime/stream-frames", exchange -> {
            relayedFrames.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, "{\"success\":true}");
        });
        server.start();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        try {
            SessionAgentRuntimeGateway gateway = gateway(server, meterRegistry, Duration.ofMillis(500));

            assertThrows(IllegalStateException.class, () -> gateway.executeTurnStream(request()));

            assertEquals(2, relayedFrames.size());
            org.assertj.core.api.Assertions.assertThat(relayedFrames.get(0)).contains("\"kind\":\"TURN_STARTED\"");
            org.assertj.core.api.Assertions.assertThat(relayedFrames.get(1)).contains("\"kind\":\"ERROR\"");
            org.assertj.core.api.Assertions.assertThat(relayedFrames.get(1)).contains("\"code\":\"DUPLICATE_FINAL_OUTCOME\"");
            org.assertj.core.api.Assertions.assertThat(relayedFrames.get(1)).contains("\"frameId\":\"exec-1:4\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRelaySyntheticErrorWhenFinalOutcomePayloadIsMissing() throws Exception {
        CopyOnWriteArrayList<String> relayedFrames = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/agent-turns/execute-stream", exchange -> writeNdjson(exchange, finalOutcomeFrame(1, false)));
        server.createContext("/api/internal/session-runtime/stream-frames", exchange -> {
            relayedFrames.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, "{\"success\":true}");
        });
        server.start();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        try {
            SessionAgentRuntimeGateway gateway = gateway(server, meterRegistry, Duration.ofMillis(500));

            assertThrows(IllegalStateException.class, () -> gateway.executeTurnStream(request()));

            assertEquals(1, relayedFrames.size());
            org.assertj.core.api.Assertions.assertThat(relayedFrames.getFirst()).contains("\"kind\":\"ERROR\"");
            org.assertj.core.api.Assertions.assertThat(relayedFrames.getFirst()).contains("\"code\":\"INVALID_FINAL_OUTCOME\"");
            org.assertj.core.api.Assertions.assertThat(relayedFrames.getFirst()).contains("\"frameId\":\"exec-1:2\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRelaySyntheticErrorWhenApiRejectsRuntimeFrame() throws Exception {
        CopyOnWriteArrayList<String> relayedFrames = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/agent-turns/execute-stream", exchange -> writeNdjson(exchange, customerDraftDeltaFrame()));
        server.createContext("/api/internal/session-runtime/stream-frames", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            relayedFrames.add(body);
            if (body.contains("\"visibility\":\"CUSTOMER\"")) {
                writeJson(exchange, 400, "{\"error\":\"customer frame rejected\"}");
            } else {
                writeJson(exchange, "{\"success\":true}");
            }
        });
        server.start();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        try {
            SessionAgentRuntimeGateway gateway = gateway(server, meterRegistry, Duration.ofMillis(500));

            assertThrows(IllegalStateException.class, () -> gateway.executeTurnStream(request()));

            assertEquals(2, relayedFrames.size());
            org.assertj.core.api.Assertions.assertThat(relayedFrames.get(0)).contains("\"visibility\":\"CUSTOMER\"");
            org.assertj.core.api.Assertions.assertThat(relayedFrames.get(1)).contains("\"kind\":\"ERROR\"");
            org.assertj.core.api.Assertions.assertThat(relayedFrames.get(1)).contains("\"visibility\":\"OPERATOR\"");
            org.assertj.core.api.Assertions.assertThat(relayedFrames.get(1)).contains("\"code\":\"STREAM_RELAY_FAILED\"");
            org.assertj.core.api.Assertions.assertThat(relayedFrames.get(1)).contains("\"frameId\":\"exec-1:2\"");
        } finally {
            server.stop(0);
        }
    }

    private static SessionAgentRuntimeGateway gateway(
        HttpServer server,
        SimpleMeterRegistry meterRegistry,
        Duration streamIdleTimeout
    ) {
        return new SessionAgentRuntimeGateway.HttpSessionAgentRuntimeGateway(
            serverUrl(server),
            serverUrl(server),
            "internal-token",
            new ObjectMapper(),
            meterRegistry,
            streamIdleTimeout
        );
    }

    private static AgentTurnRequest request() {
        AgentConfig owner = new AgentConfig(
            "agent-1",
            "Agent",
            "OWNER",
            "Handle the session",
            null,
            null,
            false,
            "",
            false,
            null,
            null,
            0,
            true,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
        return new AgentTurnRequest(
            "session-1",
            "turn-1",
            "exec-1",
            1,
            "assistant-1",
            "1.0.0",
            owner,
            List.of(owner),
            List.of(),
            null,
            Map.of(),
            null,
            false,
            new SessionTrigger(SessionTriggerType.USER_MESSAGE, "event-1", null, Map.of()),
            List.of(),
            List.of()
        );
    }

    private static String turnStartedFrame() {
        return (
            "{\"protocol\":\"%s\",\"frameId\":\"exec-1:1\",\"streamId\":\"stream-1\",\"sessionId\":\"session-1\","
                + "\"turnId\":\"turn-1\",\"turnExecutionId\":\"exec-1\",\"ownerAgentId\":\"agent-1\","
                + "\"ownershipEpoch\":1,\"seq\":1,\"kind\":\"TURN_STARTED\",\"visibility\":\"OPERATOR\","
                + "\"occurredAt\":\"2026-05-03T00:00:00Z\",\"payload\":{}}"
        ).formatted(AgentTurnStreamFrame.PROTOCOL);
    }

    private static String finalOutcomeFrame(long seq, boolean includeOutcome) {
        String payload = includeOutcome
            ? "\"payload\":{\"outcome\":{\"success\":false,\"failureReason\":\"done\",\"llmUsage\":[]}}"
            : "\"payload\":{}";
        return (
            "{\"protocol\":\"%s\",\"frameId\":\"exec-1:%d\",\"streamId\":\"stream-1\",\"sessionId\":\"session-1\","
                + "\"turnId\":\"turn-1\",\"turnExecutionId\":\"exec-1\",\"ownerAgentId\":\"agent-1\","
                + "\"ownershipEpoch\":1,\"seq\":%d,\"kind\":\"FINAL_OUTCOME\",\"visibility\":\"INTERNAL\","
                + "\"occurredAt\":\"2026-05-03T00:00:0%dZ\",%s}"
        ).formatted(AgentTurnStreamFrame.PROTOCOL, seq, seq, seq, payload);
    }

    private static String customerDraftDeltaFrame() {
        return (
            "{\"protocol\":\"%s\",\"frameId\":\"exec-1:1\",\"streamId\":\"stream-1\",\"sessionId\":\"session-1\","
                + "\"turnId\":\"turn-1\",\"turnExecutionId\":\"exec-1\",\"ownerAgentId\":\"agent-1\","
                + "\"ownershipEpoch\":1,\"seq\":1,\"kind\":\"REPLY_BLOCK_DELTA\",\"visibility\":\"CUSTOMER\","
                + "\"occurredAt\":\"2026-05-03T00:00:01Z\","
                + "\"payload\":{\"blockId\":\"block-1\",\"blockType\":\"TEXT\",\"delta\":\"hello\"}}"
        ).formatted(AgentTurnStreamFrame.PROTOCOL);
    }

    private static String serverUrl(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static void writeNdjson(HttpExchange exchange, String payload) throws IOException {
        byte[] body = (payload.endsWith("\n") ? payload : payload + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private static void writeJson(HttpExchange exchange, String payload) throws IOException {
        writeJson(exchange, 200, payload);
    }

    private static void writeJson(HttpExchange exchange, int statusCode, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }
}
