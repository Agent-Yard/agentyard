package com.lynxus.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import com.lynxus.channel.gateway.extension.ChannelGatewayDescriptorProvider;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistryLoader;
import com.lynxus.channel.gateway.extension.ExtensionRegistrationProperties;
import com.lynxus.channel.gateway.extension.ExtensionRegistrationService;
import com.lynxus.channel.gateway.extension.RuntimeChannelProviderRegistry;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobScheduleConfig;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobScheduleType;
import com.lynxus.extension.sdk.common.LynxusCanonicalJson;
import com.lynxus.extension.sdk.protocol.JsonDocuments;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHeaders;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RemoteProviderJobExecutorTest {
    @Test
    void sendsDescriptorHeadersIdempotencyAndRunJobEnvelopeWithExternalSecretRef() throws Exception {
        AtomicReference<Headers> headers = new AtomicReference<>();
        AtomicReference<Map<String, Object>> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/extension/manifest", exchange -> writeJson(exchange, 200, manifest(channelDescriptor())));
        server.createContext("/channel/run-job", exchange -> {
            headers.set(exchange.getRequestHeaders());
            requestBody.set(JsonDocuments.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            writeJson(exchange, 200, """
                {
                  "status": "NOOP",
                  "nextCursor": "cursor-2",
                  "events": [],
                  "metadata": {"providerStatus": "ok"}
                }
                """);
        });
        server.start();
        try {
            RuntimeChannelProviderRegistry registry = registry("http://localhost:" + server.getAddress().getPort());
            RemoteProviderJobExecutor executor = new RemoteProviderJobExecutor(
                registry,
                mock(NormalizedChannelEventIngestService.class),
                mock(ChannelInboundSessionDispatcher.class),
                new ObjectMapper(),
                "internal-token",
                java.net.http.HttpClient.newHttpClient()
            );
            ProviderJobClaim claim = new ProviderJobClaim(
                "channel-job-1",
                "channel-job-run-1",
                "channel-job-run:channel-job-run-1",
                "channel-profile-1",
                "enterprise.acme.jobs",
                Map.of("region", "apac"),
                "vault://secret-ref",
                "PULL_MESSAGES",
                new ChannelProviderJobScheduleConfig(
                    ChannelProviderJobScheduleType.INTERVAL,
                    60,
                    null,
                    "UTC",
                    45,
                    Map.of("cursorMode", "incremental")
                ),
                "cursor-1",
                Instant.parse("2026-04-25T00:00:00Z"),
                Instant.parse("2026-04-25T00:00:01Z"),
                45
            );

            ProviderJobExecutionResult result = executor.run(claim);

            assertEquals("cursor-2", result.nextCursor());
            assertEquals("Bearer internal-token", headers.get().getFirst(LynxusExtensionHeaders.AUTHORIZATION));
            assertEquals("acme-channel-provider", headers.get().getFirst(LynxusExtensionHeaders.REGISTRATION_ID));
            assertEquals("CHANNEL_PROVIDER", headers.get().getFirst(LynxusExtensionHeaders.DESCRIPTOR_TYPE));
            assertEquals("enterprise.acme.jobs", headers.get().getFirst(LynxusExtensionHeaders.DESCRIPTOR_ID));
            assertEquals("channel-job-run:channel-job-run-1", headers.get().getFirst(LynxusExtensionHeaders.IDEMPOTENCY_KEY));
            assertEquals("vault://secret-ref", requestBody.get().get("externalSecretRef"));
            assertEquals("channel-job-run:channel-job-run-1", requestBody.get().get("idempotencyKey"));
            assertEquals("enterprise.acme.jobs", requestBody.get().get("providerType"));
            assertEquals("channel-profile-1", requestBody.get().get("channelProfileId"));
            assertEquals(Map.of("region", "apac"), requestBody.get().get("config"));
            Map<?, ?> payload = (Map<?, ?>) requestBody.get().get("payload");
            assertEquals("PULL_MESSAGES", payload.get("jobType"));
            assertEquals(Map.of("cursorMode", "incremental"), payload.get("jobConfig"));
            assertEquals("2026-04-25T00:00:00Z", payload.get("scheduledAt"));
            assertFalse(requestBody.get().containsKey("accountId"));
        } finally {
            server.stop(0);
        }
    }

    private static RuntimeChannelProviderRegistry registry(String baseUrl) {
        return new RuntimeChannelProviderRegistry(new ChannelProviderRegistryLoader(
            registrationServiceFromYaml("""
                lynxus:
                  extensions:
                    services:
                      - registrationId: acme-channel-provider
                        baseUrl: %s
                        exposes:
                          channelProviderTypes:
                            - enterprise.acme.jobs
                        auth:
                          type: INTERNAL_TOKEN
                """.formatted(baseUrl)),
            new ChannelGatewayDescriptorProvider(),
            (manifestUrl, headers) -> java.net.http.HttpClient.newHttpClient()
                .send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(manifestUrl)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()
                )
                .body(),
            "internal-token"
        ));
    }

    private static ExtensionRegistrationService registrationServiceFromYaml(String operatorYaml) {
        try {
            Path tempFile = Files.createTempFile("lynxus-extension-registration", ".yaml");
            Files.writeString(tempFile, operatorYaml);
            return new ExtensionRegistrationService(
                new ExtensionRegistrationProperties(tempFile.toString()),
                "http://channel-gateway.example.com",
                "http://agent-runtime.example.com"
            );
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String manifest(Map<String, Object> channelDescriptor) {
        Map<String, Object> descriptors = new LinkedHashMap<>();
        descriptors.put("channelProviders", List.of(channelDescriptor));
        descriptors.put("toolConnectors", List.of());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("extensionApiVersion", 1);
        manifest.put("coreMinVersion", "0.8.0");
        manifest.put("coreMaxVersion", "0.9.x");
        manifest.put("descriptors", descriptors);
        return LynxusCanonicalJson.canonicalizeValue(manifest);
    }

    private static Map<String, Object> channelDescriptor() {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("providerType", "enterprise.acme.jobs");
        descriptor.put("title", "Acme Jobs Provider");
        descriptor.put("accountConfigSchema", Map.of());
        descriptor.put("accountConfigUiSchema", List.of());
        descriptor.put("configSchema", Map.of());
        descriptor.put("configUiSchema", List.of());
        descriptor.put("defaultConfig", Map.of());
        descriptor.put("jobDefinitions", List.of(Map.of(
            "jobType", "PULL_MESSAGES",
            "title", "Pull messages",
            "jobConfigSchema", Map.of("type", "object"),
            "jobConfigUiSchema", List.of(),
            "defaultSchedule", Map.of("scheduleType", "MANUAL"),
            "defaultEnabled", true,
            "defaultJobTimeoutSeconds", 45
        )));
        descriptor.put("endpoints", Map.of("sendOutbound", "/channel/send-outbound", "runJob", "/channel/run-job"));
        return descriptor;
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
