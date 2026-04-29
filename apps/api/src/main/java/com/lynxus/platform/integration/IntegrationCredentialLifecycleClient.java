package com.lynxus.platform.integration;

import com.lynxus.extension.sdk.protocol.LynxusExtensionHttp;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountCredentialStatus;
import com.lynxus.platform.integration.IntegrationDtos.RemoteCredentialLifecycleRequest;
import com.lynxus.platform.integration.IntegrationDtos.RemoteCredentialLifecycleResponse;
import com.lynxus.platform.shared.ApiProblemException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

public interface IntegrationCredentialLifecycleClient {
    RemoteCredentialLifecycleResponse invoke(
        String baseUrl,
        String path,
        RemoteCredentialLifecycleRequest request,
        String traceId,
        String requestId
    );
}

@Component
final class JdkIntegrationCredentialLifecycleClient implements IntegrationCredentialLifecycleClient {
    private static final Duration CREDENTIAL_LIFECYCLE_TIMEOUT = Duration.ofSeconds(30);
    private static final int EXTERNAL_SECRET_REF_MAX_LENGTH = 512;

    private final ObjectMapper objectMapper;
    private final String internalAuthToken;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(CREDENTIAL_LIFECYCLE_TIMEOUT)
        .build();

    JdkIntegrationCredentialLifecycleClient(
        ObjectMapper objectMapper,
        @Value("${lynxus.internal-auth.token}") String internalAuthToken
    ) {
        this.objectMapper = objectMapper;
        this.internalAuthToken = internalAuthToken == null ? "" : internalAuthToken.trim();
    }

    @Override
    public RemoteCredentialLifecycleResponse invoke(
        String baseUrl,
        String path,
        RemoteCredentialLifecycleRequest request,
        String traceId,
        String requestId
    ) {
        if (internalAuthToken.isBlank()) {
            throw remoteFailure();
        }
        try {
            HttpRequest.Builder httpRequest = HttpRequest.newBuilder(URI.create(joinUrl(baseUrl, path)))
                .timeout(CREDENTIAL_LIFECYCLE_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request.toWireBody())));
            LynxusExtensionHttp.credentialLifecycleHeaders("Bearer " + internalAuthToken, traceId, requestId)
                .forEach(httpRequest::header);
            httpRequest.header("Content-Type", "application/json");

            HttpResponse<String> response = httpClient.send(httpRequest.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                return parseExtensionErrorThenFail(response.statusCode(), response.body());
            }
            return parseSuccessResponse(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw remoteFailure();
        } catch (IOException | RuntimeException error) {
            throw remoteFailure();
        }
    }

    private RemoteCredentialLifecycleResponse parseSuccessResponse(String body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) objectMapper.readValue(body, Object.class);
            String externalSecretRef = raw.get("externalSecretRef") instanceof String ref ? ref : null;
            if (externalSecretRef != null && (externalSecretRef.isBlank() || externalSecretRef.length() > EXTERNAL_SECRET_REF_MAX_LENGTH)) {
                throw remoteFailure();
            }
            Object rawStatus = raw.get("credentialStatus");
            IntegrationAccountCredentialStatus status = rawStatus instanceof String statusText
                ? IntegrationAccountCredentialStatus.valueOf(statusText)
                : null;
            if (status == null) {
                throw remoteFailure();
            }
            return new RemoteCredentialLifecycleResponse(externalSecretRef, status);
        } catch (ApiProblemException error) {
            throw error;
        } catch (RuntimeException error) {
            throw remoteFailure();
        }
    }

    private RemoteCredentialLifecycleResponse parseExtensionErrorThenFail(int statusCode, String body) {
        try {
            LynxusExtensionHttp.parseNon2xxExtensionError(statusCode, body);
        } catch (RuntimeException ignored) {
            // The public control-plane error is intentionally sanitized regardless of remote body shape.
        }
        throw remoteFailure();
    }

    private static String joinUrl(String baseUrl, String path) {
        String cleanBase = requireText(baseUrl, "baseUrl");
        String cleanPath = requireText(path, "credential endpoint path");
        if (!cleanPath.startsWith("/")) {
            cleanPath = "/" + cleanPath;
        }
        if (cleanBase.endsWith("/")) {
            return cleanBase.substring(0, cleanBase.length() - 1) + cleanPath;
        }
        return cleanBase + cleanPath;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private static ApiProblemException remoteFailure() {
        return new ApiProblemException(
            HttpStatus.BAD_GATEWAY,
            "INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED",
            "INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED: credential lifecycle call failed"
        );
    }
}
