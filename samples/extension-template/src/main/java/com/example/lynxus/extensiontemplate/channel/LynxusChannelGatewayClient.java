package com.example.lynxus.extensiontemplate.channel;

import com.example.lynxus.extensiontemplate.config.ExtensionTemplateProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynxus.extension.sdk.generated.protocol.model.ChannelOutboundFrameAck;
import com.lynxus.extension.sdk.generated.protocol.model.ChannelOutboundFrameAckResponse;
import com.lynxus.extension.sdk.generated.protocol.model.ChannelOutboundFrameSubscriptionList;
import com.lynxus.extension.sdk.generated.protocol.model.NormalizedChannelInboundEvent;
import com.lynxus.extension.sdk.generated.protocol.model.NormalizedEventAccepted;
import com.lynxus.extension.sdk.protocol.DescriptorType;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHttp;
import com.lynxus.extension.sdk.protocol.LynxusExtensionProtocol;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public final class LynxusChannelGatewayClient {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final ExtensionTemplateProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LynxusChannelGatewayClient(ExtensionTemplateProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build();
    }

    public ChannelOutboundFrameSubscriptionList listOutboundFrameSubscriptions(String traceId, String requestId) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(resolve(LynxusExtensionProtocol.CHANNEL_OUTBOUND_FRAME_SUBSCRIPTIONS_PATH))
            .timeout(DEFAULT_TIMEOUT)
            .GET();
        descriptorHeaders(traceId, requestId, "outbound-subscriptions").forEach(request::header);
        return sendJson(request.build(), ChannelOutboundFrameSubscriptionList.class);
    }

    public ChannelOutboundFrameAckResponse ackFinalDelivery(ChannelOutboundFrameAck ack, String traceId, String requestId) {
        try {
            String body = objectMapper.writeValueAsString(ack);
            HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(resolve(LynxusExtensionProtocol.CHANNEL_OUTBOUND_FRAMES_ACK_PATH))
                .timeout(DEFAULT_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
            descriptorHeaders(traceId, requestId, ack.getFrameId()).forEach(request::header);
            return sendJson(request.build(), ChannelOutboundFrameAckResponse.class);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to serialize channel outbound frame ACK", exception);
        }
    }

    public NormalizedEventAccepted submitNormalizedInboundEvent(
        NormalizedChannelInboundEvent event,
        String traceId,
        String requestId,
        String dedupKey
    ) {
        try {
            String body = objectMapper.writeValueAsString(event);
            HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(resolve("/internal/channel-events/normalized"))
                .timeout(DEFAULT_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
            descriptorHeaders(traceId, requestId, dedupKey).forEach(request::header);
            return sendJson(request.build(), NormalizedEventAccepted.class);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to serialize normalized inbound event", exception);
        }
    }

    public URI outboundFrameStreamUri(String channelProfileId) {
        return UriComponentsBuilder
            .fromUri(resolve(LynxusExtensionProtocol.CHANNEL_OUTBOUND_FRAMES_STREAM_PATH))
            .queryParam("channelProfileId", channelProfileId)
            .build()
            .toUri();
    }

    private <T> T sendJson(HttpRequest request, Class<T> responseType) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new IllegalStateException("Lynxus gateway returned HTTP " + response.statusCode());
            }
            if (response.body() == null || response.body().isBlank()) {
                throw new IllegalStateException("Lynxus gateway returned an empty response body");
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to call Lynxus channel gateway", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling Lynxus channel gateway", exception);
        }
    }

    private Map<String, String> descriptorHeaders(String traceId, String requestId, String idempotencyKey) {
        return LynxusExtensionHttp.descriptorLevelHeaders(
            "Bearer " + properties.internalAuthToken(),
            properties.registrationId(),
            DescriptorType.CHANNEL_PROVIDER,
            properties.channelProviderType(),
            traceId,
            requestId,
            idempotencyKey
        );
    }

    private URI resolve(String path) {
        String base = properties.lynxusChannelGatewayBaseUrl().toString();
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return URI.create(normalizedBase + normalizedPath);
    }
}
