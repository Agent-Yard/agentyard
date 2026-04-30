package com.lynxus.platform.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.extension.sdk.common.LynxusCanonicalJson;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHeaders;
import com.lynxus.extension.sdk.registration.ExtensionRegistrationLoader;
import com.lynxus.platform.extension.ExtensionDefinitionDtos.ChannelProviderDefinition;
import com.lynxus.platform.extension.ExtensionDefinitionDtos.ToolConnectorDefinition;
import com.lynxus.platform.extension.ExtensionDefinitionService.ExtensionDefinitionRegistry;
import com.lynxus.platform.extension.ExtensionRegistryValidation.RuntimeRegistryValidation;
import com.lynxus.platform.integration.InternalRuntimeAuth;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

final class ExtensionRegistryAggregateValidationServiceTest {
    @Test
    void aggregateValidationIsReadyWhenRuntimeOwnersMatchLocalRegistry() {
        ScenarioContext context = readyContext();
        ExtensionRegistryValidation validation = context.aggregateService().validate();

        assertEquals("READY", validation.status());
        assertEquals("api", validation.service());
        assertEquals("EXTENSION_REGISTRY", validation.component());
        assertEquals("AGGREGATE", validation.registryType());
        assertEquals(context.registrationService().registrationConfigDigest(), validation.registrationConfigDigest());
        assertTrue(validation.errors().isEmpty());
        assertEquals(1, context.runtimeClient().agentRuntimeCalls);
        assertEquals(1, context.runtimeClient().channelGatewayCalls);
    }

    @Test
    void registrationConfigDigestMismatchMakesAggregateNotReady() {
        ScenarioContext context = readyContext();
        context.runtimeClient().agentRuntime = RuntimeRegistryValidationResult.ready(
            "agent-runtime",
            runtimeValidation(
                "agent-runtime",
                "TOOL_CONNECTOR",
                "sha256:agent-runtime-drift",
                toolConnectorDigests(context.localRegistry())
            )
        );

        ExtensionRegistryValidation validation = context.aggregateService().validate();

        assertEquals("NOT_READY", validation.status());
        RegistryValidationError error = onlyError(validation);
        assertEquals("REGISTRATION_CONFIG_DIGEST_MISMATCH", error.code());
        assertEquals("AGGREGATE_VALIDATION", error.details().get("phase"));
        assertEquals("agent-runtime", error.details().get("runtimeService"));
        assertFalse(validation.toString().contains("internal-token"));
    }

    @Test
    void descriptorIdMismatchCoversToolAndChannelRuntimeOwners() {
        ScenarioContext context = readyContext();
        Map<String, String> toolDigests = new LinkedHashMap<>(toolConnectorDigests(context.localRegistry()));
        toolDigests.remove("simple-http");
        Map<String, String> channelDigests = new LinkedHashMap<>(channelProviderDigests(context.localRegistry()));
        channelDigests.put("enterprise.acme.extra", "sha256:extra");
        context.runtimeClient().agentRuntime = RuntimeRegistryValidationResult.ready(
            "agent-runtime",
            runtimeValidation(
                "agent-runtime",
                "TOOL_CONNECTOR",
                context.registrationService().registrationConfigDigest(),
                toolDigests
            )
        );
        context.runtimeClient().channelGateway = RuntimeRegistryValidationResult.ready(
            "channel-gateway",
            runtimeValidation(
                "channel-gateway",
                "CHANNEL_PROVIDER",
                context.registrationService().registrationConfigDigest(),
                channelDigests
            )
        );

        ExtensionRegistryValidation validation = context.aggregateService().validate();

        assertEquals("NOT_READY", validation.status());
        assertEquals(
            List.of("REGISTRY_DESCRIPTOR_MISMATCH", "REGISTRY_DESCRIPTOR_MISMATCH"),
            validation.errors().stream().map(RegistryValidationError::code).toList()
        );
        assertTrue(validation.errors().stream().anyMatch(error -> "TOOL_CONNECTOR".equals(error.descriptorType())));
        assertTrue(validation.errors().stream().anyMatch(error -> "CHANNEL_PROVIDER".equals(error.descriptorType())));
    }

    @Test
    void definitionDigestMismatchNamesTheDriftedDescriptor() {
        ScenarioContext context = readyContext();
        Map<String, String> toolDigests = new LinkedHashMap<>(toolConnectorDigests(context.localRegistry()));
        toolDigests.put("simple-http", "sha256:runtime-definition-drift");
        context.runtimeClient().agentRuntime = RuntimeRegistryValidationResult.ready(
            "agent-runtime",
            runtimeValidation(
                "agent-runtime",
                "TOOL_CONNECTOR",
                context.registrationService().registrationConfigDigest(),
                toolDigests
            )
        );

        ExtensionRegistryValidation validation = context.aggregateService().validate();

        assertEquals("NOT_READY", validation.status());
        RegistryValidationError error = onlyError(validation);
        assertEquals("REGISTRY_DEFINITION_DIGEST_MISMATCH", error.code());
        assertEquals("TOOL_CONNECTOR", error.descriptorType());
        assertEquals("simple-http", error.descriptorId());
        assertEquals("AGGREGATE_VALIDATION", error.details().get("phase"));
        assertEquals("agent-runtime", error.details().get("runtimeService"));
    }

    @Test
    void runtimeValidationFailureMapsToUnreachableWithoutSensitiveLeakage() {
        ScenarioContext context = readyContext();
        context.runtimeClient().agentRuntime = RuntimeRegistryValidationResult.failure(
            "agent-runtime",
            "non-2xx response",
            503
        );

        ExtensionRegistryValidation validation = context.aggregateService().validate();

        assertEquals("NOT_READY", validation.status());
        RegistryValidationError error = onlyError(validation);
        assertEquals("RUNTIME_REGISTRY_UNREACHABLE", error.code());
        assertEquals("AGGREGATE_VALIDATION", error.details().get("phase"));
        assertEquals("agent-runtime", error.details().get("runtimeService"));
        assertEquals(503, error.details().get("httpStatus"));
        assertEquals("non-2xx response", error.details().get("failureReason"));
        String serialized = validation.toString();
        assertFalse(serialized.contains("internal-token"));
        assertFalse(serialized.contains("Authorization"));
        assertFalse(serialized.contains("http://agent-runtime.example.com"));
        assertFalse(serialized.contains("externalSecretRef"));
    }

    @Test
    void localRegistryNotReadyReturnsLocalErrorsAndSkipsRuntimeOwners() {
        CapturingFetcher fetcher = coreFetcher();
        fetcher.responses.put(ExtensionRegistrationLoader.CORE_AGENT_RUNTIME_REGISTRATION_ID, manifest(List.of(), List.of()));
        ExtensionRegistrationService registrationService = registrationService("");
        ExtensionDefinitionService definitionService = new ExtensionDefinitionService(registrationService, fetcher, "internal-token");
        FakeRuntimeClient runtimeClient = new FakeRuntimeClient();
        ExtensionAggregateRegistryValidationService service = new ExtensionAggregateRegistryValidationService(
            definitionService,
            registrationService,
            runtimeClient
        );

        ExtensionRegistryValidation validation = service.validate();

        assertEquals("NOT_READY", validation.status());
        assertFalse(validation.errors().isEmpty());
        assertTrue(validation.errors().stream().allMatch(error -> !"AGGREGATE_VALIDATION".equals(error.details().get("phase"))));
        assertEquals(0, runtimeClient.agentRuntimeCalls);
        assertEquals(0, runtimeClient.channelGatewayCalls);
    }

    @Test
    void endpointMapsReadyTo200AndNotReadyTo503() throws Exception {
        ScenarioContext ready = readyContext();
        MockMvcBuilders.standaloneSetup(new ExtensionAggregateRegistryValidationController(
                ready.aggregateService(),
                new InternalRuntimeAuth("internal-token")
            ))
            .build()
            .perform(get("/internal/extension-registry/validation")
                .header(LynxusExtensionHeaders.AUTHORIZATION, "Bearer internal-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.service").value("api"))
            .andExpect(jsonPath("$.registryType").value("AGGREGATE"));

        ScenarioContext notReady = readyContext();
        notReady.runtimeClient().channelGateway = RuntimeRegistryValidationResult.failure(
            "channel-gateway",
            "request failure",
            null
        );
        MockMvcBuilders.standaloneSetup(new ExtensionAggregateRegistryValidationController(
                notReady.aggregateService(),
                new InternalRuntimeAuth("internal-token")
            ))
            .build()
            .perform(get("/internal/extension-registry/validation")
                .header(LynxusExtensionHeaders.AUTHORIZATION, "Bearer internal-token"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("NOT_READY"))
            .andExpect(jsonPath("$.errors[0].code").value("RUNTIME_REGISTRY_UNREACHABLE"));
    }

    @Test
    void runtimeValidationClientSendsBearerAuthOnlyAndRejectsNon200() throws Exception {
        List<Map<String, List<String>>> observedHeaders = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/extension-registry/tool-connectors/validation", exchange -> {
            observedHeaders.add(new LinkedHashMap<>(exchange.getRequestHeaders()));
            byte[] body = runtimeValidationJson(
                "agent-runtime",
                "TOOL_CONNECTOR",
                "sha256:test",
                Map.of("simple-http", "sha256:simple")
            ).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/internal/extension-registry/channel-providers/validation", exchange -> {
            observedHeaders.add(new LinkedHashMap<>(exchange.getRequestHeaders()));
            byte[] body = "Authorization Bearer internal-token externalSecretRef".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            JdkRuntimeRegistryValidationClient client = new JdkRuntimeRegistryValidationClient(
                java.net.http.HttpClient.newHttpClient(),
                new ObjectMapper(),
                baseUrl,
                baseUrl,
                "internal-token"
            );

            RuntimeRegistryValidationResult agentResult = client.agentRuntimeValidation();
            RuntimeRegistryValidationResult channelResult = client.channelGatewayValidation();

            assertTrue(agentResult.ready());
            assertFalse(channelResult.ready());
            assertEquals("RUNTIME_REGISTRY_UNREACHABLE", unreachableError(channelResult).code());
            assertEquals("non-2xx response", channelResult.failureReason());
            assertEquals(503, channelResult.httpStatus());
            for (Map<String, List<String>> headers : observedHeaders) {
                assertEquals(List.of("Bearer internal-token"), headers.get(LynxusExtensionHeaders.AUTHORIZATION));
                assertFalse(headers.containsKey(LynxusExtensionHeaders.REGISTRATION_ID));
                assertFalse(headers.containsKey(LynxusExtensionHeaders.DESCRIPTOR_TYPE));
                assertFalse(headers.containsKey(LynxusExtensionHeaders.DESCRIPTOR_ID));
                assertFalse(headers.containsKey(LynxusExtensionHeaders.IDEMPOTENCY_KEY));
                assertFalse(headers.containsKey(LynxusExtensionHeaders.TRACE_ID));
                assertFalse(headers.containsKey(LynxusExtensionHeaders.REQUEST_ID));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serializedAggregateValidationDoesNotExposeSlice5bSensitiveFields() throws Exception {
        ScenarioContext context = readyContext();
        context.runtimeClient().agentRuntime = RuntimeRegistryValidationResult.failure(
            "agent-runtime",
            "request failure",
            null
        );

        MvcResult result = MockMvcBuilders.standaloneSetup(new ExtensionAggregateRegistryValidationController(
                context.aggregateService(),
                new InternalRuntimeAuth("internal-token")
            ))
            .build()
            .perform(get("/internal/extension-registry/validation")
                .header(LynxusExtensionHeaders.AUTHORIZATION, "Bearer internal-token"))
            .andExpect(status().isServiceUnavailable())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("internal-token"));
        assertFalse(body.contains("Authorization"));
        assertFalse(body.contains("http://agent-runtime.example.com"));
        assertFalse(body.contains("http://channel-gateway.example.com"));
        assertFalse(body.contains("externalSecretRef"));
        assertFalse(body.contains("credential"));
        assertFalse(body.contains("/tools/simple-http/invoke"));
    }

    private static RegistryValidationError unreachableError(RuntimeRegistryValidationResult result) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("phase", "AGGREGATE_VALIDATION");
        details.put("runtimeService", result.runtimeService());
        details.put("httpStatus", result.httpStatus());
        details.put("failureReason", result.failureReason());
        return new RegistryValidationError(
            "RUNTIME_REGISTRY_UNREACHABLE",
            "ERROR",
            null,
            null,
            null,
            "Runtime registry validation endpoint could not be used for aggregate validation",
            true,
            details
        );
    }

    private static RegistryValidationError onlyError(ExtensionRegistryValidation validation) {
        assertEquals(1, validation.errors().size(), validation.errors().toString());
        return validation.errors().getFirst();
    }

    private static ScenarioContext readyContext() {
        CapturingFetcher fetcher = coreFetcher();
        ExtensionRegistrationService registrationService = registrationService("");
        ExtensionDefinitionService definitionService = new ExtensionDefinitionService(registrationService, fetcher, "internal-token");
        ExtensionDefinitionRegistry localRegistry = definitionService.loadRegistry();
        assertTrue(localRegistry.ready(), localRegistry.errors().toString());
        FakeRuntimeClient runtimeClient = new FakeRuntimeClient();
        runtimeClient.agentRuntime = RuntimeRegistryValidationResult.ready(
            "agent-runtime",
            runtimeValidation(
                "agent-runtime",
                "TOOL_CONNECTOR",
                registrationService.registrationConfigDigest(),
                toolConnectorDigests(localRegistry)
            )
        );
        runtimeClient.channelGateway = RuntimeRegistryValidationResult.ready(
            "channel-gateway",
            runtimeValidation(
                "channel-gateway",
                "CHANNEL_PROVIDER",
                registrationService.registrationConfigDigest(),
                channelProviderDigests(localRegistry)
            )
        );
        ExtensionAggregateRegistryValidationService aggregateService = new ExtensionAggregateRegistryValidationService(
            definitionService,
            registrationService,
            runtimeClient
        );
        return new ScenarioContext(registrationService, localRegistry, runtimeClient, aggregateService);
    }

    private static RuntimeRegistryValidation runtimeValidation(
        String service,
        String registryType,
        String registrationConfigDigest,
        Map<String, String> descriptorDigests
    ) {
        return new RuntimeRegistryValidation(
            "READY",
            service,
            "EXTENSION_REGISTRY",
            registryType,
            registrationConfigDigest,
            service + " registry is ready",
            List.of(),
            List.copyOf(descriptorDigests.keySet()),
            List.copyOf(descriptorDigests.keySet()),
            Map.copyOf(descriptorDigests),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static String runtimeValidationJson(
        String service,
        String registryType,
        String registrationConfigDigest,
        Map<String, String> descriptorDigests
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "READY");
        payload.put("service", service);
        payload.put("component", "EXTENSION_REGISTRY");
        payload.put("registryType", registryType);
        payload.put("registrationConfigDigest", registrationConfigDigest);
        payload.put("summary", service + " registry is ready");
        payload.put("errors", List.of());
        payload.put("expectedDescriptorIds", List.copyOf(descriptorDigests.keySet()));
        payload.put("loadedDescriptorIds", List.copyOf(descriptorDigests.keySet()));
        payload.put("descriptorDefinitionDigests", descriptorDigests);
        payload.put("missingDescriptorIds", List.of());
        payload.put("unexpectedDescriptorIds", List.of());
        payload.put("duplicateDescriptorIds", List.of());
        payload.put("manifestErrors", List.of());
        return LynxusCanonicalJson.canonicalizeValue(payload);
    }

    private static Map<String, String> toolConnectorDigests(ExtensionDefinitionRegistry registry) {
        Map<String, String> digests = new LinkedHashMap<>();
        for (ToolConnectorDefinition definition : registry.toolConnectors()) {
            digests.put(definition.connectorType(), definition.definitionDigest());
        }
        return digests;
    }

    private static Map<String, String> channelProviderDigests(ExtensionDefinitionRegistry registry) {
        Map<String, String> digests = new LinkedHashMap<>();
        for (ChannelProviderDefinition definition : registry.channelProviders()) {
            digests.put(definition.providerType(), definition.definitionDigest());
        }
        return digests;
    }

    private static ExtensionRegistrationService registrationService(String operatorYaml) {
        if (operatorYaml.isBlank()) {
            return new ExtensionRegistrationService(
                new ExtensionRegistrationProperties(null),
                "http://channel-gateway.example.com",
                "http://agent-runtime.example.com",
                key -> null
            );
        }
        try {
            Path tempFile = Files.createTempFile("lynxus-api-extension-registration", ".yaml");
            Files.writeString(tempFile, operatorYaml);
            return new ExtensionRegistrationService(
                new ExtensionRegistrationProperties(tempFile.toString()),
                "http://channel-gateway.example.com",
                "http://agent-runtime.example.com",
                key -> null
            );
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static CapturingFetcher coreFetcher() {
        CapturingFetcher fetcher = new CapturingFetcher();
        fetcher.responses.put(ExtensionRegistrationLoader.CORE_CHANNEL_GATEWAY_REGISTRATION_ID, manifest(
            List.of(channelProviderDescriptor("feishu")),
            List.of()
        ));
        fetcher.responses.put(ExtensionRegistrationLoader.CORE_AGENT_RUNTIME_REGISTRATION_ID, manifest(
            List.of(),
            List.of(businessCodeSecretHttpDescriptor(), mcpDescriptor(), simpleHttpDescriptor())
        ));
        return fetcher;
    }

    private static String manifest(List<Map<String, Object>> channelProviders, List<Map<String, Object>> toolConnectors) {
        Map<String, Object> descriptors = new LinkedHashMap<>();
        descriptors.put("channelProviders", channelProviders);
        descriptors.put("toolConnectors", toolConnectors);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("extensionApiVersion", 1);
        manifest.put("coreMinVersion", "0.8.0");
        manifest.put("coreMaxVersion", "0.9.x");
        manifest.put("descriptors", descriptors);
        return LynxusCanonicalJson.canonicalizeValue(manifest);
    }

    private static Map<String, Object> channelProviderDescriptor(String providerType) {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("sendOutbound", "/channel/send-outbound");

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("providerType", providerType);
        descriptor.put("title", "Test Provider");
        descriptor.put("description", "Provider description");
        descriptor.put("accountConfigSchema", Map.of());
        descriptor.put("accountConfigUiSchema", List.of());
        descriptor.put("configSchema", Map.of());
        descriptor.put("configUiSchema", List.of());
        descriptor.put("defaultConfig", Map.of());
        descriptor.put("jobDefinitions", List.of());
        descriptor.put("endpoints", endpoints);
        return descriptor;
    }

    private static Map<String, Object> businessCodeSecretHttpDescriptor() {
        Map<String, Object> descriptor = toolConnectorDescriptor("business-code-secret-http", "Business Code Secret HTTP");
        descriptor.put("credentialSchema", objectSchema(Map.of(
            "businessCode", Map.of("type", "string"),
            "secretKey", Map.of("type", "string")
        )));
        descriptor.put("credentialUiSchema", List.of());
        return descriptor;
    }

    private static Map<String, Object> mcpDescriptor() {
        return toolConnectorDescriptor("mcp", "MCP");
    }

    private static Map<String, Object> simpleHttpDescriptor() {
        Map<String, Object> descriptor = toolConnectorDescriptor("simple-http", "Simple HTTP");
        descriptor.put("credentialSchema", objectSchema(Map.of("bearerToken", Map.of("type", "string"))));
        descriptor.put("credentialUiSchema", List.of());
        return descriptor;
    }

    private static Map<String, Object> toolConnectorDescriptor(String connectorType, String title) {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("invoke", "/tools/" + connectorType + "/invoke");

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("connectorType", connectorType);
        descriptor.put("title", title);
        descriptor.put("description", title + " description");
        descriptor.put("accountConfigSchema", objectSchema(Map.of()));
        descriptor.put("accountConfigUiSchema", List.of());
        descriptor.put("configSchema", objectSchema(Map.of("endpoint", Map.of("type", "string"))));
        descriptor.put("configUiSchema", List.of());
        descriptor.put("operationMappingSchema", objectSchema(Map.of("operation", Map.of("type", "string"))));
        descriptor.put("operationMappingUiSchema", List.of());
        descriptor.put("endpoints", endpoints);
        return descriptor;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static final class CapturingFetcher implements ExtensionManifestFetcher {
        private final Map<String, String> responses = new LinkedHashMap<>();
        private final List<CapturedFetch> calls = new ArrayList<>();

        @Override
        public String fetch(String manifestUrl, Map<String, String> headers) throws IOException {
            calls.add(new CapturedFetch(manifestUrl, Map.copyOf(headers)));
            String registrationId = headers.get(LynxusExtensionHeaders.REGISTRATION_ID);
            if (!responses.containsKey(registrationId)) {
                throw new AssertionError("unexpected manifest fetch for " + registrationId);
            }
            return responses.get(registrationId);
        }
    }

    private static final class FakeRuntimeClient implements RuntimeRegistryValidationClient {
        private RuntimeRegistryValidationResult agentRuntime;
        private RuntimeRegistryValidationResult channelGateway;
        private int agentRuntimeCalls;
        private int channelGatewayCalls;

        @Override
        public RuntimeRegistryValidationResult agentRuntimeValidation() {
            agentRuntimeCalls += 1;
            return agentRuntime;
        }

        @Override
        public RuntimeRegistryValidationResult channelGatewayValidation() {
            channelGatewayCalls += 1;
            return channelGateway;
        }
    }

    private record ScenarioContext(
        ExtensionRegistrationService registrationService,
        ExtensionDefinitionRegistry localRegistry,
        FakeRuntimeClient runtimeClient,
        ExtensionAggregateRegistryValidationService aggregateService
    ) {}

    private record CapturedFetch(String url, Map<String, String> headers) {}
}
