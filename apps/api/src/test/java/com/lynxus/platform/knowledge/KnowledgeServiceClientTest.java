package com.lynxus.platform.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KnowledgeServiceClientTest {
    @Test
    void shouldSendInternalBearerToken() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/upload-sessions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            writeJson(
                exchange,
                """
                    {
                      "id": "upload-session-1",
                      "knowledgeBaseId": "kb-1",
                      "status": "OPEN",
                      "acceptedTypes": ["pdf"]
                    }
                    """
            );
        });
        server.start();

        try {
            KnowledgeServiceClient client = new KnowledgeServiceClient(serverUrl(server), "internal-token");

            var response = client.createUploadSession("kb-1");

            assertEquals("Bearer internal-token", authorization.get());
            assertEquals("upload-session-1", response.id());
            assertEquals("kb-1", response.knowledgeBaseId());
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
