package com.agentyard.extension.sdk.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ExtensionProtocolContractTest {
    private static final Path REPO_ROOT = Path.of(System.getProperty("agentyard.repo.root"));
    private static final Path OPENAPI_PATH = REPO_ROOT.resolve(
        "packages/extension-protocol/openapi/extension-boundary.openapi.json"
    );
    private static final Path EXTENSION_ERROR_SCHEMA_PATH = REPO_ROOT.resolve(
        "packages/extension-protocol/json-schema/extension-error.schema.json"
    );
    private static final Path EXTENSION_ERROR_EXAMPLE_PATH = REPO_ROOT.resolve(
        "packages/extension-protocol/examples/extension-error.remote-auth-failed.json"
    );

    @Test
    void pathConstantsMatchOpenApiPathKeys() throws IOException {
        Map<String, Object> openApi = JsonDocuments.parseObject(Files.readString(OPENAPI_PATH));
        Set<String> openApiPaths = object(openApi.get("paths")).keySet();

        Set<String> fixedPathConstants = Set.of(
            AgentYardExtensionProtocol.EXTENSION_MANIFEST_PATH,
            AgentYardExtensionProtocol.EXTENSION_HEALTH_PATH,
            AgentYardExtensionProtocol.HEALTH_LIVE_PATH,
            AgentYardExtensionProtocol.HEALTH_READY_PATH,
            AgentYardExtensionProtocol.CHANNEL_OUTBOUND_FRAME_SUBSCRIPTIONS_PATH,
            AgentYardExtensionProtocol.CHANNEL_OUTBOUND_FRAMES_STREAM_PATH,
            AgentYardExtensionProtocol.CHANNEL_OUTBOUND_FRAMES_ACK_PATH
        );

        assertEquals(
            Set.of(
                "/extension/manifest",
                "/extension/health",
                "/health/live",
                "/health/ready",
                "/extension/channel/outbound-frame-subscriptions",
                "/extension/channel/outbound-frames/stream",
                "/extension/channel/outbound-frames/ack"
            ),
            fixedPathConstants
        );
        assertTrue(openApiPaths.containsAll(fixedPathConstants));
    }

    @Test
    void endpointKeyConstantsMatchJsonSchemaEndpointProperties() throws IOException {
        Map<String, Object> toolDescriptorSchema = JsonDocuments.parseObject(
            Files.readString(REPO_ROOT.resolve("packages/extension-protocol/json-schema/tool-connector-descriptor.schema.json"))
        );
        Map<String, Object> channelDescriptorSchema = JsonDocuments.parseObject(
            Files.readString(REPO_ROOT.resolve("packages/extension-protocol/json-schema/channel-provider-descriptor.schema.json"))
        );

        Set<String> credentialEndpointConstants = Set.of(
            AgentYardExtensionProtocol.CREATE_CREDENTIAL_ENDPOINT,
            AgentYardExtensionProtocol.ROTATE_CREDENTIAL_ENDPOINT,
            AgentYardExtensionProtocol.REVOKE_CREDENTIAL_ENDPOINT,
            AgentYardExtensionProtocol.VALIDATE_CREDENTIAL_ENDPOINT
        );
        Set<String> toolEndpointConstants = Set.of(AgentYardExtensionProtocol.TOOL_CONNECTOR_INVOKE_ENDPOINT);
        Set<String> channelEndpointConstants = Set.of(AgentYardExtensionProtocol.CHANNEL_PROVIDER_RUN_JOB_ENDPOINT);

        assertEquals(Set.of("createCredential", "rotateCredential", "revokeCredential", "validateCredential"), credentialEndpointConstants);
        assertEquals(Set.of("invoke"), toolEndpointConstants);
        assertEquals(Set.of("runJob"), channelEndpointConstants);
        assertEquals(toolEndpointConstants, endpointPropertyKeys(toolDescriptorSchema));
        assertEquals(channelEndpointConstants, endpointPropertyKeys(channelDescriptorSchema));
    }

    @Test
    void headerConstantsMatchOpenApiOperationGroups() throws IOException {
        Map<String, Object> openApi = JsonDocuments.parseObject(Files.readString(OPENAPI_PATH));

        assertEquals(
            AgentYardExtensionHeaders.SERVICE_LEVEL_HEADERS,
            operationHeaderNames(openApi, "getExtensionManifest")
        );
        assertEquals(
            AgentYardExtensionHeaders.SERVICE_LEVEL_HEADERS,
            operationHeaderNames(openApi, "getExtensionHealth")
        );
        assertEquals(Set.of(), operationHeaderNames(openApi, "getHealthLive"));
        assertEquals(Set.of(), operationHeaderNames(openApi, "getHealthReady"));

        for (String operationId : List.of(
            "invokeToolConnector",
            "runChannelProviderJob",
            "ingestNormalizedChannelEvent",
            "ingestNormalizedChannelTurn"
        )) {
            assertEquals(
                AgentYardExtensionHeaders.DESCRIPTOR_LEVEL_REQUIRED_HEADERS,
                operationHeaderNames(openApi, operationId),
                operationId
            );
        }

        for (String operationId : List.of(
            "createCredential",
            "rotateCredential",
            "revokeCredential",
            "validateCredential"
        )) {
            assertEquals(
                AgentYardExtensionHeaders.CREDENTIAL_LIFECYCLE_REQUIRED_HEADERS,
                operationHeaderNames(openApi, operationId),
                operationId
            );
        }
    }

    @Test
    void httpHelperHeadersMatchOpenApiOperationGroups() throws IOException {
        Map<String, Object> openApi = JsonDocuments.parseObject(Files.readString(OPENAPI_PATH));

        Map<String, String> serviceHeaders = AgentYardExtensionHttp.serviceLevelHeaders(
            "Bearer service-token",
            "registration-1"
        );
        assertEquals(operationHeaderNames(openApi, "getExtensionManifest"), serviceHeaders.keySet());
        assertEquals("Bearer service-token", serviceHeaders.get(AgentYardExtensionHeaders.AUTHORIZATION));
        assertEquals("registration-1", serviceHeaders.get(AgentYardExtensionHeaders.REGISTRATION_ID));

        Map<String, String> serviceHeadersWithoutRegistration = AgentYardExtensionHttp.serviceLevelHeaders(
            "Bearer service-token",
            null
        );
        assertEquals(AgentYardExtensionHeaders.SERVICE_LEVEL_REQUIRED_HEADERS, serviceHeadersWithoutRegistration.keySet());

        Map<String, String> descriptorHeaders = AgentYardExtensionHttp.descriptorLevelHeaders(
            "Bearer descriptor-token",
            "registration-1",
            DescriptorType.TOOL_CONNECTOR,
            "enterprise.acme.crm",
            "trace-1",
            "request-1",
            "idempotency-1"
        );
        assertEquals(operationHeaderNames(openApi, "invokeToolConnector"), descriptorHeaders.keySet());
        assertEquals("TOOL_CONNECTOR", descriptorHeaders.get(AgentYardExtensionHeaders.DESCRIPTOR_TYPE));
        assertEquals("enterprise.acme.crm", descriptorHeaders.get(AgentYardExtensionHeaders.DESCRIPTOR_ID));

        Map<String, String> credentialHeaders = AgentYardExtensionHttp.credentialLifecycleHeaders(
            "Bearer credential-token",
            "trace-1",
            "request-1"
        );
        assertEquals(operationHeaderNames(openApi, "createCredential"), credentialHeaders.keySet());
        assertTrue(!credentialHeaders.containsKey(AgentYardExtensionHeaders.IDEMPOTENCY_KEY));
        assertTrue(!credentialHeaders.containsKey(AgentYardExtensionHeaders.REGISTRATION_ID));
        assertTrue(!credentialHeaders.containsKey(AgentYardExtensionHeaders.DESCRIPTOR_TYPE));
        assertTrue(!credentialHeaders.containsKey(AgentYardExtensionHeaders.DESCRIPTOR_ID));
    }

    @Test
    void httpHelperBuildsManifestUrlFromProtocolPathConstant() {
        assertEquals(
            "https://extension.example.com/extension/manifest",
            AgentYardExtensionHttp.manifestUrl("https://extension.example.com")
        );
        assertEquals(
            "https://extension.example.com/tenant-a/extension/manifest",
            AgentYardExtensionHttp.manifestUrl("https://extension.example.com/tenant-a/")
        );
        assertTrue(AgentYardExtensionHttp.manifestUrl("https://extension.example.com").endsWith(
            AgentYardExtensionProtocol.EXTENSION_MANIFEST_PATH
        ));
        assertThrows(IllegalArgumentException.class, () -> AgentYardExtensionHttp.manifestUrl(" "));
    }

    @Test
    void httpHelperParsesNon2xxExtensionErrorBodies() throws IOException {
        ExtensionError error = AgentYardExtensionHttp.parseNon2xxExtensionError(
            401,
            Files.readString(EXTENSION_ERROR_EXAMPLE_PATH)
        );

        assertEquals("REMOTE_AUTH_FAILED", error.errorCode());
        assertEquals(ExtensionErrorCategory.AUTH, error.category());
        assertEquals(false, error.retryable());

        assertThrows(
            ExtensionErrorParseException.class,
            () -> AgentYardExtensionHttp.parseNon2xxExtensionError(200, Files.readString(EXTENSION_ERROR_EXAMPLE_PATH))
        );
    }

    @Test
    void openApiDefaultErrorResponsesUseExtensionErrorSchema() throws IOException {
        Map<String, Object> openApi = JsonDocuments.parseObject(Files.readString(OPENAPI_PATH));

        for (String operationId : List.of(
            "getExtensionManifest",
            "getExtensionHealth",
            "invokeToolConnector",
            "runChannelProviderJob",
            "createCredential",
            "rotateCredential",
            "revokeCredential",
            "validateCredential",
            "ingestNormalizedChannelEvent",
            "ingestNormalizedChannelTurn"
        )) {
            assertEquals("#/components/responses/ExtensionErrorResponse", defaultResponseRef(openApi, operationId), operationId);
        }

        Map<String, Object> extensionErrorResponse = object(
            object(object(openApi.get("components")).get("responses")).get("ExtensionErrorResponse")
        );
        Map<String, Object> applicationJson = object(
            object(object(extensionErrorResponse.get("content")).get("application/json")).get("schema")
        );
        assertEquals("#/components/schemas/ExtensionError", applicationJson.get("$ref"));
    }

    @Test
    void facadeEnumsMatchOpenApiAndJsonSchemaEnums() throws IOException {
        Map<String, Object> openApi = JsonDocuments.parseObject(Files.readString(OPENAPI_PATH));
        Map<String, Object> descriptorTypeHeader = componentParameter(openApi, "DescriptorTypeHeader");
        assertEquals(
            enumValues(DescriptorType.values()),
            stringList(object(descriptorTypeHeader.get("schema")).get("enum"))
        );

        Map<String, Object> openApiExtensionError = componentSchema(openApi, "ExtensionError");
        List<String> openApiCategories = stringList(
            object(object(openApiExtensionError.get("properties")).get("category")).get("enum")
        );

        Map<String, Object> extensionErrorSchema = JsonDocuments.parseObject(Files.readString(EXTENSION_ERROR_SCHEMA_PATH));
        List<String> jsonSchemaCategories = stringList(
            object(object(extensionErrorSchema.get("properties")).get("category")).get("enum")
        );

        List<String> facadeCategories = enumValues(ExtensionErrorCategory.values());
        assertEquals(facadeCategories, openApiCategories);
        assertEquals(facadeCategories, jsonSchemaCategories);
    }

    @Test
    void parsesExtensionErrorExampleAndValidatesRequiredShape() throws IOException {
        ExtensionError error = ExtensionError.parseJson(Files.readString(EXTENSION_ERROR_EXAMPLE_PATH));

        assertEquals("REMOTE_AUTH_FAILED", error.errorCode());
        assertEquals("credential expired", error.message());
        assertEquals(ExtensionErrorCategory.AUTH, error.category());
        assertEquals(false, error.retryable());
        assertTrue(error.details().isEmpty());

        ExtensionError circuitOpen = ExtensionError.parseJson(
            """
            {
              "errorCode": "CIRCUIT_OPEN",
              "message": "circuit breaker is open",
              "category": "CIRCUIT_OPEN",
              "retryable": true,
              "details": {"breaker": "remote-tool"}
            }
            """
        );
        assertEquals(ExtensionErrorCategory.CIRCUIT_OPEN, circuitOpen.category());
        assertEquals(true, circuitOpen.retryable());
        assertEquals("remote-tool", circuitOpen.details().get("breaker"));

        assertThrows(
            ExtensionErrorParseException.class,
            () ->
                ExtensionError.parseJson(
                    """
                    {"errorCode":"REMOTE_AUTH_FAILED","message":"credential expired","category":"AUTH","retryable":false}
                    """
                )
        );
        assertThrows(
            ExtensionErrorParseException.class,
            () ->
                ExtensionError.parseJson(
                    """
                    {"errorCode":"REMOTE_AUTH_FAILED","message":"credential expired","category":"NOT_A_CATEGORY","retryable":false,"details":{}}
                    """
                )
        );
        assertThrows(
            ExtensionErrorParseException.class,
            () ->
                ExtensionError.parseJson(
                    """
                    {"errorCode":"REMOTE_AUTH_FAILED","message":"credential expired","category":"AUTH","retryable":"false","details":{}}
                    """
                )
        );
        assertThrows(
            ExtensionErrorParseException.class,
            () ->
                ExtensionError.parseJson(
                    """
                    {"errorCode":"REMOTE_AUTH_FAILED","message":"credential expired","category":"AUTH","retryable":false,"details":[]}
                    """
                )
        );
    }

    private static Set<String> operationHeaderNames(Map<String, Object> openApi, String operationId) {
        for (Object pathItem : object(openApi.get("paths")).values()) {
            for (Object operationCandidate : object(pathItem).values()) {
                Map<String, Object> operation = object(operationCandidate);
                if (!operationId.equals(operation.get("operationId"))) {
                    continue;
                }
                Set<String> names = new LinkedHashSet<>();
                for (Object parameter : list(operation.getOrDefault("parameters", List.of()))) {
                    names.add(parameterName(openApi, object(parameter)));
                }
                return names;
            }
        }
        throw new AssertionError("OpenAPI operation not found: " + operationId);
    }

    private static String defaultResponseRef(Map<String, Object> openApi, String operationId) {
        for (Object pathItem : object(openApi.get("paths")).values()) {
            for (Object operationCandidate : object(pathItem).values()) {
                Map<String, Object> operation = object(operationCandidate);
                if (!operationId.equals(operation.get("operationId"))) {
                    continue;
                }
                return (String) object(object(operation.get("responses")).get("default")).get("$ref");
            }
        }
        throw new AssertionError("OpenAPI operation not found: " + operationId);
    }

    private static String parameterName(Map<String, Object> openApi, Map<String, Object> parameter) {
        String ref = (String) parameter.get("$ref");
        if (ref != null) {
            String key = ref.substring(ref.lastIndexOf('/') + 1);
            return (String) componentParameter(openApi, key).get("name");
        }
        return (String) parameter.get("name");
    }

    private static Map<String, Object> componentParameter(Map<String, Object> openApi, String key) {
        return object(object(object(openApi.get("components")).get("parameters")).get(key));
    }

    private static Map<String, Object> componentSchema(Map<String, Object> openApi, String key) {
        return object(object(object(openApi.get("components")).get("schemas")).get(key));
    }

    private static Set<String> endpointPropertyKeys(Map<String, Object> descriptorSchema) {
        return object(object(object(descriptorSchema.get("properties")).get("endpoints")).get("properties")).keySet();
    }

    private static <E extends WireEnum> List<String> enumValues(E[] values) {
        return Arrays.stream(values).map(WireEnum::wireValue).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static List<String> stringList(Object value) {
        return list(value).stream().map(String.class::cast).toList();
    }
}
