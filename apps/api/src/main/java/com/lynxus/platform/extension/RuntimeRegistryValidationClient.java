package com.lynxus.platform.extension;

import com.lynxus.contracts.http.HttpUrls;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHeaders;
import com.lynxus.platform.extension.ExtensionRegistryValidation.RuntimeRegistryValidation;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

public interface RuntimeRegistryValidationClient {
    RuntimeRegistryValidationResult agentRuntimeValidation();

    RuntimeRegistryValidationResult channelGatewayValidation();
}

@Component
final class JdkRuntimeRegistryValidationClient implements RuntimeRegistryValidationClient {
    private static final Duration VALIDATION_TIMEOUT = Duration.ofSeconds(5);
    private static final String TOOL_CONNECTOR_VALIDATION_PATH = "/internal/extension-registry/tool-connectors/validation";
    private static final String CHANNEL_PROVIDER_VALIDATION_PATH = "/internal/extension-registry/channel-providers/validation";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String agentRuntimeBaseUrl;
    private final String channelGatewayBaseUrl;
    private final String authorization;

    @Autowired
    JdkRuntimeRegistryValidationClient(
        @Value("${lynxus.agent-runtime.base-url}") String agentRuntimeBaseUrl,
        @Value("${lynxus.channel-gateway.base-url}") String channelGatewayBaseUrl,
        @Value("${lynxus.internal-auth.token}") String internalAuthToken,
        ObjectMapper objectMapper
    ) {
        this(
            HttpClient.newBuilder().connectTimeout(VALIDATION_TIMEOUT).build(),
            objectMapper,
            agentRuntimeBaseUrl,
            channelGatewayBaseUrl,
            internalAuthToken
        );
    }

    JdkRuntimeRegistryValidationClient(
        HttpClient httpClient,
        ObjectMapper objectMapper,
        String agentRuntimeBaseUrl,
        String channelGatewayBaseUrl,
        String internalAuthToken
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.agentRuntimeBaseUrl = requireBaseUrl(agentRuntimeBaseUrl, "agentRuntimeBaseUrl");
        this.channelGatewayBaseUrl = requireBaseUrl(channelGatewayBaseUrl, "channelGatewayBaseUrl");
        this.authorization = "Bearer " + requireInternalAuthToken(internalAuthToken);
    }

    @Override
    public RuntimeRegistryValidationResult agentRuntimeValidation() {
        return fetch("agent-runtime", "TOOL_CONNECTOR", agentRuntimeBaseUrl, TOOL_CONNECTOR_VALIDATION_PATH);
    }

    @Override
    public RuntimeRegistryValidationResult channelGatewayValidation() {
        return fetch("channel-gateway", "CHANNEL_PROVIDER", channelGatewayBaseUrl, CHANNEL_PROVIDER_VALIDATION_PATH);
    }

    private RuntimeRegistryValidationResult fetch(
        String runtimeService,
        String registryType,
        String baseUrl,
        String path
    ) {
        HttpRequest request = HttpRequest.newBuilder(validationUri(baseUrl, path))
            .GET()
            .timeout(VALIDATION_TIMEOUT)
            .header(LynxusExtensionHeaders.AUTHORIZATION, authorization)
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return RuntimeRegistryValidationResult.failure(
                    runtimeService,
                    "non-2xx response",
                    response.statusCode()
                );
            }
            RuntimeRegistryValidation validation = objectMapper.readValue(
                response.body(),
                RuntimeRegistryValidation.class
            );
            if (!validRuntimeValidationShape(validation, runtimeService, registryType)) {
                return RuntimeRegistryValidationResult.failure(runtimeService, "malformed response", null);
            }
            if (!"READY".equals(validation.status())) {
                return RuntimeRegistryValidationResult.failure(runtimeService, "runtime registry not ready", null);
            }
            return RuntimeRegistryValidationResult.ready(runtimeService, validation);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return RuntimeRegistryValidationResult.failure(runtimeService, "request interrupted", null);
        } catch (IOException exception) {
            return RuntimeRegistryValidationResult.failure(runtimeService, failureReason(exception), null);
        } catch (RuntimeException exception) {
            return RuntimeRegistryValidationResult.failure(runtimeService, "malformed response", null);
        }
    }

    private static boolean validRuntimeValidationShape(
        RuntimeRegistryValidation validation,
        String runtimeService,
        String registryType
    ) {
        return validation != null
            && ("READY".equals(validation.status()) || "NOT_READY".equals(validation.status()))
            && runtimeService.equals(validation.service())
            && "EXTENSION_REGISTRY".equals(validation.component())
            && registryType.equals(validation.registryType())
            && validation.registrationConfigDigest() != null
            && !validation.registrationConfigDigest().isBlank()
            && validation.loadedDescriptorIds() != null
            && validation.descriptorDefinitionDigests() != null
            && validation.errors() != null;
    }

    private static URI validationUri(String baseUrl, String path) {
        return HttpUrls.join(baseUrl, path);
    }

    private static String failureReason(IOException exception) {
        if (exception instanceof java.net.http.HttpTimeoutException) {
            return "timeout";
        }
        if (exception instanceof ConnectException) {
            return "request failure";
        }
        return "request failure";
    }

    private static String requireBaseUrl(String baseUrl, String name) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(name + " must be configured");
        }
        return baseUrl.trim();
    }

    private static String requireInternalAuthToken(String internalAuthToken) {
        if (internalAuthToken == null || internalAuthToken.isBlank()) {
            throw new IllegalStateException("lynxus.internal-auth.token must be configured");
        }
        return internalAuthToken.trim();
    }
}

record RuntimeRegistryValidationResult(
    boolean ready,
    String runtimeService,
    ExtensionRegistryValidation.RuntimeRegistryValidation validation,
    String failureReason,
    Integer httpStatus
) {
    static RuntimeRegistryValidationResult ready(String runtimeService, RuntimeRegistryValidation validation) {
        return new RuntimeRegistryValidationResult(true, runtimeService, validation, null, null);
    }

    static RuntimeRegistryValidationResult failure(String runtimeService, String failureReason, Integer httpStatus) {
        return new RuntimeRegistryValidationResult(false, runtimeService, null, failureReason, httpStatus);
    }
}
