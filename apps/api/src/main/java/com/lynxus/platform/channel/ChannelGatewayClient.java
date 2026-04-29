package com.lynxus.platform.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelProfileRequest;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelProfileRequest;
import com.lynxus.platform.shared.ConflictException;
import com.lynxus.platform.shared.DownstreamServiceException;
import com.lynxus.platform.shared.logging.PlatformLogContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

@Component
public class ChannelGatewayClient {
    private static final ParameterizedTypeReference<ApiEnvelope<List<ChannelProfile>>> CHANNEL_PROFILE_LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiEnvelope<List<ChannelConversationBinding>>> CHANNEL_BINDING_LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiEnvelope<List<ChannelInboundEvent>>> CHANNEL_INBOUND_EVENT_LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiEnvelope<List<ChannelOutboundDelivery>>> CHANNEL_OUTBOUND_DELIVERY_LIST = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ChannelGatewayClient(
        @Value("${lynxus.channel-gateway.base-url}") String baseUrl,
        @Value("${lynxus.internal-auth.token}") String internalAuthToken,
        ObjectMapper objectMapper
    ) {
        String sanitizedToken = requireInternalAuthToken(internalAuthToken);
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + sanitizedToken)
            .requestInterceptor((request, body, execution) -> {
                PlatformLogContext.outboundHeaders(null, null, null, null)
                    .forEach((headerName, headerValue) -> request.getHeaders().set(headerName, headerValue));
                return execution.execute(request, body);
            })
            .build();
    }

    public List<ChannelProfile> listProfiles() {
        return invoke(() -> body(restClient.get()
            .uri("/internal/channel-admin/profiles")
            .retrieve()
            .body(CHANNEL_PROFILE_LIST)));
    }

    public ChannelProfile createProfile(CreateChannelProfileRequest request) {
        return invoke(() -> body(restClient.post()
            .uri("/internal/channel-admin/profiles")
            .body(request)
            .retrieve()
            .body(new ParameterizedTypeReference<ApiEnvelope<ChannelProfile>>() {
            })));
    }

    public ChannelProfile getProfile(String channelProfileId) {
        return invoke(() -> body(restClient.get()
            .uri("/internal/channel-admin/profiles/{channelProfileId}", channelProfileId)
            .retrieve()
            .body(new ParameterizedTypeReference<ApiEnvelope<ChannelProfile>>() {
            })));
    }

    public ChannelProfile updateProfile(String channelProfileId, UpdateChannelProfileRequest request) {
        return invoke(() -> body(restClient.put()
            .uri("/internal/channel-admin/profiles/{channelProfileId}", channelProfileId)
            .body(request)
            .retrieve()
            .body(new ParameterizedTypeReference<ApiEnvelope<ChannelProfile>>() {
            })));
    }

    public List<ChannelConversationBinding> listBindings(String channelProfileId) {
        return invoke(() -> body(restClient.get()
            .uri("/internal/channel-admin/profiles/{channelProfileId}/bindings", channelProfileId)
            .retrieve()
            .body(CHANNEL_BINDING_LIST)));
    }

    public List<ChannelInboundEvent> listInboundEvents(String channelProfileId) {
        return invoke(() -> body(restClient.get()
            .uri("/internal/channel-admin/profiles/{channelProfileId}/inbound-events", channelProfileId)
            .retrieve()
            .body(CHANNEL_INBOUND_EVENT_LIST)));
    }

    public List<ChannelOutboundDelivery> listOutboundDeliveries(String channelProfileId) {
        return invoke(() -> body(restClient.get()
            .uri("/internal/channel-admin/profiles/{channelProfileId}/outbound-deliveries", channelProfileId)
            .retrieve()
            .body(CHANNEL_OUTBOUND_DELIVERY_LIST)));
    }

    private static String requireInternalAuthToken(String internalAuthToken) {
        if (internalAuthToken == null || internalAuthToken.isBlank()) {
            throw new IllegalStateException("lynxus.internal-auth.token must be configured");
        }
        return internalAuthToken.trim();
    }

    private static <T> T body(ApiEnvelope<T> envelope) {
        if (envelope == null) {
            throw new IllegalStateException("channel gateway returned empty response");
        }
        return envelope.data();
    }

    private <T> T invoke(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException error) {
            throw translate(error);
        }
    }

    private RuntimeException translate(RestClientResponseException error) {
        String detail = extractDetail(error);
        HttpStatusCode status = error.getStatusCode();
        return switch (status.value()) {
            case 400 -> new IllegalArgumentException(detail);
            case 404 -> new NoSuchElementException(detail);
            case 409 -> new ConflictException(detail);
            default -> new DownstreamServiceException(status, detail);
        };
    }

    private String extractDetail(RestClientResponseException error) {
        String responseBody = error.getResponseBodyAsString(StandardCharsets.UTF_8);
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                ProblemPayload payload = objectMapper.readValue(responseBody, ProblemPayload.class);
                if (payload.detail() != null && !payload.detail().isBlank()) {
                    return payload.detail();
                }
            } catch (Exception ignored) {
                return responseBody;
            }
            return responseBody;
        }
        HttpStatus resolvedStatus = HttpStatus.resolve(error.getStatusCode().value());
        if (resolvedStatus != null) {
            return resolvedStatus.getReasonPhrase();
        }
        String statusText = error.getStatusText();
        return statusText == null || statusText.isBlank() ? "channel gateway request failed" : statusText;
    }

    private record ApiEnvelope<T>(boolean success, T data, java.time.Instant timestamp) {
    }

    private record ProblemPayload(String detail) {
    }
}
