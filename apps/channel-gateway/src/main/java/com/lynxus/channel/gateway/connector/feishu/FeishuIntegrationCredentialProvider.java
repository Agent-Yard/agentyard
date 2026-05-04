package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.contracts.http.HttpUrls;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
final class FeishuIntegrationCredentialProvider implements FeishuCredentialProvider {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;
    private final String internalAuthToken;
    private final HttpClient httpClient;

    @Autowired
    FeishuIntegrationCredentialProvider(
        ObjectMapper objectMapper,
        @Value("${lynxus.api.base-url}") String apiBaseUrl,
        @Qualifier("internalAuthToken") String internalAuthToken
    ) {
        this(objectMapper, apiBaseUrl, internalAuthToken, HttpClient.newHttpClient());
    }

    FeishuIntegrationCredentialProvider(
        ObjectMapper objectMapper,
        String apiBaseUrl,
        String internalAuthToken,
        HttpClient httpClient
    ) {
        this.objectMapper = objectMapper;
        this.apiBaseUrl = requireText(apiBaseUrl, "lynxus.api.base-url");
        this.internalAuthToken = requireText(internalAuthToken, "lynxus.internal-auth.token");
        this.httpClient = httpClient;
    }

    @Override
    public FeishuAppCredential resolve(String accountId, Map<String, Object> profileConfig) {
        String normalizedAccountId = requireText(accountId, "channel profile integration account");
        RuntimeCredential credential = fetchRuntimeCredential(normalizedAccountId);
        if (!"CHANNEL_PROVIDER".equals(readString(credential.data().get("subjectType")))) {
            throw new IllegalStateException("integration account subjectType is not CHANNEL_PROVIDER: " + normalizedAccountId);
        }
        if (!FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE.equals(readString(credential.data().get("subjectId")))) {
            throw new IllegalStateException("integration account subjectId is not feishu: " + normalizedAccountId);
        }
        if (!"ENABLED".equals(readString(credential.data().get("status")))) {
            throw new IllegalStateException("integration account is not ENABLED: " + normalizedAccountId);
        }

        Map<String, Object> accountConfig = objectValue(credential.data().get("config"));
        Map<String, Object> credentialObject = objectValue(credential.data().get("credential"));
        String appId = firstNonBlank(
            readString(accountConfig.get("appId")),
            readString(credentialObject.get("appId")),
            readString(credentialObject.get("app_id")),
            readString(profileConfig == null ? null : profileConfig.get("appId"))
        );
        String appSecret = firstNonBlank(
            readString(credentialObject.get("appSecret")),
            readString(credentialObject.get("app_secret"))
        );
        return new FeishuAppCredential(normalizedAccountId, appId, appSecret);
    }

    private RuntimeCredential fetchRuntimeCredential(String accountId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(HttpUrls.join(apiBaseUrl, runtimeCredentialPath(accountId)))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .header("Authorization", "Bearer " + internalAuthToken)
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new IllegalStateException("integration account credential lookup failed with HTTP " + response.statusCode());
            }
            Map<String, Object> envelope = objectMapper.readValue(response.body(), OBJECT_MAP);
            if (!Boolean.TRUE.equals(envelope.get("success"))) {
                throw new IllegalStateException("integration account credential lookup returned unsuccessful response");
            }
            Map<String, Object> data = objectValue(envelope.get("data"));
            if (data.isEmpty()) {
                throw new IllegalStateException("integration account credential response data is empty");
            }
            return new RuntimeCredential(data);
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to load feishu integration account credential", error);
        }
    }

    private static String runtimeCredentialPath(String accountId) {
        String encoded = URLEncoder.encode(accountId, StandardCharsets.UTF_8);
        return "/internal/integration/accounts/" + encoded + "/credential";
    }

    private static Map<String, Object> objectValue(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return Map.copyOf(result);
    }

    private static String readString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private record RuntimeCredential(Map<String, Object> data) {
    }
}
