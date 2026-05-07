package com.lynxus.platform.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.extension.sdk.common.LynxusCanonicalJson;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHeaders;
import com.lynxus.extension.sdk.registration.ExtensionRegistrationLoader;
import com.lynxus.platform.extension.ExtensionDefinitionDtos.ChannelProviderDefinition;
import com.lynxus.platform.extension.ExtensionDefinitionDtos.CredentialCapabilityMode;
import com.lynxus.platform.extension.ExtensionDefinitionDtos.ToolConnectorDefinition;
import com.lynxus.platform.shared.ApiExceptionHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class ExtensionDefinitionServiceTest {
    @Test
    void coreOnlyRegistrationReturnsFeishuAndAgentRuntimeToolDefinitions() {
        CapturingFetcher fetcher = coreFetcher();
        ExtensionDefinitionService service = service("", fetcher);

        List<ChannelProviderDefinition> channelProviders = service.channelProviders();
        List<ToolConnectorDefinition> toolConnectors = service.toolConnectors();

        assertEquals(List.of("feishu"), channelProviders.stream().map(ChannelProviderDefinition::providerType).toList());
        assertEquals(
            List.of("business-code-secret-http", "mcp", "simple-http"),
            toolConnectors.stream().map(ToolConnectorDefinition::connectorType).toList()
        );
        assertTrue(channelProviders.getFirst().definitionDigest().startsWith("sha256:"));
        assertEquals(Map.of(), channelProviders.getFirst().configSchema());
        assertEquals(Map.of(), channelProviders.getFirst().defaultConfig());
        assertTrue(channelProviders.getFirst().jobDefinitions().isEmpty());

        ToolConnectorDefinition simpleHttp = toolConnectors.stream()
            .filter(definition -> "simple-http".equals(definition.connectorType()))
            .findFirst()
            .orElseThrow();
        assertTrue(simpleHttp.definitionDigest().startsWith("sha256:"));
        assertTrue(simpleHttp.configSchema().containsKey("properties"));
        assertTrue(simpleHttp.operationMappingSchema().containsKey("properties"));
        assertEquals(CredentialCapabilityMode.CORE_ENCRYPTED_REFERENCE, simpleHttp.credentialCapability().mode());
        assertNotNull(simpleHttp.credentialCapability().credentialSchema());
    }

    @Test
    void serializedDefinitionsDoNotExposeRegistryOrCredentialMaterials() throws Exception {
        CapturingFetcher fetcher = coreFetcher();
        ExtensionDefinitionService service = service("", fetcher);

        MvcResult result = MockMvcBuilders
            .standaloneSetup(new ExtensionDefinitionController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build()
            .perform(get("/api/extensions/tool-connectors"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].definitionDigest").exists())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("internal-token"));
        assertFalse(body.contains("http://agent-runtime.example.com"));
        assertFalse(body.contains("core-agent-runtime"));
        assertFalse(body.contains("/tools/simple-http/invoke"));
        assertFalse(body.contains("externalSecretRef"));
        assertFalse(body.contains("secret-plaintext"));
        assertFalse(body.contains("Authorization"));
    }

    @Test
    void credentialCapabilitiesCoverUnsupportedCoreEncryptedReferenceAndRemoteLifecycle() {
        CapturingFetcher fetcher = coreFetcher();
        fetcher.responses.put("acme-remote", manifest(List.of(), List.of(remoteToolConnectorDescriptor())));

        ExtensionDefinitionService service = service("""
            lynxus:
              extensions:
                services:
                  - registrationId: acme-remote
                    baseUrl: https://remote.example.com/private
                    exposes:
                      toolConnectorTypes:
                        - enterprise.acme.crm
                    auth:
                      type: INTERNAL_TOKEN
            """, fetcher);

        Map<String, ToolConnectorDefinition> definitions = new LinkedHashMap<>();
        for (ToolConnectorDefinition definition : service.toolConnectors()) {
            definitions.put(definition.connectorType(), definition);
        }

        assertFalse(definitions.get("mcp").credentialCapability().supported());
        assertEquals(null, definitions.get("mcp").credentialCapability().mode());
        assertFalse(definitions.get("mcp").credentialCapability().supportsValidate());
        assertEquals(CredentialCapabilityMode.CORE_ENCRYPTED_REFERENCE, definitions.get("simple-http").credentialCapability().mode());
        assertTrue(definitions.get("simple-http").credentialCapability().supportsValidate());
        assertEquals(CredentialCapabilityMode.REMOTE_LIFECYCLE, definitions.get("enterprise.acme.crm").credentialCapability().mode());
        assertTrue(definitions.get("enterprise.acme.crm").credentialCapability().supportsValidate());
    }

    @Test
    void remoteCredentialLifecycleDoesNotRequireValidateEndpoint() {
        CapturingFetcher fetcher = coreFetcher();
        fetcher.responses.put("acme-remote", manifest(List.of(), List.of(remoteToolConnectorDescriptor()), false));

        ExtensionDefinitionService service = service("""
            lynxus:
              extensions:
                services:
                  - registrationId: acme-remote
                    baseUrl: https://remote.example.com/private
                    exposes:
                      toolConnectorTypes:
                        - enterprise.acme.crm
                    auth:
                      type: INTERNAL_TOKEN
            """, fetcher);

        ToolConnectorDefinition definition = service.toolConnectors().stream()
            .filter(candidate -> "enterprise.acme.crm".equals(candidate.connectorType()))
            .findFirst()
            .orElseThrow();

        assertEquals(CredentialCapabilityMode.REMOTE_LIFECYCLE, definition.credentialCapability().mode());
        assertFalse(definition.credentialCapability().supportsValidate());
    }


    @Test
    void manifestFetchUsesServiceLevelHeadersOnly() {
        CapturingFetcher fetcher = coreFetcher();
        service("", fetcher).channelProviders();

        assertEquals(2, fetcher.calls.size());
        for (CapturedFetch call : fetcher.calls) {
            assertTrue(call.url().endsWith("/extension/manifest"));
            assertEquals("Bearer internal-token", call.headers().get(LynxusExtensionHeaders.AUTHORIZATION));
            assertTrue(call.headers().containsKey(LynxusExtensionHeaders.REGISTRATION_ID));
            assertFalse(call.headers().containsKey(LynxusExtensionHeaders.DESCRIPTOR_TYPE));
            assertFalse(call.headers().containsKey(LynxusExtensionHeaders.DESCRIPTOR_ID));
            assertFalse(call.headers().containsKey(LynxusExtensionHeaders.TRACE_ID));
            assertFalse(call.headers().containsKey(LynxusExtensionHeaders.REQUEST_ID));
            assertFalse(call.headers().containsKey(LynxusExtensionHeaders.IDEMPOTENCY_KEY));
        }
    }

    @Test
    void registryErrorsPreventDefinitionServingWithHttp503() throws Exception {
        List<Scenario> scenarios = List.of(
            new Scenario("missing", manifest(List.of(), List.of(remoteToolConnectorDescriptor())), "MISSING_DESCRIPTOR"),
            new Scenario("unexpected", manifest(List.of(channelProviderDescriptor("enterprise.acme.unexpected")), List.of()), "UNEXPECTED_DESCRIPTOR"),
            new Scenario(
                "duplicate",
                manifest(
                    List.of(channelProviderDescriptor("enterprise.acme.chat"), channelProviderDescriptor("enterprise.acme.chat")),
                    List.of()
                ),
                "DUPLICATE_DESCRIPTOR"
            ),
            new Scenario("schema", manifest(List.of(invalidChannelProviderDescriptor("enterprise.acme.chat")), List.of()), "MANIFEST_SCHEMA_INVALID")
        );

        for (Scenario scenario : scenarios) {
            CapturingFetcher fetcher = coreFetcher();
            fetcher.responses.put("acme-channel", scenario.manifest());
            ExtensionDefinitionService service = service("""
                lynxus:
                  extensions:
                    services:
                      - registrationId: acme-channel
                        baseUrl: https://channel.example.com/private
                        exposes:
                          channelProviderTypes:
                            - enterprise.acme.chat
                        auth:
                          type: INTERNAL_TOKEN
            """, fetcher);

            assertEquals("NOT_READY", service.loadRegistry().ready() ? "READY" : "NOT_READY", scenario.label());
            assertTrue(
                service.loadRegistry().errors().stream().anyMatch(error -> scenario.expectedCode().equals(error.code())),
                scenario.label()
            );
            MockMvcBuilders.standaloneSetup(new ExtensionDefinitionController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(get("/api/extensions/channel-providers"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Extension definition registry is NOT_READY"));
        }

        CapturingFetcher fetcher = coreFetcher();
        fetcher.failures.put("acme-channel", new IOException("Bearer internal-token leaked by exception"));
        ExtensionDefinitionService service = service("""
            lynxus:
              extensions:
                services:
                  - registrationId: acme-channel
                    baseUrl: https://channel.example.com/private
                    exposes:
                      channelProviderTypes:
                        - enterprise.acme.chat
                    auth:
                      type: INTERNAL_TOKEN
        """, fetcher);
        assertTrue(service.loadRegistry().errors().stream().anyMatch(error -> "MANIFEST_FETCH_FAILED".equals(error.code())));
        assertFalse(service.loadRegistry().errors().toString().contains("internal-token"));
        MockMvcBuilders.standaloneSetup(new ExtensionDefinitionController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build()
            .perform(get("/api/extensions/channel-providers"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.detail").value("Extension definition registry is NOT_READY"));
    }

    @Test
    void defaultConfigSecretLikeKeysMakeRegistryNotReadyWithoutLeakingValue() throws Exception {
        String secretValue = "api-key-plaintext-value";
        Map<String, Object> descriptor = new LinkedHashMap<>(channelProviderDescriptor("enterprise.acme.chat"));
        descriptor.put("defaultConfig", Map.of("apiKey", secretValue));

        ExtensionDefinitionService service = remoteChannelProviderService(descriptor);

        ExtensionDefinitionService.ExtensionDefinitionRegistry registry = service.loadRegistry();
        assertFalse(registry.ready());
        RegistryValidationError error = registry.errors().stream()
            .filter(candidate -> "MANIFEST_SCHEMA_INVALID".equals(candidate.code()))
            .findFirst()
            .orElseThrow();
        assertEquals("MANIFEST_FETCH", error.details().get("phase"));
        assertEquals("/descriptors/channelProviders/0/defaultConfig/apiKey", error.details().get("schemaPath"));
        assertEquals("CONFIG_SECRET_MATERIAL_NOT_ALLOWED", error.details().get("violation"));
        assertFalse(registry.errors().toString().contains(secretValue));

        MvcResult result = MockMvcBuilders.standaloneSetup(new ExtensionDefinitionController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build()
            .perform(get("/api/extensions/channel-providers"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.detail").value("Extension definition registry is NOT_READY"))
            .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains(secretValue));
    }

    @Test
    void defaultScheduleJobConfigSecretLikeKeysMakeRegistryNotReadyWithoutLeakingValue() throws Exception {
        String secretValue = "webhook-signing-secret-plaintext";
        Map<String, Object> descriptor = new LinkedHashMap<>(channelProviderDescriptor("enterprise.acme.chat"));
        descriptor.put("jobDefinitions", List.of(providerJobDefinition(Map.of("webhookSigningSecret", secretValue))));

        ExtensionDefinitionService service = remoteChannelProviderService(descriptor);

        ExtensionDefinitionService.ExtensionDefinitionRegistry registry = service.loadRegistry();
        assertFalse(registry.ready());
        RegistryValidationError error = registry.errors().stream()
            .filter(candidate -> "MANIFEST_SCHEMA_INVALID".equals(candidate.code()))
            .findFirst()
            .orElseThrow();
        assertEquals("MANIFEST_FETCH", error.details().get("phase"));
        assertEquals(
            "/descriptors/channelProviders/0/jobDefinitions/0/defaultSchedule/jobConfig/webhookSigningSecret",
            error.details().get("schemaPath")
        );
        assertEquals("CONFIG_SECRET_MATERIAL_NOT_ALLOWED", error.details().get("violation"));
        assertFalse(registry.errors().toString().contains(secretValue));

        MvcResult result = MockMvcBuilders.standaloneSetup(new ExtensionDefinitionController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build()
            .perform(get("/api/extensions/channel-providers"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.detail").value("Extension definition registry is NOT_READY"))
            .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains(secretValue));
    }

    @Test
    void remoteCredentialSchemaApiKeyIsAllowedWhenCredentialEndpointProfileIsDeclared() throws Exception {
        CapturingFetcher fetcher = coreFetcher();
        fetcher.responses.put("acme-remote", manifest(List.of(), List.of(remoteToolConnectorDescriptor())));

        ExtensionDefinitionService service = service("""
            lynxus:
              extensions:
                services:
                  - registrationId: acme-remote
                    baseUrl: https://remote.example.com/private
                    exposes:
                      toolConnectorTypes:
                        - enterprise.acme.crm
                    auth:
                      type: INTERNAL_TOKEN
            """, fetcher);

        ExtensionDefinitionService.ExtensionDefinitionRegistry registry = service.loadRegistry();
        assertTrue(registry.ready(), registry.errors().toString());

        MvcResult result = MockMvcBuilders.standaloneSetup(new ExtensionDefinitionController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build()
            .perform(get("/api/extensions/tool-connectors"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.connectorType == 'enterprise.acme.crm')].credentialCapability.mode")
                .value("REMOTE_LIFECYCLE"))
            .andReturn();
        assertTrue(result.getResponse().getContentAsString().contains("apiKey"));
    }

    private static ExtensionDefinitionService service(String operatorYaml, CapturingFetcher fetcher) {
        ExtensionRegistrationService registrationService = operatorYaml.isBlank()
            ? new ExtensionRegistrationService(
                new ExtensionRegistrationProperties(null),
                "http://channel-gateway.example.com",
                "http://agent-runtime.example.com",
                key -> null
            )
            : registrationServiceFromYaml(operatorYaml);
        return new ExtensionDefinitionService(registrationService, fetcher, "internal-token");
    }

    private static ExtensionDefinitionService remoteChannelProviderService(Map<String, Object> descriptor) {
        CapturingFetcher fetcher = coreFetcher();
        fetcher.responses.put("acme-channel", manifest(List.of(descriptor), List.of()));
        return service("""
            lynxus:
              extensions:
                services:
                  - registrationId: acme-channel
                    baseUrl: https://channel.example.com/private
                    exposes:
                      channelProviderTypes:
                        - enterprise.acme.chat
                    auth:
                      type: INTERNAL_TOKEN
        """, fetcher);
    }

    private static ExtensionRegistrationService registrationServiceFromYaml(String operatorYaml) {
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
        return manifest(channelProviders, toolConnectors, true);
    }

    private static String manifest(
        List<Map<String, Object>> channelProviders,
        List<Map<String, Object>> toolConnectors,
        boolean includeValidate
    ) {
        Map<String, Object> descriptors = new LinkedHashMap<>();
        descriptors.put("channelProviders", channelProviders);
        descriptors.put("toolConnectors", toolConnectors);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("extensionApiVersion", 1);
        manifest.put("coreMinVersion", "0.8.0");
        manifest.put("coreMaxVersion", "0.9.x");
        manifest.put("credentialLifecycleEndpointProfiles", defaultCredentialLifecycleEndpointProfiles(includeValidate));
        manifest.put("descriptors", descriptors);
        return LynxusCanonicalJson.canonicalizeValue(manifest);
    }

    private static Map<String, Object> defaultCredentialLifecycleEndpointProfiles(boolean includeValidate) {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("createCredential", "/credentials/create");
        endpoints.put("rotateCredential", "/credentials/rotate");
        endpoints.put("revokeCredential", "/credentials/revoke");
        if (includeValidate) {
            endpoints.put("validateCredential", "/credentials/validate");
        }
        return Map.of("default", endpoints);
    }

    private static Map<String, Object> channelProviderDescriptor(String providerType) {
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
        descriptor.put("outbound", channelOutboundCapability());
        descriptor.put("endpoints", Map.of());
        return descriptor;
    }

    private static Map<String, Object> channelOutboundCapability() {
        return Map.of(
            "mode", "FRAME_STREAM",
            "supportsTyping", true,
            "supportsDraftUpdate", true,
            "supportsFinalDelivery", true,
            "requiresIdempotentFinalDelivery", true
        );
    }

    private static Map<String, Object> invalidChannelProviderDescriptor(String providerType) {
        Map<String, Object> descriptor = new LinkedHashMap<>(channelProviderDescriptor(providerType));
        descriptor.remove("title");
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

    private static Map<String, Object> remoteToolConnectorDescriptor() {
        Map<String, Object> descriptor = toolConnectorDescriptor("enterprise.acme.crm", "Acme CRM");
        descriptor.put("credentialSchema", objectSchema(Map.of("apiKey", Map.of("type", "string"))));
        descriptor.put("credentialUiSchema", List.of());
        descriptor.put("credentialLifecycleEndpointProfile", "default");
        return descriptor;
    }

    private static Map<String, Object> providerJobDefinition(Map<String, Object> jobConfig) {
        Map<String, Object> defaultSchedule = new LinkedHashMap<>();
        defaultSchedule.put("scheduleType", "INTERVAL");
        defaultSchedule.put("intervalSeconds", 300);
        defaultSchedule.put("timezone", "UTC");
        defaultSchedule.put("jobConfig", jobConfig);

        Map<String, Object> job = new LinkedHashMap<>();
        job.put("jobType", "sync");
        job.put("title", "Sync");
        job.put("jobConfigSchema", objectSchema(Map.of()));
        job.put("jobConfigUiSchema", List.of());
        job.put("defaultSchedule", defaultSchedule);
        job.put("defaultEnabled", true);
        job.put("defaultJobTimeoutSeconds", 60);
        return job;
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
        private final Map<String, IOException> failures = new LinkedHashMap<>();
        private final List<CapturedFetch> calls = new ArrayList<>();

        @Override
        public String fetch(String manifestUrl, Map<String, String> headers) throws IOException {
            calls.add(new CapturedFetch(manifestUrl, Map.copyOf(headers)));
            String registrationId = headers.get(LynxusExtensionHeaders.REGISTRATION_ID);
            if (failures.containsKey(registrationId)) {
                throw failures.get(registrationId);
            }
            if (!responses.containsKey(registrationId)) {
                throw new AssertionError("unexpected manifest fetch for " + registrationId);
            }
            return responses.get(registrationId);
        }
    }

    private record CapturedFetch(String url, Map<String, String> headers) {}

    private record Scenario(String label, String manifest, String expectedCode) {}
}
