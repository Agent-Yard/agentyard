package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.shared.ApiResponse;
import com.lynxus.contracts.http.HttpUrls;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionMessageRequest;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionMessageResponse;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
final class DefaultChannelSessionRuntimeClient implements ChannelSessionRuntimeClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final TypeReference<ApiResponse<ChannelInboundSessionMessageResponse>> RESPONSE_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;
    private final String internalAuthToken;
    private final HttpClient httpClient;

    @Autowired
    DefaultChannelSessionRuntimeClient(
        ObjectMapper objectMapper,
        @Value("${lynxus.api.base-url}") String apiBaseUrl,
        @Qualifier("internalAuthToken") String internalAuthToken
    ) {
        this(objectMapper, apiBaseUrl, internalAuthToken, HttpClient.newHttpClient());
    }

    DefaultChannelSessionRuntimeClient(
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
    public ChannelInboundSessionMessageResponse dispatchInboundMessage(ChannelInboundSessionMessageRequest request) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(HttpUrls.join(apiBaseUrl, "/internal/session-runtime/channel-inbound"))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + internalAuthToken)
                .header("Idempotency-Key", requireText(request.dedupKey(), "channelInbound.dedupKey"))
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new IllegalStateException("session runtime channel inbound dispatch failed with HTTP " + response.statusCode());
            }
            ApiResponse<ChannelInboundSessionMessageResponse> envelope = objectMapper.readValue(response.body(), RESPONSE_TYPE);
            if (envelope == null || !envelope.success() || envelope.data() == null) {
                throw new IllegalStateException("session runtime channel inbound dispatch returned unsuccessful response");
            }
            return envelope.data();
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to dispatch channel inbound message to session runtime", error);
        }
    }

    @Override
    public void replayChannelOutbound(String sessionId) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(HttpUrls.join(
                    apiBaseUrl,
                    "/internal/session-runtime/sessions/" + requireText(sessionId, "sessionId") + "/channel-outbound/replay"
                ))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Authorization", "Bearer " + internalAuthToken)
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new IllegalStateException("session runtime channel outbound replay failed with HTTP " + response.statusCode());
            }
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to replay channel outbound messages", error);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
