package com.lynxus.platform.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDeliveryRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobConfig;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobConfigWriteRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobRun;
import com.lynxus.contracts.channel.ChannelContracts.ChannelTemplateBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelTemplateBindingWriteRequest;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelProfileInternalRequest;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelProfileInternalRequest;
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
    private static final ParameterizedTypeReference<ApiEnvelope<List<ChannelGatewayProfile>>> CHANNEL_PROFILE_LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiEnvelope<List<ChannelConversationBinding>>> CHANNEL_BINDING_LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiEnvelope<List<ChannelInboundEvent>>> CHANNEL_INBOUND_EVENT_LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiEnvelope<List<ChannelOutboundDelivery>>> CHANNEL_OUTBOUND_DELIVERY_LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiEnvelope<ChannelConversationBinding>> CHANNEL_BINDING = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiEnvelope<ChannelOutboundDelivery>> CHANNEL_OUTBOUND_DELIVERY = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiEnvelope<List<ChannelTemplateBinding>>> CHANNEL_TEMPLATE_BINDING_LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiEnvelope<List<ChannelProviderJobConfig>>> CHANNEL_PROVIDER_JOB_LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiEnvelope<List<ChannelProviderJobRun>>> CHANNEL_PROVIDER_JOB_RUN_LIST = new ParameterizedTypeReference<>() {
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

    public List<ChannelGatewayProfile> listProfiles() {
        return invoke(() -> body(restClient.get()
            .uri("/internal/channel-admin/profiles")
            .retrieve()
            .body(CHANNEL_PROFILE_LIST)));
    }

    public ChannelGatewayProfile createProfile(CreateChannelProfileInternalRequest request) {
        return invoke(() -> body(restClient.post()
            .uri("/internal/channel-admin/profiles")
            .body(request)
            .retrieve()
            .body(new ParameterizedTypeReference<ApiEnvelope<ChannelGatewayProfile>>() {
            })));
    }

    public ChannelGatewayProfile getProfile(String channelProfileId) {
        return invoke(() -> body(restClient.get()
            .uri("/internal/channel-admin/profiles/{channelProfileId}", channelProfileId)
            .retrieve()
            .body(new ParameterizedTypeReference<ApiEnvelope<ChannelGatewayProfile>>() {
            })));
    }

    public ChannelGatewayProfile updateProfile(String channelProfileId, UpdateChannelProfileInternalRequest request) {
        return invoke(() -> body(restClient.put()
            .uri("/internal/channel-admin/profiles/{channelProfileId}", channelProfileId)
            .body(request)
            .retrieve()
            .body(new ParameterizedTypeReference<ApiEnvelope<ChannelGatewayProfile>>() {
            })));
    }

    public ChannelGatewayProfile deleteProfile(String channelProfileId, long expectedRevision) {
        return invoke(() -> body(restClient.delete()
            .uri(uriBuilder -> uriBuilder
                .path("/internal/channel-admin/profiles/{channelProfileId}")
                .queryParam("expectedRevision", expectedRevision)
                .build(channelProfileId))
            .retrieve()
            .body(new ParameterizedTypeReference<ApiEnvelope<ChannelGatewayProfile>>() {
            })));
    }

    public List<ChannelConversationBinding> listBindings(String channelProfileId) {
        return invoke(() -> body(restClient.get()
            .uri("/internal/channel-admin/profiles/{channelProfileId}/bindings", channelProfileId)
            .retrieve()
            .body(CHANNEL_BINDING_LIST)));
    }

    public ChannelConversationBinding getBindingBySession(String sessionId) {
        return invoke(() -> body(restClient.get()
            .uri("/internal/channel-admin/profiles/bindings/by-session/{sessionId}", sessionId)
            .retrieve()
            .body(CHANNEL_BINDING)));
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

    public ChannelOutboundDelivery deliverOutbound(ChannelOutboundDeliveryRequest request) {
        return invoke(() -> body(restClient.post()
            .uri("/internal/channel-outbound/deliveries")
            .body(request)
            .retrieve()
            .body(CHANNEL_OUTBOUND_DELIVERY)));
    }

    public List<ChannelTemplateBinding> listTemplateBindings(String channelProfileId) {
        return invoke(() -> body(restClient.get()
            .uri("/internal/channel-admin/profiles/{channelProfileId}/template-bindings", channelProfileId)
            .retrieve()
            .body(CHANNEL_TEMPLATE_BINDING_LIST)));
    }

    public ChannelTemplateBinding upsertTemplateBinding(
        String channelProfileId,
        String assistantId,
        String messageType,
        String messageSubtype,
        String messageVersion,
        ChannelTemplateBindingWriteRequest request
    ) {
        return invoke(() -> body(restClient.put()
            .uri(
                "/internal/channel-admin/profiles/{channelProfileId}/template-bindings/{assistantId}/{messageType}/{messageSubtype}/{messageVersion}",
                channelProfileId,
                assistantId,
                messageType,
                messageSubtype,
                messageVersion
            )
            .body(request)
            .retrieve()
            .body(new ParameterizedTypeReference<ApiEnvelope<ChannelTemplateBinding>>() {
            })));
    }

    public ChannelTemplateBinding deleteTemplateBinding(
        String channelProfileId,
        String assistantId,
        String messageType,
        String messageSubtype,
        String messageVersion,
        long expectedRevision
    ) {
        return invoke(() -> body(restClient.delete()
            .uri(uriBuilder -> uriBuilder
                .path("/internal/channel-admin/profiles/{channelProfileId}/template-bindings/{assistantId}/{messageType}/{messageSubtype}/{messageVersion}")
                .queryParam("expectedRevision", expectedRevision)
                .build(channelProfileId, assistantId, messageType, messageSubtype, messageVersion))
            .retrieve()
            .body(new ParameterizedTypeReference<ApiEnvelope<ChannelTemplateBinding>>() {
            })));
    }

    public List<ChannelProviderJobConfig> listJobs(String channelProfileId) {
        return invoke(() -> body(restClient.get()
            .uri("/internal/channel-admin/profiles/{channelProfileId}/jobs", channelProfileId)
            .retrieve()
            .body(CHANNEL_PROVIDER_JOB_LIST)));
    }

    public ChannelProviderJobConfig upsertJob(
        String channelProfileId,
        String jobType,
        ChannelProviderJobConfigWriteRequest request
    ) {
        return invoke(() -> body(restClient.put()
            .uri("/internal/channel-admin/profiles/{channelProfileId}/jobs/{jobType}", channelProfileId, jobType)
            .body(request)
            .retrieve()
            .body(new ParameterizedTypeReference<ApiEnvelope<ChannelProviderJobConfig>>() {
            })));
    }

    public ChannelProviderJobConfig deleteJob(String channelProfileId, String jobType, long expectedRevision) {
        return invoke(() -> body(restClient.delete()
            .uri(uriBuilder -> uriBuilder
                .path("/internal/channel-admin/profiles/{channelProfileId}/jobs/{jobType}")
                .queryParam("expectedRevision", expectedRevision)
                .build(channelProfileId, jobType))
            .retrieve()
            .body(new ParameterizedTypeReference<ApiEnvelope<ChannelProviderJobConfig>>() {
            })));
    }

    public List<ChannelProviderJobRun> listJobRuns(String channelProfileId, String jobType) {
        return invoke(() -> body(restClient.get()
            .uri("/internal/channel-admin/profiles/{channelProfileId}/jobs/{jobType}/runs", channelProfileId, jobType)
            .retrieve()
            .body(CHANNEL_PROVIDER_JOB_RUN_LIST)));
    }

    public ChannelProviderJobRun runJob(String channelProfileId, String jobType) {
        return invoke(() -> body(restClient.post()
            .uri("/internal/channel-admin/profiles/{channelProfileId}/jobs/{jobType}/runs", channelProfileId, jobType)
            .retrieve()
            .body(new ParameterizedTypeReference<ApiEnvelope<ChannelProviderJobRun>>() {
            })));
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
            case 400, 422 -> new IllegalArgumentException(detail);
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
