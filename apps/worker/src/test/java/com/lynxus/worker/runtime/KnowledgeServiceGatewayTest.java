package com.lynxus.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KnowledgeServiceGatewayTest {
    @Test
    void shouldSendInternalBearerToken() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/import-jobs/job-1/run", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            writeJson(exchange, "{\"status\":\"SUCCEEDED\"}");
        });
        server.start();

        try {
            KnowledgeServiceGateway gateway = new KnowledgeServiceGateway.HttpKnowledgeServiceGateway(
                serverUrl(server),
                "internal-token"
            );

            String status = gateway.runImportJob("job-1");

            assertEquals("Bearer internal-token", authorization.get());
            assertEquals("SUCCEEDED", status);
        } finally {
            server.stop(0);
        }
    }

    private static String serverUrl(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }
}
