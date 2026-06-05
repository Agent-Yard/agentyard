package com.agentyard.platform.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class IntegrationAccountContractTest {
    private static final Path CONTROL_PLANE_OPENAPI = Path.of("packages/contracts/openapi/control-plane.yaml");
    private static final Path CONTRACT_TYPES = Path.of("packages/contracts/src/index.ts");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    @SuppressWarnings("unchecked")
    void integrationAccountContractUsesSubjectModelAndRedactsSecretMaterial() throws IOException {
        Path repoRoot = repoRoot();
        Map<String, Object> document = YAML.readValue(
            Files.readString(repoRoot.resolve(CONTROL_PLANE_OPENAPI)),
            new TypeReference<>() {}
        );
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) document.get("components")).get("schemas");
        Map<String, Object> accountProperties = properties((Map<String, Object>) schemas.get("IntegrationAccount"));
        Map<String, Object> createProperties = properties((Map<String, Object>) schemas.get("CreateIntegrationAccountPayload"));
        Map<String, Object> updateProperties = properties((Map<String, Object>) schemas.get("UpdateIntegrationAccountPayload"));
        String contractTypes = Files.readString(repoRoot.resolve(CONTRACT_TYPES));

        assertTrue(accountProperties.containsKey("subjectType"));
        assertTrue(accountProperties.containsKey("subjectId"));
        assertTrue(accountProperties.containsKey("hasExternalSecretRef"));
        assertTrue(accountProperties.containsKey("credentialStatus"));
        assertFalse(accountProperties.containsKey("connectorType"));
        assertFalse(accountProperties.containsKey("externalSecretRef"));
        assertFalse(accountProperties.containsKey("credentialCiphertext"));
        assertFalse(accountProperties.containsKey("credentialFingerprint"));
        assertFalse(accountProperties.containsKey("metadata"));

        assertTrue(createProperties.containsKey("subjectType"));
        assertFalse(createProperties.containsKey("connectorType"));
        assertTrue(createProperties.containsKey("credential"));
        assertFalse(updateProperties.containsKey("credential"));

        assertTrue(contractTypes.contains("IntegrationAccountSubjectType"));
        assertFalse(contractTypes.contains("connectorType: ToolConnectorType;"));
        assertTrue(contractTypes.contains("credential?: Record<string, unknown> | null;"));
        assertTrue(contractTypes.contains("CreateIntegrationAccountCredentialPayload"));
        assertTrue(contractTypes.contains("RotateIntegrationAccountCredentialPayload"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
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
