package com.lynxus.extension.sdk.protocol;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class GeneratedProtocolModelContractTest {
    private static final Path REPO_ROOT = Path.of(System.getProperty("lynxus.repo.root"));
    private static final Path OPENAPI_PATH = REPO_ROOT.resolve(
        "packages/extension-protocol/openapi/extension-boundary.openapi.json"
    );
    private static final Path SDK_ROOT = REPO_ROOT.resolve("packages/extension-sdk-jvm");
    private static final Path GENERATED_OPENAPI_PATH = Path.of(
        System.getProperty(
            "lynxus.extension.generated.java.openapi.path",
            SDK_ROOT.resolve(
                "build/generated/extension-protocol/openapi/extension-boundary.openapi.generator.json"
            ).toString()
        )
    );
    private static final Path GENERATED_ROOT = Path.of(
        System.getProperty(
            "lynxus.extension.generated.java.dir",
            SDK_ROOT.resolve("build/generated/extension-protocol/java").toString()
        )
    );
    private static final Path GENERATED_MODEL_DIR = GENERATED_ROOT.resolve(
        "src/main/java/com/lynxus/extension/sdk/generated/protocol/model"
    );
    private static final Path GENERATED_SUPPORT_DIR = GENERATED_ROOT.resolve(
        "src/main/java/com/lynxus/extension/sdk/generated/protocol"
    );
    private static final Path GENERATED_CLASSES_ROOT = Path.of(
        System.getProperty(
            "lynxus.extension.generated.java.classes.dir",
            SDK_ROOT.resolve("build/classes/java/generated-extension-protocol").toString()
        )
    );
    private static final Path GENERATED_MODEL_CLASSES_DIR = GENERATED_CLASSES_ROOT.resolve(
        "com/lynxus/extension/sdk/generated/protocol/model"
    );
    private static final Path GENERATED_SMOKE_CLASS = GENERATED_CLASSES_ROOT.resolve(
        "com/lynxus/extension/sdk/generated/protocol/smoke/GeneratedProtocolModelCompileSmoke.class"
    );

    @Test
    void openApiGeneratorWritesProtocolModelsUnderGradleBuildDirectory() throws IOException {
        assertTrue(
            GENERATED_ROOT.normalize().startsWith(SDK_ROOT.resolve("build").normalize()),
            () -> "generated Java models must stay under Gradle build directory: " + GENERATED_ROOT
        );

        Map<String, Object> openApi = JsonDocuments.parseObject(Files.readString(OPENAPI_PATH));
        Set<String> sourceSchemas = object(object(openApi.get("components")).get("schemas")).keySet();
        Map<String, Object> sourceAccepted = schemaProperty(openApi, "NormalizedEventAccepted", "accepted");
        Map<String, Object> sourceSupportsFinalDelivery = schemaProperty(
            openApi,
            "ChannelProviderOutboundCapability",
            "supportsFinalDelivery"
        );
        Map<String, Object> sourceRequiresIdempotentFinalDelivery = schemaProperty(
            openApi,
            "ChannelProviderOutboundCapability",
            "requiresIdempotentFinalDelivery"
        );

        assertTrue(
            GENERATED_OPENAPI_PATH.normalize().startsWith(SDK_ROOT.resolve("build").normalize()),
            () -> "generator-only OpenAPI copy must stay under Gradle build directory: " + GENERATED_OPENAPI_PATH
        );
        assertTrue(
            Files.isRegularFile(GENERATED_OPENAPI_PATH),
            () -> "generator-only OpenAPI copy missing: " + GENERATED_OPENAPI_PATH
        );
        Map<String, Object> generatorOpenApi = JsonDocuments.parseObject(Files.readString(GENERATED_OPENAPI_PATH));
        Map<String, Object> generatorAccepted = schemaProperty(generatorOpenApi, "NormalizedEventAccepted", "accepted");
        Map<String, Object> generatorSupportsFinalDelivery = schemaProperty(
            generatorOpenApi,
            "ChannelProviderOutboundCapability",
            "supportsFinalDelivery"
        );
        Map<String, Object> generatorRequiresIdempotentFinalDelivery = schemaProperty(
            generatorOpenApi,
            "ChannelProviderOutboundCapability",
            "requiresIdempotentFinalDelivery"
        );
        assertTrue(
            Boolean.TRUE.equals(sourceAccepted.get("const")),
            "source OpenAPI NormalizedEventAccepted.accepted must keep const: true"
        );
        assertTrue(
            Boolean.TRUE.equals(sourceSupportsFinalDelivery.get("const")),
            "source OpenAPI ChannelProviderOutboundCapability.supportsFinalDelivery must keep const: true"
        );
        assertTrue(
            Boolean.TRUE.equals(sourceRequiresIdempotentFinalDelivery.get("const")),
            "source OpenAPI ChannelProviderOutboundCapability.requiresIdempotentFinalDelivery must keep const: true"
        );
        assertTrue(
            Boolean.TRUE.equals(generatorAccepted.get("default")),
            "generator-only OpenAPI copy should downgrade boolean const to default for OpenAPI Generator Java"
        );
        assertTrue(
            !generatorAccepted.containsKey("const"),
            "generator-only OpenAPI copy should remove boolean const for OpenAPI Generator Java"
        );
        assertTrue(
            Boolean.TRUE.equals(generatorSupportsFinalDelivery.get("default")),
            "generator-only OpenAPI copy should downgrade supportsFinalDelivery boolean const to default"
        );
        assertTrue(
            !generatorSupportsFinalDelivery.containsKey("const"),
            "generator-only OpenAPI copy should remove supportsFinalDelivery boolean const"
        );
        assertTrue(
            Boolean.TRUE.equals(generatorRequiresIdempotentFinalDelivery.get("default")),
            "generator-only OpenAPI copy should downgrade requiresIdempotentFinalDelivery boolean const to default"
        );
        assertTrue(
            !generatorRequiresIdempotentFinalDelivery.containsKey("const"),
            "generator-only OpenAPI copy should remove requiresIdempotentFinalDelivery boolean const"
        );

        assertTrue(
            Files.isRegularFile(GENERATED_SUPPORT_DIR.resolve("JSON.java")),
            "generated Java support class missing for model JSON helpers"
        );
        assertTrue(
            Files.isRegularFile(GENERATED_MODEL_DIR.resolve("AbstractOpenApiSchema.java")),
            "generated Java support class missing for composed models"
        );
        assertTrue(
            Files.isRegularFile(GENERATED_SMOKE_CLASS),
            () -> "generated Java compile smoke class missing: " + GENERATED_SMOKE_CLASS
        );

        for (String expectedSchema : Set.of(
            "ServiceManifestEnvelope",
            "ExtensionError",
            "ChannelProviderDescriptor",
            "ChannelOutboundFrame",
            "ChannelOutboundFrameAck",
            "ToolConnectorDescriptor"
        )) {
            assertTrue(sourceSchemas.contains(expectedSchema), "OpenAPI source schema missing " + expectedSchema);
            assertTrue(
                Files.isRegularFile(GENERATED_MODEL_DIR.resolve(expectedSchema + ".java")),
                () -> "generated Java model missing for " + expectedSchema
            );
            assertTrue(
                Files.isRegularFile(GENERATED_MODEL_CLASSES_DIR.resolve(expectedSchema + ".class")),
                () -> "generated Java model did not compile for " + expectedSchema
            );
        }
    }

    private static Map<String, Object> schemaProperty(
        Map<String, Object> openApi,
        String schemaName,
        String propertyName
    ) {
        Map<String, Object> components = object(openApi.get("components"));
        Map<String, Object> schemas = object(components.get("schemas"));
        Map<String, Object> schema = object(schemas.get(schemaName));
        Map<String, Object> properties = object(schema.get("properties"));
        return object(properties.get(propertyName));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }
}
