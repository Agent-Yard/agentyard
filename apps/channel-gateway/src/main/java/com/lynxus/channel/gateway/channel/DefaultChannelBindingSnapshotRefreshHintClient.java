package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshotRefreshRequest;
import com.lynxus.contracts.http.HttpUrls;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class DefaultChannelBindingSnapshotRefreshHintClient implements ChannelBindingSnapshotRefreshHintClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;
    private final String internalAuthToken;
    private final HttpClient httpClient;

    DefaultChannelBindingSnapshotRefreshHintClient(
        ObjectMapper objectMapper,
        @Value("${lynxus.api.base-url}") String apiBaseUrl,
        @Qualifier("internalAuthToken") String internalAuthToken
    ) {
        this(objectMapper, apiBaseUrl, internalAuthToken, HttpClient.newHttpClient());
    }

    DefaultChannelBindingSnapshotRefreshHintClient(
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
    public void bindingSessionAttached(ChannelConversationBinding binding) {
        ChannelOutboundBindingSnapshotRefreshRequest request = new ChannelOutboundBindingSnapshotRefreshRequest(
            binding.channelProfileId(),
            binding.id(),
            binding.sessionId(),
            "BINDING_SESSION_ATTACHED",
            binding.updatedAt()
        );
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(HttpUrls.join(apiBaseUrl, "/internal/channel-outbound/binding-snapshot/refresh"))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + internalAuthToken)
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new IllegalStateException("binding snapshot refresh hint failed with HTTP " + response.statusCode());
            }
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to send binding snapshot refresh hint", error);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
