package com.agentyard.channel.gateway.connector.feishu;

import com.agentyard.contracts.http.HttpUrls;
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
final class FeishuIntegrationAccountRuntimeClient implements FeishuIntegrationAccountRuntimeProvider {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;
    private final String internalAuthToken;
    private final HttpClient httpClient;

    @Autowired
    FeishuIntegrationAccountRuntimeClient(
        ObjectMapper objectMapper,
        @Value("${agentyard.api.base-url}") String apiBaseUrl,
        @Qualifier("internalAuthToken") String internalAuthToken
    ) {
        this(objectMapper, apiBaseUrl, internalAuthToken, HttpClient.newHttpClient());
    }

    FeishuIntegrationAccountRuntimeClient(
        ObjectMapper objectMapper,
        String apiBaseUrl,
        String internalAuthToken,
        HttpClient httpClient
    ) {
        this.objectMapper = objectMapper;
        this.apiBaseUrl = requireText(apiBaseUrl, "agentyard.api.base-url");
        this.internalAuthToken = requireText(internalAuthToken, "agentyard.internal-auth.token");
        this.httpClient = httpClient;
    }

    @Override
    public FeishuIntegrationAccountRuntime load(String accountId) {
        String normalizedAccountId = requireText(accountId, "channel profile integration account");
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(HttpUrls.join(apiBaseUrl, runtimeCredentialPath(normalizedAccountId)))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .header("Authorization", "Bearer " + internalAuthToken)
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new IllegalStateException("integration account runtime lookup failed with HTTP " + response.statusCode());
            }
            Map<String, Object> envelope = objectMapper.readValue(response.body(), OBJECT_MAP);
            if (!Boolean.TRUE.equals(envelope.get("success"))) {
                throw new IllegalStateException("integration account runtime lookup returned unsuccessful response");
            }
            Map<String, Object> data = objectValue(envelope.get("data"));
            if (data.isEmpty()) {
                throw new IllegalStateException("integration account runtime response data is empty");
            }
            return new FeishuIntegrationAccountRuntime(
                readString(data.get("accountId")),
                readString(data.get("subjectType")),
                readString(data.get("subjectId")),
                readString(data.get("status")),
                objectValue(data.get("config")),
                objectValue(data.get("credential"))
            );
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to load feishu integration account runtime", error);
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
