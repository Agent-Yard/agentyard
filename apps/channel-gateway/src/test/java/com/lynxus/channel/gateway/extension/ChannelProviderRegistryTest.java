package com.lynxus.channel.gateway.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.channel.gateway.connector.feishu.FeishuGatewayNativeChannelProviderAdapter;
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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

final class ChannelProviderRegistryTest {
    @Test
    void loadsCorePresetWithoutSelfHttp() {
        CapturingFetcher fetcher = new CapturingFetcher();
        RuntimeChannelProviderRegistry registry = registry("", fetcher);

        assertTrue(registry.snapshot().ready());
        assertEquals("feishu", registry.requireProvider("feishu").providerType());
        assertTrue(fetcher.calls.isEmpty());
    }

    @Test
    void springContextCreatesRegistryFromLoaderConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getSystemProperties()
                .put("lynxus.channel-gateway.base-url", "http://channel-gateway.example.com");
            context.getEnvironment().getSystemProperties()
                .put("lynxus.agent-runtime.base-url", "http://agent-runtime.example.com");
            context.registerBean(ExtensionRegistrationProperties.class, () -> new ExtensionRegistrationProperties(null));
            context.registerBean(ExtensionRegistrationService.class);
            context.registerBean(FeishuGatewayNativeChannelProviderAdapter.class);
            context.registerBean(GatewayNativeChannelProviderAdapters.class);
            context.registerBean(ChannelGatewayDescriptorProvider.class);
            context.registerBean(ExtensionManifestFetcher.class, CapturingFetcher::new);
            context.registerBean("internalAuthToken", String.class, () -> "internal-token");
            context.registerBean(ChannelProviderRegistryLoader.class);
            context.registerBean(RuntimeChannelProviderRegistry.class);
            context.refresh();

            RuntimeChannelProviderRegistry registry = context.getBean(RuntimeChannelProviderRegistry.class);
            assertTrue(registry.snapshot().ready());
            assertEquals("feishu", registry.requireProvider("feishu").providerType());
        }
    }

    @Test
    void ignoresToolOnlyRegistrationsAndLoadsRemoteChannelProvidersWithSdkHeaders() {
        CapturingFetcher fetcher = new CapturingFetcher();
        fetcher.responses.put("acme-channel-provider", manifest(channelDescriptor(
            "enterprise.acme.internal-im",
            objectSchema(Map.of("tenant", Map.of("type", "string")), List.of("tenant")),
            Map.of("tenant", "acme")
        )));

        RuntimeChannelProviderRegistry registry = registry("""
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
            """, fetcher);

        assertTrue(registry.snapshot().ready());
        assertEquals("enterprise.acme.internal-im", registry.requireProvider("enterprise.acme.internal-im").providerType());
        assertEquals(Map.of("tenant", "custom"), registry.materializeAndValidateProfileConfig(
            "enterprise.acme.internal-im",
            Map.of("tenant", "custom")
        ));
        assertEquals(1, fetcher.calls.size());
        CapturedFetch call = fetcher.calls.getFirst();
        assertEquals("http://channel.example.com/lynxus/extension/manifest", call.url());
        assertEquals("Bearer internal-token", call.headers().get(LynxusExtensionHeaders.AUTHORIZATION));
        assertEquals("acme-channel-provider", call.headers().get(LynxusExtensionHeaders.REGISTRATION_ID));
        assertFalse(call.headers().containsKey(LynxusExtensionHeaders.DESCRIPTOR_ID));
        assertFalse(call.headers().containsKey(LynxusExtensionHeaders.IDEMPOTENCY_KEY));
    }

    @Test
    void rejectsUnknownProviderTypeClearly() {
        RuntimeChannelProviderRegistry registry = registry("", new CapturingFetcher());

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> registry.requireProvider("missing-provider")
        );

        assertEquals("unknown channelProfile.providerType: missing-provider", error.getMessage());
    }

    @Test
    void appliesDefaultConfigOnlyWhenConfigIsNullAndRejectsExplicitEmptyConfig() {
        CapturingFetcher fetcher = new CapturingFetcher();
        fetcher.responses.put("acme-channel-provider", manifest(channelDescriptor(
            "enterprise.acme.configured",
            objectSchema(Map.of("tenant", Map.of("type", "string")), List.of("tenant")),
            Map.of("tenant", "acme")
        )));
        RuntimeChannelProviderRegistry registry = registry("""
            lynxus:
              extensions:
                services:
                  - registrationId: acme-channel-provider
                    baseUrl: http://channel.example.com
                    exposes:
                      channelProviderTypes:
                        - enterprise.acme.configured
                    auth:
                      type: INTERNAL_TOKEN
            """, fetcher);

        assertEquals(
            Map.of("tenant", "acme"),
            registry.materializeAndValidateProfileConfig("enterprise.acme.configured", null)
        );

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> registry.materializeAndValidateProfileConfig("enterprise.acme.configured", Map.of())
        );
        assertEquals("channel profile config does not satisfy provider configSchema", error.getMessage());
    }

    @Test
    void missingDuplicateAndInvalidDefaultConfigMakeRegistryNotReady() {
        CapturingFetcher fetcher = new CapturingFetcher();
        fetcher.responses.put("acme-a", manifest(
            channelDescriptor("enterprise.acme.duplicate"),
            channelDescriptor(
                "enterprise.acme.bad-default",
                objectSchema(Map.of("tenant", Map.of("type", "string")), List.of("tenant")),
                Map.of("tenant", 123)
            )
        ));
        fetcher.responses.put("acme-b", manifest(channelDescriptor("enterprise.acme.duplicate")));

        RuntimeChannelProviderRegistry registry = registry("""
            lynxus:
              extensions:
                services:
                  - registrationId: acme-a
                    baseUrl: http://a.example.com
                    exposes:
                      channelProviderTypes:
                        - enterprise.acme.expected
                        - enterprise.acme.duplicate
                        - enterprise.acme.bad-default
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

        ChannelProviderRegistryLoadResult snapshot = registry.snapshot();
        assertFalse(snapshot.ready());
        assertTrue(snapshot.missingDescriptorIds().contains("enterprise.acme.expected"));
        assertEquals(List.of("enterprise.acme.duplicate"), snapshot.duplicateDescriptorIds());
        assertTrue(snapshot.manifestErrors().stream().anyMatch(error ->
            "enterprise.acme.bad-default".equals(error.descriptorId())
                && "DEFAULT_CONFIG_INVALID".equals(error.details().get("violation"))
        ));
        assertThrows(IllegalStateException.class, () -> registry.requireProvider("enterprise.acme.duplicate"));
    }

    private static RuntimeChannelProviderRegistry registry(String operatorYaml, CapturingFetcher fetcher) {
        ExtensionRegistrationService registrationService = operatorYaml.isBlank()
            ? new ExtensionRegistrationService(
                new ExtensionRegistrationProperties(null),
                "http://channel-gateway.example.com",
                "http://agent-runtime.example.com"
            )
            : registrationServiceFromYaml(operatorYaml);
        return new RuntimeChannelProviderRegistry(new ChannelProviderRegistryLoader(
            registrationService,
            new ChannelGatewayDescriptorProvider(),
            fetcher,
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
        return channelDescriptor(providerType, Map.of(), Map.of());
    }

    private static Map<String, Object> channelDescriptor(
        String providerType,
        Map<String, Object> configSchema,
        Map<String, Object> defaultConfig
    ) {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("sendOutbound", "/channel/send-outbound");
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("providerType", providerType);
        descriptor.put("title", "Test Provider");
        descriptor.put("accountConfigSchema", Map.of());
        descriptor.put("accountConfigUiSchema", List.of());
        descriptor.put("configSchema", configSchema);
        descriptor.put("configUiSchema", List.of());
        descriptor.put("defaultConfig", defaultConfig);
        descriptor.put("endpoints", endpoints);
        return descriptor;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
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

    private record CapturedFetch(String url, Map<String, String> headers) {}
}
