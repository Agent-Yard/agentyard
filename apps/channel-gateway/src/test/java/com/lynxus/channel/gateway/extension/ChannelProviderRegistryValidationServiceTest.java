package com.lynxus.channel.gateway.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.extension.sdk.common.LynxusCanonicalJson;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHeaders;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class ChannelProviderRegistryValidationServiceTest {
    @Test
    void corePresetValidationIsReadyAndDoesNotFetchSelfHttp() {
        CapturingFetcher fetcher = new CapturingFetcher();
        ChannelProviderRegistryValidation validation = service("", fetcher).validate();

        assertEquals("READY", validation.status());
        assertEquals(List.of("feishu"), validation.expectedDescriptorIds());
        assertEquals(List.of("feishu"), validation.loadedDescriptorIds());
        assertTrue(validation.descriptorDefinitionDigests().get("feishu").startsWith("sha256:"));
        assertTrue(fetcher.calls.isEmpty());
    }

    @Test
    void nonCoreValidationFiltersToolOnlyRegistrationsAndUsesSdkUrlAndHeaders() {
        CapturingFetcher fetcher = new CapturingFetcher();
        fetcher.responses.put("acme-channel-provider", manifest(channelDescriptor("enterprise.acme.internal-im")));

        ChannelProviderRegistryValidation validation = service("""
            lynxus:
              extensions:
                services:
                  - registrationId: acme-tools
                    baseUrl: http://tools.example.com
                    exposes:
                      toolConnectorTypes:
                        - enterprise.acme.crm
                    auth:
                      type: INTERNAL_TOKEN
                  - registrationId: acme-channel-provider
                    baseUrl: http://channel.example.com/lynxus/
                    exposes:
                      channelProviderTypes:
                        - enterprise.acme.internal-im
                    auth:
                      type: INTERNAL_TOKEN
            """, fetcher).validate();

        assertEquals("READY", validation.status());
        assertEquals(List.of("enterprise.acme.internal-im", "feishu"), validation.loadedDescriptorIds());
        assertEquals(1, fetcher.calls.size());
        CapturedFetch call = fetcher.calls.getFirst();
        assertEquals("http://channel.example.com/lynxus/extension/manifest", call.url());
        assertEquals("Bearer internal-token", call.headers().get(LynxusExtensionHeaders.AUTHORIZATION));
        assertEquals("acme-channel-provider", call.headers().get(LynxusExtensionHeaders.REGISTRATION_ID));
        assertFalse(call.headers().containsKey(LynxusExtensionHeaders.DESCRIPTOR_ID));
        assertFalse(call.headers().containsKey(LynxusExtensionHeaders.IDEMPOTENCY_KEY));
    }

    @Test
    void validationReflectsLoadedRuntimeRegistrySnapshotWithoutReloading() {
        CapturingFetcher fetcher = new CapturingFetcher();
        fetcher.responses.put("acme-channel-provider", manifest(channelDescriptor("enterprise.acme.internal-im")));
        ChannelProviderRegistryValidationService service = service("""
            lynxus:
              extensions:
                services:
                  - registrationId: acme-channel-provider
                    baseUrl: http://channel.example.com
                    exposes:
                      channelProviderTypes:
                        - enterprise.acme.internal-im
                    auth:
                      type: INTERNAL_TOKEN
            """, fetcher);
        fetcher.calls.clear();
        fetcher.responses.clear();

        ChannelProviderRegistryValidation validation = service.validate();

        assertEquals("READY", validation.status());
        assertEquals(List.of("enterprise.acme.internal-im", "feishu"), validation.loadedDescriptorIds());
        assertTrue(fetcher.calls.isEmpty());
    }

    @Test
    void notReadyReportsMissingUnexpectedDuplicateAndReturnsHttp503() throws Exception {
        CapturingFetcher fetcher = new CapturingFetcher();
        fetcher.responses.put("acme-a", manifest(
            channelDescriptor("enterprise.acme.duplicate"),
            channelDescriptor("enterprise.acme.unexpected")
        ));
        fetcher.responses.put("acme-b", manifest(channelDescriptor("enterprise.acme.duplicate")));
        ChannelProviderRegistryValidationService service = service("""
            lynxus:
              extensions:
                services:
                  - registrationId: acme-a
                    baseUrl: http://a.example.com
                    exposes:
                      channelProviderTypes:
                        - enterprise.acme.expected
                        - enterprise.acme.duplicate
                    auth:
                      type: INTERNAL_TOKEN
                  - registrationId: acme-b
                    baseUrl: http://b.example.com
                    exposes:
                      channelProviderTypes:
                        - enterprise.acme.duplicate
                    auth:
                      type: INTERNAL_TOKEN
            """, fetcher);

        ChannelProviderRegistryValidation validation = service.validate();

        assertEquals("NOT_READY", validation.status());
        assertEquals(List.of("enterprise.acme.expected"), validation.missingDescriptorIds());
        assertEquals(List.of("enterprise.acme.unexpected"), validation.unexpectedDescriptorIds());
        assertEquals(List.of("enterprise.acme.duplicate"), validation.duplicateDescriptorIds());
        assertTrue(validation.errors().stream().anyMatch(error -> "MISSING_DESCRIPTOR".equals(error.code())));
        assertTrue(validation.errors().stream().anyMatch(error -> "UNEXPECTED_DESCRIPTOR".equals(error.code())));
        assertTrue(validation.errors().stream().anyMatch(error -> "DUPLICATE_DESCRIPTOR".equals(error.code())));

        MockMvcBuilders.standaloneSetup(new ChannelProviderRegistryValidationController(service))
            .build()
            .perform(get("/internal/extension-registry/channel-providers/validation"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("NOT_READY"))
            .andExpect(jsonPath("$.component").value("EXTENSION_REGISTRY"))
            .andExpect(jsonPath("$.registryType").value("CHANNEL_PROVIDER"));
    }

    @Test
    void schemaErrorsAreNormalizedWithoutSensitiveDetails() {
        CapturingFetcher fetcher = new CapturingFetcher();
        fetcher.responses.put("acme-channel-provider", manifest(invalidChannelDescriptor("enterprise.acme.invalid")));

        ChannelProviderRegistryValidation validation = service("""
            lynxus:
              extensions:
                services:
                  - registrationId: acme-channel-provider
                    baseUrl: http://channel.example.com
                    exposes:
                      channelProviderTypes:
                        - enterprise.acme.invalid
                    auth:
                      type: INTERNAL_TOKEN
            """, fetcher).validate();

        assertEquals("NOT_READY", validation.status());
        RegistryValidationError schemaError = validation.manifestErrors().getFirst();
        assertEquals("MANIFEST_SCHEMA_INVALID", schemaError.code());
        assertEquals("MANIFEST_FETCH", schemaError.details().get("phase"));
        assertTrue(schemaError.details().containsKey("schemaPath"));
        assertEquals("MANIFEST_SCHEMA_INVALID", schemaError.details().get("violation"));
        assertFalse(schemaError.details().containsKey("path"));
        assertFalse(schemaError.details().toString().contains("internal-token"));
        assertFalse(schemaError.details().toString().contains("Authorization"));
    }

    @Test
    void fetchErrorsAreNormalizedWithoutSensitiveDetails() {
        CapturingFetcher fetcher = new CapturingFetcher();
        fetcher.failures.put("acme-channel-provider", new IOException("Authorization Bearer internal-token rejected"));

        ChannelProviderRegistryValidation validation = service("""
            lynxus:
              extensions:
                services:
                  - registrationId: acme-channel-provider
                    baseUrl: http://channel.example.com
                    exposes:
                      channelProviderTypes:
                        - enterprise.acme.internal-im
                    auth:
                      type: INTERNAL_TOKEN
            """, fetcher).validate();

        assertEquals("NOT_READY", validation.status());
        RegistryValidationError fetchError = validation.manifestErrors().getFirst();
        assertEquals("MANIFEST_FETCH_FAILED", fetchError.code());
        assertEquals("MANIFEST_FETCH", fetchError.details().get("phase"));
        assertTrue(fetchError.details().containsKey("httpStatus"));
        assertEquals(null, fetchError.details().get("httpStatus"));
        assertEquals("IOException", fetchError.details().get("failureReason"));
        assertFalse(fetchError.details().containsKey("failure"));
        assertFalse(fetchError.details().toString().contains("internal-token"));
        assertFalse(fetchError.details().toString().contains("Authorization"));
    }

    private static ChannelProviderRegistryValidationService service(String operatorYaml, CapturingFetcher fetcher) {
        ExtensionRegistrationService registrationService = operatorYaml.isBlank()
            ? new ExtensionRegistrationService(
                new ExtensionRegistrationProperties(null),
                "http://channel-gateway.example.com",
                "http://agent-runtime.example.com",
                key -> null
            )
            : registrationServiceFromYaml(operatorYaml);
        return new ChannelProviderRegistryValidationService(
            registrationService,
            new RuntimeChannelProviderRegistry(new ChannelProviderRegistryLoader(
                registrationService,
                new ChannelGatewayDescriptorProvider(),
                fetcher,
                "internal-token"
            ))
        );
    }

    private static ExtensionRegistrationService registrationServiceFromYaml(String operatorYaml) {
        try {
            Path tempFile = Files.createTempFile("lynxus-extension-registration", ".yaml");
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

    @SafeVarargs
    private static String manifest(Map<String, Object>... channelDescriptors) {
        Map<String, Object> descriptors = new LinkedHashMap<>();
        descriptors.put("channelProviders", List.of(channelDescriptors));
        descriptors.put("toolConnectors", List.of());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("extensionApiVersion", 1);
        manifest.put("coreMinVersion", "0.8.0");
        manifest.put("coreMaxVersion", "0.9.x");
        manifest.put("descriptors", descriptors);
        return LynxusCanonicalJson.canonicalizeValue(manifest);
    }

    private static Map<String, Object> channelDescriptor(String providerType) {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("providerType", providerType);
        descriptor.put("title", "Test Provider");
        descriptor.put("accountConfigSchema", Map.of());
        descriptor.put("accountConfigUiSchema", List.of());
        descriptor.put("configSchema", Map.of());
        descriptor.put("configUiSchema", List.of());
        descriptor.put("outbound", outbound());
        descriptor.put("endpoints", endpoints);
        return descriptor;
    }

    private static Map<String, Object> outbound() {
        return Map.of(
            "mode", "FRAME_STREAM",
            "supportsTyping", false,
            "supportsDraftUpdate", false,
            "supportsFinalDelivery", true,
            "requiresIdempotentFinalDelivery", true
        );
    }

    private static Map<String, Object> invalidChannelDescriptor(String providerType) {
        Map<String, Object> descriptor = new LinkedHashMap<>(channelDescriptor(providerType));
        descriptor.remove("title");
        return descriptor;
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
}
