package com.agentyard.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WorkerHttpClientTest {
    @Test
    void sendsAuthenticatedJsonAndReadsUnwrappedControlPlanePayload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/api/internal/activations/a-1/finish", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"status\":\"COMPLETED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            WorkerHttpClient client = new WorkerHttpClient(new ObjectMapper(), "test-internal-token");
            Map<?, ?> result = client.post("http://127.0.0.1:" + server.getAddress().getPort() + "/api",
                "/internal/activations/a-1/finish", Map.of("status", "COMPLETED"), Map.class);
            assertEquals("Bearer test-internal-token", authorization.get());
            assertEquals(Map.of("status", "COMPLETED"), new ObjectMapper().readValue(requestBody.get(), Map.class));
            assertEquals("COMPLETED", result.get("status"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotExposePrivateUpstreamBodyInFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/private", exchange -> {
            byte[] response = "secret-tool-credential".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            WorkerHttpClient client = new WorkerHttpClient(new ObjectMapper(), "test-token");
            var error = assertThrows(WorkerHttpClient.WorkerHttpException.class, () ->
                client.get("http://127.0.0.1:" + server.getAddress().getPort(), "/private", Map.class));
            assertEquals(403, error.statusCode());
            assertFalse(error.getMessage().contains("secret-tool-credential"));
        } finally {
            server.stop(0);
        }
    }
}
