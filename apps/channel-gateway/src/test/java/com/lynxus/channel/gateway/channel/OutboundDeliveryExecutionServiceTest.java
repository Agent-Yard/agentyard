package com.lynxus.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.channel.gateway.connector.feishu.FeishuAppCredential;
import com.lynxus.channel.gateway.connector.feishu.FeishuCredentialProvider;
import com.lynxus.channel.gateway.connector.feishu.FeishuGatewayNativeChannelProviderAdapter;
import com.lynxus.channel.gateway.connector.feishu.FeishuMessageSender;
import com.lynxus.channel.gateway.extension.ChannelProviderDescriptor;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistry;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistryLoadResult;
import com.lynxus.channel.gateway.extension.GatewayNativeChannelProviderAdapters;
import com.lynxus.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityResponse;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityResponseStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityType;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDeliveryRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDeliveryStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundResponse;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundResponseStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileAccountSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelTemplateBindingWriteRequest;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelProfileInternalRequest;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelTraceContext;
import com.lynxus.extension.sdk.protocol.JsonDocuments;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHeaders;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.ObjectMapper;

class OutboundDeliveryExecutionServiceTest {
    private static final String PROVIDER_TYPE = "enterprise.acme.outbound";
    private static EmbeddedPostgresTestDatabase database;

    private ObjectMapper objectMapper;
    private ChannelAdminRepository repository;

    @BeforeAll
    static void startDatabase() throws Exception {
        database = new EmbeddedPostgresTestDatabase();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        database.close();
    }

    @BeforeEach
    void setUp() {
        database.reset();
        objectMapper = new ObjectMapper();
        repository = new ChannelAdminRepository(database.dsl(), objectMapper);
    }

    @Test
    void successfulCardOutboundSnapshotsResolvedTemplateAndSendsCanonicalRemoteEnvelope() throws Exception {
        AtomicReference<Headers> headers = new AtomicReference<>();
        AtomicReference<Map<String, Object>> requestBody = new AtomicReference<>();
        AtomicInteger providerCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/channel/send-outbound", exchange -> {
            providerCalls.incrementAndGet();
            headers.set(exchange.getRequestHeaders());
            requestBody.set(JsonDocuments.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            writeJson(exchange, 200, """
                {
                  "status": "SENT",
                  "externalMessageId": "msg-remote-1",
                  "retryable": false,
                  "metadata": {}
                }
                """);
        });
        server.start();
        try {
            ChannelProviderRegistry registry = registry(remoteDescriptor("http://localhost:" + server.getAddress().getPort()));
            ChannelAdminService adminService = adminService(registry);
            ChannelGatewayProfile profile = createProfile(adminService, PROVIDER_TYPE, Map.of("region", "apac"), "vault://secret-ref");
            adminService.upsertTemplateBinding(
                profile.id(),
                "assistant-1",
                "CARD",
                "ORDER_STATUS",
                "1",
                new ChannelTemplateBindingWriteRequest(
                    "tpl_order_status",
                    "published",
                    objectSchema(
                        Map.of(
                            "orderId", Map.of("type", "string"),
                            "status", Map.of("type", "string")
                        ),
                        List.of("orderId", "status")
                    ),
                    "Order status",
                    null,
                    true,
                    null
                )
            );
            OutboundDeliveryExecutionService service = outboundService(
                registry,
                new DefaultChannelProviderOutboundSender(
                    nativeAdapters(),
                    objectMapper,
                    "internal-token",
                    java.net.http.HttpClient.newHttpClient()
                )
            );

            ChannelOutboundDelivery delivery = service.deliver(new ChannelOutboundDeliveryRequest(
                profile.id(),
                "assistant-1",
                "chat-1",
                "session-1",
                "message-1",
                cardBlock(Map.of("orderId", "A001", "status", "SHIPPED")),
                new NormalizedChannelTraceContext("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", null)
            ));

            assertEquals(ChannelOutboundDeliveryStatus.SENT, delivery.status());
            assertEquals(1, delivery.attemptCount());
            assertNotNull(delivery.idempotencyKey());
            assertEquals(1, providerCalls.get());
            assertEquals(delivery.idempotencyKey(), headers.get().getFirst(LynxusExtensionHeaders.IDEMPOTENCY_KEY));
            assertEquals("Bearer internal-token", headers.get().getFirst(LynxusExtensionHeaders.AUTHORIZATION));
            assertEquals("acme-channel-provider", headers.get().getFirst(LynxusExtensionHeaders.REGISTRATION_ID));
            assertEquals("CHANNEL_PROVIDER", headers.get().getFirst(LynxusExtensionHeaders.DESCRIPTOR_TYPE));
            assertEquals(PROVIDER_TYPE, headers.get().getFirst(LynxusExtensionHeaders.DESCRIPTOR_ID));
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", headers.get().getFirst(LynxusExtensionHeaders.TRACE_ID));
            assertEquals(delivery.idempotencyKey(), requestBody.get().get("idempotencyKey"));
            assertEquals("vault://secret-ref", requestBody.get().get("externalSecretRef"));
            assertEquals(Map.of("region", "apac"), requestBody.get().get("config"));
            assertFalse(requestBody.get().containsKey("accountId"));

            Map<?, ?> payload = (Map<?, ?>) requestBody.get().get("payload");
            assertEquals("chat-1", payload.get("externalConversationId"));
            assertEquals(cardBlock(Map.of("orderId", "A001", "status", "SHIPPED")), payload.get("messageBlock"));
            assertEquals(Map.of(
                "messageType", "CARD",
                "messageSubtype", "ORDER_STATUS",
                "messageVersion", "1",
                "externalTemplateId", "tpl_order_status",
                "externalTemplateVersion", "published"
            ), payload.get("resolvedTemplate"));
            assertEquals(payload, delivery.payload());

            ChannelOutboundDelivery persisted = repository.listOutboundDeliveries(profile.id()).get(0);
            assertEquals(delivery.idempotencyKey(), persisted.idempotencyKey());
            assertEquals(ChannelOutboundDeliveryStatus.SENT, persisted.status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsExistingDeliveryForRepeatedSessionMessage() {
        CapturingSender sender = new CapturingSender();
        ChannelProviderRegistry registry = registry(remoteDescriptor("http://provider.example.com"));
        ChannelAdminService adminService = adminService(registry);
        ChannelGatewayProfile profile = createProfile(adminService, PROVIDER_TYPE, Map.of(), null);
        OutboundDeliveryExecutionService service = outboundService(registry, sender);
        ChannelOutboundDeliveryRequest request = new ChannelOutboundDeliveryRequest(
            profile.id(),
            "assistant-1",
            "chat-1",
            "session-1",
            "message-1",
            textBlock("hello"),
            new NormalizedChannelTraceContext("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", null)
        );

        ChannelOutboundDelivery first = service.deliver(request);
        ChannelOutboundDelivery second = service.deliver(request);

        assertEquals(first.deliveryId(), second.deliveryId());
        assertEquals(first.idempotencyKey(), second.idempotencyKey());
        assertEquals(1, sender.calls());
        assertEquals(1, repository.listOutboundDeliveries(profile.id()).size());
    }

    @Test
    void extensionProviderReceivesCanonicalTextImageAndRichTextBlocksUnchanged() {
        CapturingSender sender = new CapturingSender();
        ChannelProviderRegistry registry = registry(remoteDescriptor("http://provider.example.com"));
        ChannelGatewayProfile profile = createProfile(adminService(registry), PROVIDER_TYPE, Map.of(), null);
        OutboundDeliveryExecutionService service = outboundService(registry, sender);
        List<Map<String, Object>> blocks = List.of(
            textBlock("hello"),
            imageBlock("https://cdn.example.com/image.png", "image/png"),
            richTextBlock("**hello**")
        );

        for (Map<String, Object> block : blocks) {
            service.deliver(new ChannelOutboundDeliveryRequest(
                profile.id(),
                "assistant-1",
                "chat-1",
                null,
                null,
                block,
                null
            ));
            assertEquals(block, sender.invocation.get().requestBody().payload().messageBlock());
        }
    }

    @Test
    void missingOrDisabledCardTemplateBindingFailsWithoutProviderCall() {
        CapturingSender sender = new CapturingSender();
        ChannelProviderRegistry registry = registry(remoteDescriptor("http://provider.example.com"));
        ChannelAdminService adminService = adminService(registry);
        ChannelGatewayProfile profile = createProfile(adminService, PROVIDER_TYPE, Map.of(), null);
        adminService.upsertTemplateBinding(
            profile.id(),
            "assistant-1",
            "CARD",
            "ORDER_STATUS",
            "1",
            new ChannelTemplateBindingWriteRequest("tpl_disabled", null, Map.of("type", "object"), "Order status", null, false, null)
        );

        ChannelOutboundDelivery delivery = outboundService(registry, sender).deliver(new ChannelOutboundDeliveryRequest(
            profile.id(),
            "assistant-1",
            "chat-1",
            null,
            null,
            cardBlock(Map.of("orderId", "A001")),
            null
        ));

        assertEquals(ChannelOutboundDeliveryStatus.FAILED, delivery.status());
        assertEquals(0, delivery.attemptCount());
        assertEquals(0, sender.calls());
        assertTrue(delivery.lastError().contains("enabled channel template binding"));
    }

    @Test
    void cardVariableSchemaValidationFailureFailsWithoutProviderCallAndSnapshotsResolvedTemplate() {
        CapturingSender sender = new CapturingSender();
        ChannelProviderRegistry registry = registry(remoteDescriptor("http://provider.example.com"));
        ChannelAdminService adminService = adminService(registry);
        ChannelGatewayProfile profile = createProfile(adminService, PROVIDER_TYPE, Map.of(), null);
        adminService.upsertTemplateBinding(
            profile.id(),
            "assistant-1",
            "CARD",
            "ORDER_STATUS",
            "1",
            new ChannelTemplateBindingWriteRequest(
                "tpl_order_status",
                "published",
                objectSchema(Map.of("orderId", Map.of("type", "string")), List.of("orderId")),
                "Order status",
                null,
                true,
                null
            )
        );

        ChannelOutboundDelivery delivery = outboundService(registry, sender).deliver(new ChannelOutboundDeliveryRequest(
            profile.id(),
            "assistant-1",
            "chat-1",
            null,
            null,
            cardBlock(Map.of("status", "SHIPPED")),
            null
        ));

        assertEquals(ChannelOutboundDeliveryStatus.FAILED, delivery.status());
        assertEquals(0, delivery.attemptCount());
        assertEquals(0, sender.calls());
        assertEquals("card message data does not satisfy template variableSchema", delivery.lastError());
        assertEquals(Map.of(
            "messageType", "CARD",
            "messageSubtype", "ORDER_STATUS",
            "messageVersion", "1",
            "externalTemplateId", "tpl_order_status",
            "externalTemplateVersion", "published"
        ), delivery.payload().get("resolvedTemplate"));
    }

    @Test
    void providerHttpFailureMarksFailedWithSanitizedErrorAndSingleAttempt() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/channel/send-outbound", exchange -> {
            providerCalls.incrementAndGet();
            writeJson(exchange, 500, """
                {"externalSecretRef":"vault://secret-ref","Authorization":"Bearer raw-token","payload":{"credential":"raw"}}
                """);
        });
        server.start();
        try {
            ChannelProviderRegistry registry = registry(remoteDescriptor("http://localhost:" + server.getAddress().getPort()));
            ChannelGatewayProfile profile = createProfile(adminService(registry), PROVIDER_TYPE, Map.of(), "vault://secret-ref");

            ChannelOutboundDelivery delivery = outboundService(
                registry,
                new DefaultChannelProviderOutboundSender(nativeAdapters(), objectMapper, "internal-token", java.net.http.HttpClient.newHttpClient())
            ).deliver(new ChannelOutboundDeliveryRequest(
                profile.id(),
                "assistant-1",
                "chat-1",
                null,
                null,
                textBlock("hello"),
                null
            ));

            assertEquals(ChannelOutboundDeliveryStatus.FAILED, delivery.status());
            assertEquals(1, delivery.attemptCount());
            assertEquals(1, providerCalls.get());
            assertTrue(delivery.lastError().contains("HTTP 500"));
            assertFalse(delivery.lastError().contains("externalSecretRef"));
            assertFalse(delivery.lastError().contains("vault://"));
            assertFalse(delivery.lastError().contains("Bearer raw-token"));
            assertFalse(delivery.lastError().contains("credential"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerProtocolErrorMarksFailedWithSingleAttempt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/channel/send-outbound", exchange -> writeJson(exchange, 200, """
            {"status":"QUEUED","retryable":false,"metadata":{}}
            """));
        server.start();
        try {
            ChannelProviderRegistry registry = registry(remoteDescriptor("http://localhost:" + server.getAddress().getPort()));
            ChannelGatewayProfile profile = createProfile(adminService(registry), PROVIDER_TYPE, Map.of(), null);

            ChannelOutboundDelivery delivery = outboundService(
                registry,
                new DefaultChannelProviderOutboundSender(nativeAdapters(), objectMapper, "internal-token", java.net.http.HttpClient.newHttpClient())
            ).deliver(new ChannelOutboundDeliveryRequest(
                profile.id(),
                "assistant-1",
                "chat-1",
                null,
                null,
                textBlock("hello"),
                null
            ));

            assertEquals(ChannelOutboundDeliveryStatus.FAILED, delivery.status());
            assertEquals(1, delivery.attemptCount());
            assertEquals("provider sendOutbound response is invalid", delivery.lastError());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unknownMessageBlockTypeFailsWithoutProviderCall() {
        CapturingSender sender = new CapturingSender();
        ChannelProviderRegistry registry = registry(remoteDescriptor("http://provider.example.com"));
        ChannelGatewayProfile profile = createProfile(adminService(registry), PROVIDER_TYPE, Map.of(), null);

        ChannelOutboundDelivery delivery = outboundService(registry, sender).deliver(new ChannelOutboundDeliveryRequest(
            profile.id(),
            "assistant-1",
            "chat-1",
            null,
            null,
            Map.of("type", "PROVIDER_NATIVE", "body", Map.of("opaque", true)),
            null
        ));

        assertEquals(ChannelOutboundDeliveryStatus.FAILED, delivery.status());
        assertEquals(0, delivery.attemptCount());
        assertEquals(0, sender.calls());
        assertEquals("unsupported canonical messageBlock.type: PROVIDER_NATIVE", delivery.lastError());
    }

    @Test
    void malformedCanonicalTextBlockFailsWithoutProviderCall() {
        CapturingSender sender = new CapturingSender();
        ChannelProviderRegistry registry = registry(remoteDescriptor("http://provider.example.com"));
        ChannelGatewayProfile profile = createProfile(adminService(registry), PROVIDER_TYPE, Map.of(), null);

        ChannelOutboundDelivery delivery = outboundService(registry, sender).deliver(new ChannelOutboundDeliveryRequest(
            profile.id(),
            "assistant-1",
            "chat-1",
            null,
            null,
            Map.of("type", "TEXT"),
            null
        ));

        assertEquals(ChannelOutboundDeliveryStatus.FAILED, delivery.status());
        assertEquals(0, delivery.attemptCount());
        assertEquals(0, sender.calls());
        assertEquals("messageBlock.text is required", delivery.lastError());
    }

    @Test
    void outboundExecutionHasNoScheduledRetryOrRedisLockDependency() {
        for (Method method : OutboundDeliveryExecutionService.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(Scheduled.class), method.getName());
        }
        for (java.lang.reflect.Field field : OutboundDeliveryExecutionService.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().contains("ProviderJobLock"), field.getName());
            assertFalse(field.getType().getName().toLowerCase().contains("redis"), field.getName());
            assertFalse(field.getName().toLowerCase().contains("retry"), field.getName());
        }
    }

    @Test
    void gatewayNativeFeishuTextOutboundUsesIntegrationAccountCredentials() {
        ChannelProviderRegistry registry = registry(nativeDescriptor());
        ChannelGatewayProfile profile = createProfile(adminService(registry), FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE, Map.of(), null);
        CapturingFeishuCredentialProvider credentialProvider = new CapturingFeishuCredentialProvider();
        CapturingFeishuMessageSender feishuSender = new CapturingFeishuMessageSender();

        ChannelOutboundDelivery delivery = outboundService(
            registry,
            new DefaultChannelProviderOutboundSender(
                nativeAdapters(credentialProvider, feishuSender),
                objectMapper,
                "internal-token",
                java.net.http.HttpClient.newHttpClient()
            )
        ).deliver(new ChannelOutboundDeliveryRequest(
            profile.id(),
            "assistant-1",
            "chat-1",
            null,
            null,
            textBlock("hello"),
            null
        ));

        assertEquals(ChannelOutboundDeliveryStatus.SENT, delivery.status());
        assertEquals(1, delivery.attemptCount());
        assertEquals("integration-account-1", credentialProvider.accountId.get());
        assertEquals("chat-1", feishuSender.command.get().receiveId());
        assertEquals("chat_id", feishuSender.command.get().receiveIdType());
        assertEquals("hello", feishuSender.command.get().text());
    }

    @Test
    void outboundActivityRoutesTypingWhenCapabilityAndEndpointAreDeclared() {
        CapturingActivitySender sender = new CapturingActivitySender();
        ChannelProviderRegistry registry = registry(remoteActivityDescriptor("http://provider.example.com", true, false));
        ChannelGatewayProfile profile = createProfile(adminService(registry), PROVIDER_TYPE, Map.of(), null);
        OutboundActivityExecutionService service = activityService(registry, sender);
        ChannelOutboundActivityRequest request = activityRequest(
            profile.id(),
            ChannelOutboundActivityType.TYPING_START,
            "exec-1:1",
            "activity:exec-1:1:TYPING_START",
            Map.of()
        );

        ChannelOutboundActivityResponse first = service.send(request);
        ChannelOutboundActivityResponse second = service.send(request);

        assertEquals(ChannelOutboundActivityResponseStatus.ACCEPTED, first.status());
        assertEquals(ChannelOutboundActivityResponseStatus.NO_OP, second.status());
        assertEquals(1, sender.calls());
        assertEquals(ChannelOutboundActivityType.TYPING_START, sender.invocation.get().request().activityType());
        assertEquals("activity:exec-1:1:TYPING_START", sender.invocation.get().requestBody().idempotencyKey());
    }

    @Test
    void outboundActivityDoesNotRouteDraftDeltaWhenDraftCapabilityUnsupported() {
        CapturingActivitySender sender = new CapturingActivitySender();
        ChannelProviderRegistry registry = registry(remoteActivityDescriptor("http://provider.example.com", true, false));
        ChannelGatewayProfile profile = createProfile(adminService(registry), PROVIDER_TYPE, Map.of(), null);

        ChannelOutboundActivityResponse response = activityService(registry, sender).send(activityRequest(
            profile.id(),
            ChannelOutboundActivityType.DRAFT_UPDATE,
            "exec-1:2",
            "activity:exec-1:2:DRAFT_UPDATE",
            Map.of("delta", "hello")
        ));

        assertEquals(ChannelOutboundActivityResponseStatus.UNSUPPORTED, response.status());
        assertEquals(0, sender.calls());
        assertEquals(0, repository.listOutboundDeliveries(profile.id()).size());
    }

    private ChannelAdminService adminService(ChannelProviderRegistry registry) {
        return new ChannelAdminService(repository, registry);
    }

    private OutboundDeliveryExecutionService outboundService(
        ChannelProviderRegistry registry,
        ChannelProviderOutboundSender sender
    ) {
        return new OutboundDeliveryExecutionService(
            repository,
            new ChannelTemplateBindingResolver(repository),
            registry,
            sender
        );
    }

    private OutboundActivityExecutionService activityService(
        ChannelProviderRegistry registry,
        ChannelProviderActivitySender sender
    ) {
        return new OutboundActivityExecutionService(repository, registry, sender);
    }

    private ChannelGatewayProfile createProfile(
        ChannelAdminService adminService,
        String providerType,
        Map<String, Object> config,
        String externalSecretRef
    ) {
        return adminService.createProfile(new CreateChannelProfileInternalRequest(
            providerType,
            "Outbound Provider",
            null,
            true,
            config,
            new ChannelAssistantBinding("assistant-1", null),
            new ChannelProfileAccountSnapshot("integration-account-1", externalSecretRef)
        ));
    }

    private GatewayNativeChannelProviderAdapters nativeAdapters(
        FeishuCredentialProvider credentialProvider,
        FeishuMessageSender feishuSender
    ) {
        return new GatewayNativeChannelProviderAdapters(List.of(new FeishuGatewayNativeChannelProviderAdapter(
            repository,
            credentialProvider,
            feishuSender
        )));
    }

    private static GatewayNativeChannelProviderAdapters nativeAdapters() {
        return new GatewayNativeChannelProviderAdapters(List.of(new FeishuGatewayNativeChannelProviderAdapter()));
    }

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "TEXT");
        block.put("text", text);
        return Map.copyOf(block);
    }

    private static Map<String, Object> imageBlock(String url, String mimeType) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "IMAGE");
        block.put("url", url);
        block.put("mimeType", mimeType);
        return Map.copyOf(block);
    }

    private static Map<String, Object> richTextBlock(String content) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "RICH_TEXT");
        block.put("format", "MARKDOWN");
        block.put("content", content);
        return Map.copyOf(block);
    }

    private static Map<String, Object> cardBlock(Map<String, Object> data) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "CARD");
        block.put("cardType", "ORDER_STATUS");
        block.put("version", "1");
        block.put("data", data);
        block.put("actions", List.of());
        return Map.copyOf(block);
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static ChannelProviderDescriptor remoteDescriptor(String baseUrl) {
        return descriptor(PROVIDER_TYPE, "acme-channel-provider", baseUrl, false);
    }

    private static ChannelProviderDescriptor remoteActivityDescriptor(
        String baseUrl,
        boolean typing,
        boolean draftUpdate
    ) {
        return descriptor(PROVIDER_TYPE, "acme-channel-provider", baseUrl, false, typing, draftUpdate, "/channel/send-activity");
    }

    private static ChannelProviderDescriptor nativeDescriptor() {
        return descriptor(FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE, "core-channel-gateway", "http://channel-gateway.example.com", true);
    }

    private static ChannelProviderDescriptor descriptor(
        String providerType,
        String registrationId,
        String baseUrl,
        boolean gatewayNative
    ) {
        return descriptor(providerType, registrationId, baseUrl, gatewayNative, false, false, null);
    }

    private static ChannelProviderDescriptor descriptor(
        String providerType,
        String registrationId,
        String baseUrl,
        boolean gatewayNative,
        boolean typing,
        boolean draftUpdate,
        String sendActivityPath
    ) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("providerType", providerType);
        descriptor.put("title", "Outbound Provider");
        descriptor.put("accountConfigSchema", Map.of());
        descriptor.put("accountConfigUiSchema", List.of());
        descriptor.put("configSchema", Map.of());
        descriptor.put("configUiSchema", List.of());
        descriptor.put("defaultConfig", Map.of());
        descriptor.put("jobDefinitions", List.of());
        descriptor.put("capabilities", Map.of("typing", typing, "draftUpdate", draftUpdate));
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("sendOutbound", "/channel/send-outbound");
        if (sendActivityPath != null) {
            endpoints.put("sendActivity", sendActivityPath);
        }
        descriptor.put("endpoints", endpoints);
        return new ChannelProviderDescriptor(
            providerType,
            registrationId,
            baseUrl,
            "/channel/send-outbound",
            sendActivityPath,
            null,
            gatewayNative,
            descriptor,
            "digest-" + providerType,
            Map.of(),
            Map.of(),
            new ChannelProviderDescriptor.ChannelProviderCapabilities(typing, draftUpdate),
            Map.of()
        );
    }

    private static ChannelOutboundActivityRequest activityRequest(
        String channelProfileId,
        ChannelOutboundActivityType activityType,
        String frameId,
        String idempotencyKey,
        Map<String, Object> payload
    ) {
        return new ChannelOutboundActivityRequest(
            channelProfileId,
            "assistant-1",
            "chat-1",
            "session-1",
            "turn-1",
            frameId,
            activityType,
            idempotencyKey,
            payload,
            new NormalizedChannelTraceContext("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", null)
        );
    }

    private static ChannelProviderRegistry registry(ChannelProviderDescriptor descriptor) {
        return new TestRegistry(descriptor);
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class CapturingSender implements ChannelProviderOutboundSender {
        private int calls;
        private final AtomicReference<ChannelOutboundInvocation> invocation = new AtomicReference<>();

        @Override
        public ChannelOutboundResponse send(ChannelOutboundInvocation invocation) {
            calls += 1;
            this.invocation.set(invocation);
            return new ChannelOutboundResponse(ChannelOutboundResponseStatus.SENT, "msg-1", false, Map.of());
        }

        int calls() {
            return calls;
        }
    }

    private static final class CapturingActivitySender implements ChannelProviderActivitySender {
        private int calls;
        private final AtomicReference<ChannelOutboundActivityInvocation> invocation = new AtomicReference<>();

        @Override
        public ChannelOutboundActivityResponse send(ChannelOutboundActivityInvocation invocation) {
            calls += 1;
            this.invocation.set(invocation);
            return new ChannelOutboundActivityResponse(ChannelOutboundActivityResponseStatus.ACCEPTED, false, Map.of());
        }

        int calls() {
            return calls;
        }
    }

    private static final class CapturingFeishuCredentialProvider implements FeishuCredentialProvider {
        private final AtomicReference<String> accountId = new AtomicReference<>();

        @Override
        public FeishuAppCredential resolve(String accountId, Map<String, Object> profileConfig) {
            this.accountId.set(accountId);
            return new FeishuAppCredential(accountId, "cli_test", "secret_test");
        }
    }

    private static final class CapturingFeishuMessageSender implements FeishuMessageSender {
        private final AtomicReference<FeishuSendTextCommand> command = new AtomicReference<>();

        @Override
        public FeishuSendTextResult sendText(FeishuSendTextCommand command) {
            this.command.set(command);
            return new FeishuSendTextResult("om_sent", Map.of("requestId", "req_1"));
        }
    }

    private record TestRegistry(ChannelProviderDescriptor descriptor) implements ChannelProviderRegistry {
        @Override
        public ChannelProviderRegistryLoadResult snapshot() {
            return new ChannelProviderRegistryLoadResult(
                List.of(),
                List.of(),
                java.util.Set.of(descriptor.providerType()),
                java.util.Set.of(),
                java.util.Set.of(),
                List.of(),
                Map.of(descriptor.providerType(), 1),
                Map.of(descriptor.providerType(), descriptor.definitionDigest()),
                Map.of(descriptor.providerType(), descriptor)
            );
        }

        @Override
        public ChannelProviderDescriptor requireProvider(String providerType) {
            if (!descriptor.providerType().equals(providerType)) {
                throw new IllegalArgumentException("unknown channelProfile.providerType: " + providerType);
            }
            return descriptor;
        }

        @Override
        public Map<String, Object> materializeAndValidateProfileConfig(String providerType, Map<String, Object> config) {
            requireProvider(providerType);
            return config == null ? Map.of() : config;
        }
    }
}
