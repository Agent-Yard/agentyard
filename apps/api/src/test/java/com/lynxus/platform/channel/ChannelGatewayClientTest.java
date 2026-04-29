package com.lynxus.platform.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileAccountSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelProfileInternalRequest;
import com.lynxus.platform.shared.ConflictException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChannelGatewayClientTest {
    @Test
    void shouldSendInternalBearerToken() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/channel-admin/profiles", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            writeJson(
                exchange,
                200,
                """
                    {
                      "success": true,
                      "data": [],
                      "timestamp": "2026-04-23T00:00:00Z"
                    }
                    """
            );
        });
        server.start();

        try {
            ChannelGatewayClient client = new ChannelGatewayClient(serverUrl(server), "internal-token", new ObjectMapper());
            client.listProfiles();
            assertEquals("Bearer internal-token", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldSendInternalProfileAccountSnapshotOnCreate() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/channel-admin/profiles", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(
                exchange,
                200,
                """
                    {
                      "success": true,
                      "data": {
                        "id": "channel-profile-1",
                        "providerType": "feishu",
                        "displayName": "飞书客服机器人",
                        "status": "ACTIVE",
                        "inboundEnabled": true,
                        "config": {"appId": "cli_xxx"},
                        "assistantBinding": null,
                        "accountId": "integration-account-1",
                        "hasExternalSecretRef": true,
                        "revision": 1,
                        "createdAt": "2026-04-23T00:00:00Z",
                        "updatedAt": "2026-04-23T00:00:00Z"
                      },
                      "timestamp": "2026-04-23T00:00:00Z"
                    }
                    """
            );
        });
        server.start();

        try {
            ChannelGatewayClient client = new ChannelGatewayClient(serverUrl(server), "internal-token", new ObjectMapper());
            client.createProfile(new CreateChannelProfileInternalRequest(
                "feishu",
                "飞书客服机器人",
                ChannelProfileStatus.ACTIVE,
                true,
                Map.of("appId", "cli_xxx"),
                null,
                new ChannelProfileAccountSnapshot("integration-account-1", "vault://opaque-ref")
            ));

            assertTrue(requestBody.get().contains("\"accountSnapshot\""));
            assertTrue(requestBody.get().contains("\"externalSecretRef\":\"vault://opaque-ref\""));
            assertFalse(requestBody.get().contains("integrationAccountId"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldTranslateBadRequestFromChannelGateway() throws Exception {
        HttpServer server = errorServer(400, """
            {
              "type": "about:blank",
              "title": "Bad Request",
              "status": 400,
              "detail": "channel profile name is required"
            }
            """);

        try {
            ChannelGatewayClient client = new ChannelGatewayClient(serverUrl(server), "internal-token", new ObjectMapper());
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, client::listProfiles);
            assertEquals("channel profile name is required", error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldTranslateNotFoundFromChannelGateway() throws Exception {
        HttpServer server = errorServer(404, """
            {
              "type": "about:blank",
              "title": "Not Found",
              "status": 404,
              "detail": "channel profile not found"
            }
            """);

        try {
            ChannelGatewayClient client = new ChannelGatewayClient(serverUrl(server), "internal-token", new ObjectMapper());
            NoSuchElementException error = assertThrows(NoSuchElementException.class, client::listProfiles);
            assertEquals("channel profile not found", error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldTranslateConflictFromChannelGateway() throws Exception {
        HttpServer server = errorServer(409, """
            {
              "type": "about:blank",
              "title": "Conflict",
              "status": 409,
              "detail": "channel profile already exists"
            }
            """);

        try {
            ChannelGatewayClient client = new ChannelGatewayClient(serverUrl(server), "internal-token", new ObjectMapper());
            ConflictException error = assertThrows(ConflictException.class, client::listProfiles);
            assertEquals("channel profile already exists", error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private static String serverUrl(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static HttpServer errorServer(int statusCode, String payload) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/channel-admin/profiles", exchange -> writeJson(exchange, statusCode, payload));
        server.start();
        return server;
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int statusCode, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }
}
