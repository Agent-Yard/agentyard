package com.agentyard.platform.extension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ExtensionDefinitionContractTest {
    private static final Path CONTROL_PLANE_OPENAPI = Path.of("packages/contracts/openapi/control-plane.yaml");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Set<String> FORBIDDEN_DEFINITION_FIELDS = Set.of(
        "accountRequirement",
        "endpoint",
        "endpoints",
        "baseUrl",
        "auth",
        "externalSecretRef"
    );

    @Test
    @SuppressWarnings("unchecked")
    void controlPlaneContractDefinesDefinitionEndpointsWithoutSensitiveFields() throws IOException {
        Path contractPath = repoRoot().resolve(CONTROL_PLANE_OPENAPI);
        Map<String, Object> document = YAML.readValue(
            Files.readString(contractPath),
            new TypeReference<>() {}
        );
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        Map<String, Object> components = (Map<String, Object>) document.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");

        assertNotNull(paths.get("/extensions/channel-providers"));
        assertNotNull(paths.get("/extensions/tool-connectors"));
        assertNotNull(schemas.get("ApiResponseChannelProviderDefinitionList"));
        assertNotNull(schemas.get("ApiResponseToolConnectorDefinitionList"));
        assertNotNull(schemas.get("ChannelProviderDefinition"));
        assertNotNull(schemas.get("ToolConnectorDefinition"));
        assertNotNull(schemas.get("CredentialCapability"));

        assertSchemaDoesNotExposeForbiddenFields((Map<String, Object>) schemas.get("ChannelProviderDefinition"));
        assertSchemaDoesNotExposeForbiddenFields((Map<String, Object>) schemas.get("ToolConnectorDefinition"));

        String channelDefinition = schemas.get("ChannelProviderDefinition").toString();
        String toolDefinition = schemas.get("ToolConnectorDefinition").toString();
        assertTrue(channelDefinition.contains("definitionDigest"));
        assertTrue(toolDefinition.contains("definitionDigest"));
        assertFalse(channelDefinition.contains("accountRequirement"));
        assertFalse(toolDefinition.contains("accountRequirement"));
    }

    @SuppressWarnings("unchecked")
    private static void assertSchemaDoesNotExposeForbiddenFields(Map<String, Object> schema) {
        Object rawProperties = schema.get("properties");
        assertTrue(rawProperties instanceof Map<?, ?>);
        Map<String, Object> properties = (Map<String, Object>) rawProperties;
        for (String forbidden : FORBIDDEN_DEFINITION_FIELDS) {
            assertFalse(properties.containsKey(forbidden), forbidden);
        }
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(CONTROL_PLANE_OPENAPI))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("unable to locate repository root from " + Path.of("").toAbsolutePath());
    }
}
