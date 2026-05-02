package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.extension.GatewayNativeChannelProviderAdapters;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityResponse;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderActivityPayload;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderActivityRequest;
import com.lynxus.extension.sdk.protocol.DescriptorType;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHttp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class DefaultChannelProviderActivitySender implements ChannelProviderActivitySender {
    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(10);

    private final GatewayNativeChannelProviderAdapters gatewayNativeAdapters;
    private final ObjectMapper objectMapper;
    private final String internalAuthToken;
    private final HttpClient httpClient;

    @Autowired
    DefaultChannelProviderActivitySender(
        GatewayNativeChannelProviderAdapters gatewayNativeAdapters,
        ObjectMapper objectMapper,
        @Qualifier("internalAuthToken") String internalAuthToken
    ) {
        this(gatewayNativeAdapters, objectMapper, internalAuthToken, HttpClient.newHttpClient());
    }

    DefaultChannelProviderActivitySender(
        GatewayNativeChannelProviderAdapters gatewayNativeAdapters,
        ObjectMapper objectMapper,
        String internalAuthToken,
        HttpClient httpClient
    ) {
        this.gatewayNativeAdapters = gatewayNativeAdapters;
        this.objectMapper = objectMapper;
        this.internalAuthToken = internalAuthToken;
        this.httpClient = httpClient;
    }

    @Override
    public ChannelOutboundActivityResponse send(ChannelOutboundActivityInvocation invocation) throws Exception {
        ChannelProviderActivityRequest requestBody = invocation.requestBody();
        ChannelOutboundActivityResponse response = invocation.descriptor().gatewayNative()
            ? sendGatewayNative(invocation, requestBody)
            : sendRemote(invocation, requestBody);
        if (response.status() == null) {
            throw new IllegalStateException("provider sendActivity response status is missing");
        }
        return response;
    }

    private ChannelOutboundActivityResponse sendGatewayNative(
        ChannelOutboundActivityInvocation invocation,
        ChannelProviderActivityRequest requestBody
    ) {
        return gatewayNativeAdapters.find(invocation.descriptor().providerType())
            .orElseThrow(() -> new IllegalStateException("gateway-native channel provider adapter is not configured"))
            .sendActivity(requestBody);
    }

    private ChannelOutboundActivityResponse sendRemote(
        ChannelOutboundActivityInvocation invocation,
        ChannelProviderActivityRequest requestBody
    ) throws Exception {
        if (invocation.descriptor().baseUrl() == null || invocation.descriptor().sendActivityPath() == null) {
            throw new IllegalStateException("channel provider sendActivity endpoint is not configured");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create(joinUrl(invocation.descriptor().baseUrl(), invocation.descriptor().sendActivityPath())))
            .timeout(REMOTE_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBodyJson(requestBody))));
        request.header("Content-Type", "application/json");
        LynxusExtensionHttp.descriptorLevelHeaders(
            "Bearer " + internalAuthToken,
            invocation.descriptor().registrationId(),
            DescriptorType.CHANNEL_PROVIDER,
            invocation.descriptor().providerType(),
            invocation.traceIds().traceId(),
            invocation.traceIds().requestId(),
            invocation.request().idempotencyKey()
        ).forEach(request::header);

        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new IllegalStateException("provider sendActivity failed with HTTP " + response.statusCode());
        }
        try {
            return objectMapper.readValue(response.body(), ChannelOutboundActivityResponse.class);
        } catch (Exception error) {
            throw new IllegalStateException("provider sendActivity response is invalid", error);
        }
    }

    private static Map<String, Object> requestBodyJson(ChannelProviderActivityRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerType", request.providerType());
        body.put("channelProfileId", request.channelProfileId());
        body.put("config", request.config());
        if (request.externalSecretRef() != null && !request.externalSecretRef().isBlank()) {
            body.put("externalSecretRef", request.externalSecretRef());
        }
        body.put("idempotencyKey", request.idempotencyKey());
        body.put("traceContext", traceContextJson(request));
        body.put("payload", payloadJson(request.payload()));
        return body;
    }

    private static Map<String, Object> traceContextJson(ChannelProviderActivityRequest request) {
        Map<String, Object> traceContext = new LinkedHashMap<>();
        traceContext.put("traceparent", request.traceContext().traceparent());
        if (request.traceContext().tracestate() != null && !request.traceContext().tracestate().isBlank()) {
            traceContext.put("tracestate", request.traceContext().tracestate());
        }
        return traceContext;
    }

    private static Map<String, Object> payloadJson(ChannelProviderActivityPayload payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("externalConversationId", payload.externalConversationId());
        if (payload.sessionId() != null) {
            result.put("sessionId", payload.sessionId());
        }
        if (payload.turnId() != null) {
            result.put("turnId", payload.turnId());
        }
        result.put("frameId", payload.frameId());
        result.put("activityType", payload.activityType().name());
        result.put("activity", payload.activity());
        return result;
    }

    private static String joinUrl(String baseUrl, String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String suffix = path.startsWith("/") ? path : "/" + path;
        return base + suffix;
    }
}
