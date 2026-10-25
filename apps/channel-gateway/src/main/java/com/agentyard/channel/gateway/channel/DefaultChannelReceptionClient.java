package com.agentyard.channel.gateway.channel;

import com.agentyard.channel.gateway.shared.ApiResponse;
import com.agentyard.contracts.http.HttpUrls;
import com.agentyard.contracts.channel.ChannelContracts.ChannelReceptionMessage;
import com.agentyard.contracts.channel.ChannelContracts.ChannelReceptionRequest;
import com.agentyard.contracts.channel.ChannelContracts.ChannelReceptionResponse;
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
final class DefaultChannelReceptionClient implements ChannelReceptionClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final TypeReference<ApiResponse<ChannelReceptionResponse>> RESPONSE_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;
    private final String internalAuthToken;
    private final HttpClient httpClient;

    @Autowired
    DefaultChannelReceptionClient(
        ObjectMapper objectMapper,
        @Value("${agentyard.api.base-url}") String apiBaseUrl,
        @Qualifier("internalAuthToken") String internalAuthToken
    ) {
        this(objectMapper, apiBaseUrl, internalAuthToken, HttpClient.newHttpClient());
    }

    DefaultChannelReceptionClient(
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
    public ChannelReceptionResponse dispatchInboundTurn(ChannelReceptionRequest request) {
        validateRequest(request);
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(HttpUrls.join(apiBaseUrl, "/internal/channel-reception/messages"))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + internalAuthToken)
                .header("Idempotency-Key", requireText(request.dedupKey(), "channelInbound.dedupKey"))
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400 && response.statusCode() < 500) {
                throw new ChannelInboundSessionRejectedException(
                    "Agent reception rejected channel inbound turn with HTTP "
                        + response.statusCode()
                        + responseBodySummary(response.body())
                );
            }
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new IllegalStateException("Agent reception channel inbound dispatch failed with HTTP " + response.statusCode());
            }
            ApiResponse<ChannelReceptionResponse> envelope = objectMapper.readValue(response.body(), RESPONSE_TYPE);
            if (envelope == null || !envelope.success() || envelope.data() == null) {
                throw new IllegalStateException("Agent reception channel inbound dispatch returned unsuccessful response");
            }
            return envelope.data();
        } catch (ChannelInboundSessionRejectedException error) {
            throw error;
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to dispatch channel inbound turn to Agent reception", error);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String responseBodySummary(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String normalized = body.replace('\n', ' ').replace('\r', ' ').trim();
        int maxLength = 300;
        return ": " + (normalized.length() > maxLength ? normalized.substring(0, maxLength) + "..." : normalized);
    }

    private static void validateRequest(ChannelReceptionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("channelInbound request is required");
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            throw new IllegalArgumentException("channelInbound.messages are required");
        }
        for (int index = 0; index < request.messages().size(); index += 1) {
            ChannelReceptionMessage message = request.messages().get(index);
            String messageField = "channelInbound.messages[" + index + "]";
            if (message == null) {
                throw new IllegalArgumentException(messageField + " is required");
            }
            requireText(message.externalUserId(), messageField + ".externalUserId");
            requireText(message.displayName(), messageField + ".displayName");
        }
    }
}
