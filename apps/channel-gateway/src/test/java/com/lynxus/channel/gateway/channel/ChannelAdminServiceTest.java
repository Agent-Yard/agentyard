package com.lynxus.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.channel.gateway.extension.ChannelGatewayDescriptorProvider;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistryLoader;
import com.lynxus.channel.gateway.extension.ExtensionManifestFetcher;
import com.lynxus.channel.gateway.extension.ExtensionRegistrationProperties;
import com.lynxus.channel.gateway.extension.ExtensionRegistrationService;
import com.lynxus.channel.gateway.extension.RuntimeChannelProviderRegistry;
import com.lynxus.channel.gateway.shared.ConflictException;
import com.lynxus.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileAccountSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelProfileInternalRequest;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelProfileInternalRequest;
import com.lynxus.extension.sdk.common.LynxusCanonicalJson;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHeaders;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChannelAdminServiceTest {
    private static EmbeddedPostgresTestDatabase database;

    private ChannelAdminService service;

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
        service = new ChannelAdminService(
            new ChannelAdminRepository(database.dsl(), new ObjectMapper()),
            registry()
        );
    }

    @Test
    void shouldPersistMaterializedAccountSnapshotAndRevision() {
        ChannelGatewayProfile created = service.createProfile(new CreateChannelProfileInternalRequest(
            "feishu",
            "飞书客服机器人",
            ChannelProfileStatus.ACTIVE,
            true,
            Map.of("appId", "cli_xxx"),
            new ChannelAssistantBinding("assistant-1", null),
            new ChannelProfileAccountSnapshot("integration-account-1", "vault://opaque-ref")
        ));

        assertEquals(1, created.revision());
        assertEquals("integration-account-1", created.accountId());
        assertTrue(created.hasExternalSecretRef());

        ChannelGatewayProfile updated = service.updateProfile(created.id(), new UpdateChannelProfileInternalRequest(
            "feishu",
            "飞书客服机器人 Updated",
            ChannelProfileStatus.INACTIVE,
            false,
            Map.of("appId", "cli_xxx"),
            new ChannelAssistantBinding("assistant-2", "scenario-1"),
            new ChannelProfileAccountSnapshot("integration-account-2", null),
            1L
        ));

        assertEquals(2, updated.revision());
        assertEquals("飞书客服机器人 Updated", updated.displayName());
        assertEquals("integration-account-2", updated.accountId());
        assertEquals(false, updated.hasExternalSecretRef());
        assertEquals("assistant-2", updated.assistantBinding().assistantId());
    }

    @Test
    void shouldRejectStaleProfileRevision() {
        ChannelGatewayProfile created = service.createProfile(new CreateChannelProfileInternalRequest(
            "feishu",
            "飞书客服机器人",
            null,
            true,
            Map.of("appId", "cli_xxx"),
            null,
            null
        ));

        service.updateProfile(created.id(), new UpdateChannelProfileInternalRequest(
            "feishu",
            "飞书客服机器人 Updated",
            null,
            true,
            Map.of("appId", "cli_xxx"),
            null,
            null,
            1L
        ));

        assertThrows(ConflictException.class, () -> service.updateProfile(created.id(), new UpdateChannelProfileInternalRequest(
            "feishu",
            "飞书客服机器人 Stale",
            null,
            true,
            Map.of("appId", "cli_xxx"),
            null,
            null,
            1L
        )));
    }

    @Test
    void shouldRejectUnknownProviderType() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.createProfile(
            new CreateChannelProfileInternalRequest(
                "unknown-provider",
                "Unknown Provider",
                null,
                true,
                Map.of(),
                null,
                null
            )
        ));

        assertEquals("unknown channelProfile.providerType: unknown-provider", error.getMessage());
    }

    @Test
    void internalProfileWriteRequestsPreserveNullConfig() {
        CreateChannelProfileInternalRequest createRequest = new CreateChannelProfileInternalRequest(
            "enterprise.acme.configured",
            "Acme Provider",
            null,
            true,
            null,
            null,
            null
        );
        UpdateChannelProfileInternalRequest updateRequest = new UpdateChannelProfileInternalRequest(
            "enterprise.acme.configured",
            "Acme Provider",
            null,
            true,
            null,
            null,
            null,
            1L
        );

        assertNull(createRequest.config());
        assertNull(updateRequest.config());
    }

    @Test
    void shouldMaterializeDefaultConfigWhenConfigIsOmitted() {
        ChannelGatewayProfile created = service.createProfile(new CreateChannelProfileInternalRequest(
            "enterprise.acme.configured",
            "Acme Provider",
            null,
            true,
            null,
            null,
            null
        ));

        assertEquals(Map.of("tenant", "acme", "region", "cn"), created.config());
        assertEquals(Map.of("tenant", "acme", "region", "cn"), service.getProfile(created.id()).config());
    }

    @Test
    void shouldRejectExplicitEmptyConfigAgainstRequiredProviderConfigSchema() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.createProfile(
            new CreateChannelProfileInternalRequest(
                "enterprise.acme.configured",
                "Acme Provider",
                null,
                true,
                Map.of(),
                null,
                null
            )
        ));

        assertEquals("channel profile config does not satisfy provider configSchema", error.getMessage());
    }

    @Test
    void shouldRejectInvalidConfigAgainstProviderConfigSchema() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.createProfile(
            new CreateChannelProfileInternalRequest(
                "enterprise.acme.configured",
                "Acme Provider",
                null,
                true,
                Map.of("tenant", 123, "region", "cn"),
                null,
                null
            )
        ));

        assertEquals("channel profile config does not satisfy provider configSchema", error.getMessage());
    }

    @Test
    void shouldPersistValidConfigAndValidateUpdates() {
        ChannelGatewayProfile created = service.createProfile(new CreateChannelProfileInternalRequest(
            "enterprise.acme.configured",
            "Acme Provider",
            null,
            true,
            Map.of("tenant", "custom", "region", "us"),
            null,
            null
        ));

        assertEquals(Map.of("tenant", "custom", "region", "us"), created.config());
        assertEquals(Map.of("tenant", "custom", "region", "us"), service.getProfile(created.id()).config());

        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(created.id(), new UpdateChannelProfileInternalRequest(
            "enterprise.acme.configured",
            "Acme Provider",
            null,
            true,
            Map.of("tenant", "custom", "region", 5),
            null,
            null,
            1L
        )));

        ChannelGatewayProfile updated = service.updateProfile(created.id(), new UpdateChannelProfileInternalRequest(
            "enterprise.acme.configured",
            "Acme Provider",
            null,
            true,
            null,
            null,
            null,
            1L
        ));

        assertEquals(Map.of("tenant", "acme", "region", "cn"), updated.config());
    }

    private static RuntimeChannelProviderRegistry registry() {
        CapturingFetcher fetcher = new CapturingFetcher();
        fetcher.responses.put("acme-channel-provider", manifest(channelDescriptor(
            "enterprise.acme.configured",
            objectSchema(
                Map.of(
                    "tenant", Map.of("type", "string"),
                    "region", Map.of("type", "string")
                ),
                List.of("tenant", "region")
            ),
            Map.of("tenant", "acme", "region", "cn")
        )));
        return new RuntimeChannelProviderRegistry(new ChannelProviderRegistryLoader(
            registrationServiceFromYaml("""
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
                """),
            new ChannelGatewayDescriptorProvider(),
            fetcher,
            "internal-token"
        ));
    }

    private static ExtensionRegistrationService registrationServiceFromYaml(String operatorYaml) {
        try {
            Path tempFile = Files.createTempFile("lynxus-extension-registration", ".yaml");
            Files.writeString(tempFile, operatorYaml);
            return new ExtensionRegistrationService(new ExtensionRegistrationProperties(
                tempFile.toString(),
                "http://channel-gateway.example.com",
                "http://agent-runtime.example.com"
            ));
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

        @Override
        public String fetch(String manifestUrl, Map<String, String> headers) throws IOException {
            String registrationId = headers.get(LynxusExtensionHeaders.REGISTRATION_ID);
            if (!responses.containsKey(registrationId)) {
                throw new AssertionError("unexpected manifest fetch for " + registrationId);
            }
            return responses.get(registrationId);
        }
    }
}
