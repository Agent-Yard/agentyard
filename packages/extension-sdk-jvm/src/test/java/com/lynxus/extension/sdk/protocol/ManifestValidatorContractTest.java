package com.lynxus.extension.sdk.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.extension.sdk.validation.ManifestValidationResult;
import com.lynxus.extension.sdk.validation.ManifestValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class ManifestValidatorContractTest {
    private static final Path REPO_ROOT = Path.of(System.getProperty("lynxus.repo.root"));
    private static final Path SCHEMA_DIR = REPO_ROOT.resolve("packages/extension-protocol/json-schema");
    private static final Path VALID_FIXTURES = REPO_ROOT.resolve(
        "packages/extension-protocol/contract-tests/fixtures/manifest-valid"
    );
    private static final Path INVALID_FIXTURES = REPO_ROOT.resolve(
        "packages/extension-protocol/contract-tests/fixtures/manifest-invalid"
    );

    @ParameterizedTest
    @MethodSource("validManifestFixtures")
    void acceptsSharedValidManifestFixtures(Path fixturePath) throws IOException {
        Map<String, Object> fixture = JsonDocuments.parseObject(Files.readString(fixturePath));

        ManifestValidationResult result = ManifestValidator.validate(
            fixture.get("manifest"),
            SCHEMA_DIR
        );

        assertTrue(result.valid(), () -> fixturePath + " unexpected errors: " + result.errors());
    }

    @ParameterizedTest
    @MethodSource("invalidManifestFixtures")
    void rejectsSharedInvalidManifestFixturesWithExpectedCode(Path fixturePath) throws IOException {
        Map<String, Object> fixture = JsonDocuments.parseObject(Files.readString(fixturePath));
        String expectedErrorCode = (String) fixture.get("expectedErrorCode");

        ManifestValidationResult result = ManifestValidator.validate(
            fixture.get("manifest"),
            SCHEMA_DIR
        );

        assertFalse(result.valid(), () -> fixturePath + " should be rejected");
        assertTrue(
            result.errors().stream().anyMatch(error -> expectedErrorCode.equals(error.code())),
            () -> fixturePath + " expected " + expectedErrorCode + ", got " + result.errors()
        );
    }

    @ParameterizedTest
    @MethodSource("schemaInvalidManifests")
    void rejectsManifestShapesEnforcedOnlyByJsonSchema(String manifestJson) {
        ManifestValidationResult result = ManifestValidator.validateJson(manifestJson, SCHEMA_DIR);

        assertFalse(result.valid(), () -> manifestJson + " should be rejected");
        assertTrue(
            result.errors().stream().anyMatch(error -> "MANIFEST_SCHEMA_INVALID".equals(error.code())),
            () -> manifestJson + " expected MANIFEST_SCHEMA_INVALID, got " + result.errors()
        );
    }

    @Test
    void defaultValidationUsesBundledProtocolSchemaResources() {
        ManifestValidationResult result = ManifestValidator.validateJson(
            baseValidToolManifest(
                """
                ,
                  "unexpected": true
                """
            )
        );

        assertFalse(result.valid());
        assertTrue(
            result.errors().stream().anyMatch(error -> "MANIFEST_SCHEMA_INVALID".equals(error.code())),
            () -> "expected MANIFEST_SCHEMA_INVALID from bundled schemas, got " + result.errors()
        );
    }

    @Test
    void rejectsEmptyDescriptorEnvelopeThroughSchemaAndSemanticValidation() {
        ManifestValidationResult result = ManifestValidator.validateJson(
            """
            {
              "extensionApiVersion": 1,
              "coreMinVersion": "0.1.0",
              "coreMaxVersion": "0.1.x",
              "descriptors": {
                "channelProviders": [],
                "toolConnectors": []
              }
            }
            """,
            SCHEMA_DIR
        );

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> "MANIFEST_SCHEMA_INVALID".equals(error.code())));
        assertTrue(result.errors().stream().anyMatch(error -> "MANIFEST_EMPTY".equals(error.code())));
    }

    private static List<Path> validManifestFixtures() throws IOException {
        return fixtureFiles(VALID_FIXTURES);
    }

    private static List<Path> invalidManifestFixtures() throws IOException {
        return fixtureFiles(INVALID_FIXTURES);
    }

    private static Stream<Arguments> schemaInvalidManifests() {
        return Stream.of(
            Arguments.of(
                baseValidToolManifest(
                    """
                    ,
                      "unexpected": true
                    """
                )
            ),
            Arguments.of(
                baseValidToolManifest(
                    "",
                    """
                    ,
                          "unexpectedDescriptorField": true
                    """,
                    ""
                )
            ),
            Arguments.of(
                baseValidToolManifest(
                    "",
                    "",
                    """
                    ,
                            "deleteCredential": "/credentials/delete"
                    """
                )
            ),
            Arguments.of(
                baseValidToolManifest("", "", "", "bad connector type", "/tools/invoke")
            ),
            Arguments.of(
                """
                {
                  "extensionApiVersion": 1,
                  "coreMinVersion": "0.1.0",
                  "descriptors": {
                    "channelProviders": [],
                    "toolConnectors": []
                  }
                }
                """
            ),
            Arguments.of(
                baseValidToolManifest("", "", "", "contract-test.tool", "relative/path")
            )
        );
    }

    private static String baseValidToolManifest(String envelopeSuffix) {
        return baseValidToolManifest(envelopeSuffix, "", "");
    }

    private static String baseValidToolManifest(String envelopeSuffix, String descriptorSuffix, String endpointSuffix) {
        return baseValidToolManifest(envelopeSuffix, descriptorSuffix, endpointSuffix, "contract-test.tool", "/tools/invoke");
    }

    private static String baseValidToolManifest(
        String envelopeSuffix,
        String descriptorSuffix,
        String endpointSuffix,
        String connectorType,
        String invokePath
    ) {
        return
            """
            {
              "extensionApiVersion": 1,
              "coreMinVersion": "0.1.0",
              "coreMaxVersion": "0.1.x",
              "descriptors": {
                "channelProviders": [],
                "toolConnectors": [
                  {
                    "connectorType": "%s",
                    "title": "Contract test tool",
                    "accountConfigSchema": {"type": "object", "properties": {}},
                    "accountConfigUiSchema": [],
                    "configSchema": {"type": "object", "properties": {}},
                    "configUiSchema": [],
                    "operationMappingSchema": {"type": "object", "properties": {}},
                    "operationMappingUiSchema": [],
                    "endpoints": {
                      "invoke": "%s"
            """.formatted(connectorType, invokePath) +
            endpointSuffix +
            """
                    }
            """ +
            descriptorSuffix +
            """
                  }
                ]
              }
            """ +
            envelopeSuffix +
            """
            }
            """;
    }

    private static List<Path> fixtureFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }
    }
}
