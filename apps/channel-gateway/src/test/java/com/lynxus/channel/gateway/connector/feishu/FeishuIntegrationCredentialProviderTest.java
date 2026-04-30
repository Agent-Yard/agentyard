package com.lynxus.channel.gateway.connector.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FeishuIntegrationCredentialProviderTest {
    @Test
    void loadsAppCredentialFromInternalIntegrationAccountRuntimeEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/internal/integration/accounts/account-1/credential", exchange -> {
            assertEquals("Bearer internal-token", exchange.getRequestHeaders().getFirst("Authorization"));
            writeJson(exchange, 200, """
                {
                  "success": true,
                  "data": {
                    "accountId": "account-1",
                    "subjectType": "CHANNEL_PROVIDER",
                    "subjectId": "feishu",
                    "status": "ENABLED",
                    "config": {
                      "appId": "cli_test"
                    },
                    "credential": {
                      "appSecret": "secret_test"
                    }
                  }
                }
                """);
        });
        server.start();
        try {
            FeishuIntegrationCredentialProvider provider = new FeishuIntegrationCredentialProvider(
                new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort() + "/api",
                "internal-token",
                java.net.http.HttpClient.newHttpClient()
            );

            FeishuAppCredential credential = provider.resolve("account-1", Map.of());

            assertEquals("account-1", credential.accountId());
            assertEquals("cli_test", credential.appId());
            assertEquals("secret_test", credential.appSecret());
        } finally {
            server.stop(0);
        }
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
