package com.lynxus.platform.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileAccountSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobConfigWriteRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobScheduleType;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobScheduleWriteConfig;
import com.lynxus.contracts.channel.ChannelContracts.ChannelTemplateBindingWriteRequest;
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

    @Test
    void shouldSendExpectedRevisionOnProfileDelete() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> requestUri = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/channel-admin/profiles/channel-profile-1", exchange -> {
            method.set(exchange.getRequestMethod());
            requestUri.set(exchange.getRequestURI().toString());
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
                        "status": "INACTIVE",
                        "inboundEnabled": true,
                        "config": {"appId": "cli_xxx"},
                        "assistantBinding": null,
                        "accountId": "integration-account-1",
                        "hasExternalSecretRef": true,
                        "revision": 3,
                        "createdAt": "2026-04-23T00:00:00Z",
                        "updatedAt": "2026-04-23T00:01:00Z"
                      },
                      "timestamp": "2026-04-23T00:01:00Z"
                    }
                    """
            );
        });
        server.start();

        try {
            ChannelGatewayClient client = new ChannelGatewayClient(serverUrl(server), "internal-token", new ObjectMapper());
            var disabled = client.deleteProfile("channel-profile-1", 2L);

            assertEquals("DELETE", method.get());
            assertEquals("/internal/channel-admin/profiles/channel-profile-1?expectedRevision=2", requestUri.get());
            assertEquals(ChannelProfileStatus.INACTIVE, disabled.status());
            assertEquals(3, disabled.revision());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldTranslateConflictFromProfileDelete() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/channel-admin/profiles/channel-profile-1", exchange -> writeJson(exchange, 409, """
            {
              "type": "about:blank",
              "title": "Conflict",
              "status": 409,
              "detail": "channel profile revision conflict: channel-profile-1"
            }
            """));
        server.start();

        try {
            ChannelGatewayClient client = new ChannelGatewayClient(serverUrl(server), "internal-token", new ObjectMapper());
            ConflictException error = assertThrows(ConflictException.class, () -> client.deleteProfile("channel-profile-1", 1L));
            assertEquals("channel profile revision conflict: channel-profile-1", error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldProxyProviderJobConfigWritesToGateway() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> requestUri = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/channel-admin/profiles/channel-profile-1/jobs/PULL_MESSAGES", exchange -> {
            method.set(exchange.getRequestMethod());
            requestUri.set(exchange.getRequestURI().toString());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, """
                {
                  "success": true,
                  "data": {
                    "jobId": "channel-job-1",
                    "jobType": "PULL_MESSAGES",
                    "status": "ACTIVE",
                    "scheduleConfig": {
                      "scheduleType": "INTERVAL",
                      "intervalSeconds": 60,
                      "cronExpression": null,
                      "timezone": "UTC",
                      "jobTimeoutSeconds": 60,
                      "jobConfig": {}
                    },
                    "nextRunAt": "2026-04-23T00:01:00Z",
                    "lastRunAt": null,
                    "lastSuccessAt": null,
                    "lastError": null,
                    "failureCount": 0,
                    "revision": 1,
                    "createdAt": "2026-04-23T00:00:00Z",
                    "updatedAt": "2026-04-23T00:00:00Z"
                  },
                  "timestamp": "2026-04-23T00:00:00Z"
                }
                """);
        });
        server.start();

        try {
            ChannelGatewayClient client = new ChannelGatewayClient(serverUrl(server), "internal-token", new ObjectMapper());
            var job = client.upsertJob("channel-profile-1", "PULL_MESSAGES", new ChannelProviderJobConfigWriteRequest(
                new ChannelProviderJobScheduleWriteConfig(
                    true,
                    ChannelProviderJobScheduleType.INTERVAL,
                    60,
                    null,
                    null,
                    null,
                    Map.of()
                ),
                null
            ));

            assertEquals("PUT", method.get());
            assertEquals("/internal/channel-admin/profiles/channel-profile-1/jobs/PULL_MESSAGES", requestUri.get());
            assertTrue(requestBody.get().contains("\"enabled\":true"));
            assertEquals("PULL_MESSAGES", job.jobType());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldProxyTemplateBindingCrudToGateway() throws Exception {
        AtomicReference<String> putMethod = new AtomicReference<>();
        AtomicReference<String> putUri = new AtomicReference<>();
        AtomicReference<String> putBody = new AtomicReference<>();
        AtomicReference<String> deleteMethod = new AtomicReference<>();
        AtomicReference<String> deleteUri = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/channel-admin/profiles/channel-profile-1/template-bindings/assistant-1/CARD/ORDER_STATUS/v1", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) {
                putMethod.set(exchange.getRequestMethod());
                putUri.set(exchange.getRequestURI().toString());
                putBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                writeJson(exchange, 200, templateBindingJson(true, 1));
                return;
            }
            deleteMethod.set(exchange.getRequestMethod());
            deleteUri.set(exchange.getRequestURI().toString());
            writeJson(exchange, 200, templateBindingJson(false, 2));
        });
        server.start();

        try {
            ChannelGatewayClient client = new ChannelGatewayClient(serverUrl(server), "internal-token", new ObjectMapper());
            var saved = client.upsertTemplateBinding(
                "channel-profile-1",
                "assistant-1",
                "CARD",
                "ORDER_STATUS",
                "v1",
                new ChannelTemplateBindingWriteRequest(
                    "tpl_123",
                    "published",
                    Map.of("type", "object"),
                    "Order status",
                    null,
                    true,
                    null
                )
            );
            var disabled = client.deleteTemplateBinding("channel-profile-1", "assistant-1", "CARD", "ORDER_STATUS", "v1", 1L);

            assertEquals("PUT", putMethod.get());
            assertEquals("/internal/channel-admin/profiles/channel-profile-1/template-bindings/assistant-1/CARD/ORDER_STATUS/v1", putUri.get());
            assertTrue(putBody.get().contains("\"externalTemplateId\":\"tpl_123\""));
            assertEquals("tpl_123", saved.externalTemplateId());
            assertEquals("DELETE", deleteMethod.get());
            assertEquals("/internal/channel-admin/profiles/channel-profile-1/template-bindings/assistant-1/CARD/ORDER_STATUS/v1?expectedRevision=1", deleteUri.get());
            assertFalse(disabled.enabled());
            assertEquals(2, disabled.revision());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldProxyProviderJobDeleteExpectedRevisionToGateway() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> requestUri = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/channel-admin/profiles/channel-profile-1/jobs/PULL_MESSAGES", exchange -> {
            method.set(exchange.getRequestMethod());
            requestUri.set(exchange.getRequestURI().toString());
            writeJson(exchange, 200, """
                {
                  "success": true,
                  "data": {
                    "jobId": "channel-job-1",
                    "jobType": "PULL_MESSAGES",
                    "status": "DISABLED",
                    "scheduleConfig": {
                      "scheduleType": "INTERVAL",
                      "intervalSeconds": 60,
                      "cronExpression": null,
                      "timezone": "UTC",
                      "jobTimeoutSeconds": 60,
                      "jobConfig": {}
                    },
                    "nextRunAt": null,
                    "lastRunAt": null,
                    "lastSuccessAt": null,
                    "lastError": null,
                    "failureCount": 0,
                    "revision": 2,
                    "createdAt": "2026-04-23T00:00:00Z",
                    "updatedAt": "2026-04-23T00:00:00Z"
                  },
                  "timestamp": "2026-04-23T00:00:00Z"
                }
                """);
        });
        server.start();

        try {
            ChannelGatewayClient client = new ChannelGatewayClient(serverUrl(server), "internal-token", new ObjectMapper());
            client.deleteJob("channel-profile-1", "PULL_MESSAGES", 1L);

            assertEquals("DELETE", method.get());
            assertEquals("/internal/channel-admin/profiles/channel-profile-1/jobs/PULL_MESSAGES?expectedRevision=1", requestUri.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldProxyProviderJobManualRunToGateway() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> requestUri = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/channel-admin/profiles/channel-profile-1/jobs/PULL_MESSAGES/runs", exchange -> {
            method.set(exchange.getRequestMethod());
            requestUri.set(exchange.getRequestURI().toString());
            writeJson(exchange, 200, """
                {
                  "success": true,
                  "data": {
                    "id": "channel-job-run-1",
                    "runId": "channel-job-run-1",
                    "jobId": "channel-job-1",
                    "status": "SUCCEEDED",
                    "scheduledAt": "2026-04-23T00:00:00Z",
                    "startedAt": "2026-04-23T00:00:01Z",
                    "jobTimeoutSeconds": 60,
                    "finishedAt": "2026-04-23T00:00:02Z",
                    "durationMs": 1000,
                    "idempotencyKey": "channel-job-run:channel-job-run-1",
                    "attempt": 1,
                    "eventsIngested": 0,
                    "nextCursor": "cursor-2",
                    "error": {},
                    "metadata": {},
                    "createdAt": "2026-04-23T00:00:01Z",
                    "updatedAt": "2026-04-23T00:00:02Z"
                  },
                  "timestamp": "2026-04-23T00:00:02Z"
                }
                """);
        });
        server.start();

        try {
            ChannelGatewayClient client = new ChannelGatewayClient(serverUrl(server), "internal-token", new ObjectMapper());
            var run = client.runJob("channel-profile-1", "PULL_MESSAGES");

            assertEquals("POST", method.get());
            assertEquals("/internal/channel-admin/profiles/channel-profile-1/jobs/PULL_MESSAGES/runs", requestUri.get());
            assertEquals("cursor-2", run.nextCursor());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldTranslateUnprocessableProviderJobErrorsFromGateway() throws Exception {
        HttpServer server = errorServer(422, """
            {
              "type": "about:blank",
              "title": "Unprocessable Entity",
              "status": 422,
              "detail": "unknown channel provider jobType: PULL_MESSAGES"
            }
            """);

        try {
            ChannelGatewayClient client = new ChannelGatewayClient(serverUrl(server), "internal-token", new ObjectMapper());
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, client::listProfiles);
            assertEquals("unknown channel provider jobType: PULL_MESSAGES", error.getMessage());
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

    private static String templateBindingJson(boolean enabled, int revision) {
        return """
            {
              "success": true,
              "data": {
                "id": "channel-template-binding-1",
                "channelProfileId": "channel-profile-1",
                "assistantId": "assistant-1",
                "messageType": "CARD",
                "messageSubtype": "ORDER_STATUS",
                "messageVersion": "v1",
                "externalTemplateId": "tpl_123",
                "externalTemplateVersion": "published",
                "enabled": %s,
                "variableSchema": {"type": "object"},
                "displayName": "Order status",
                "externalEditUrl": null,
                "revision": %d,
                "createdAt": "2026-04-23T00:00:00Z",
                "updatedAt": "2026-04-23T00:00:00Z"
              },
              "timestamp": "2026-04-23T00:00:00Z"
            }
            """.formatted(enabled, revision);
    }
}
