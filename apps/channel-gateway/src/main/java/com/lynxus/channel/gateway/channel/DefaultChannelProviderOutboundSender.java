package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.extension.GatewayNativeChannelProviderAdapters;
import com.lynxus.contracts.http.HttpUrls;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundPayload;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundResolvedTemplate;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundResponse;
import com.lynxus.extension.sdk.protocol.DescriptorType;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHttp;
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
final class DefaultChannelProviderOutboundSender implements ChannelProviderOutboundSender {
    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(30);

    private final GatewayNativeChannelProviderAdapters gatewayNativeAdapters;
    private final ObjectMapper objectMapper;
    private final String internalAuthToken;
    private final HttpClient httpClient;

    @Autowired
    DefaultChannelProviderOutboundSender(
        GatewayNativeChannelProviderAdapters gatewayNativeAdapters,
        ObjectMapper objectMapper,
        @Qualifier("internalAuthToken") String internalAuthToken
    ) {
        this(gatewayNativeAdapters, objectMapper, internalAuthToken, HttpClient.newHttpClient());
    }

    DefaultChannelProviderOutboundSender(
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
    public ChannelOutboundResponse send(ChannelOutboundInvocation invocation) throws Exception {
        ChannelOutboundRequest requestBody = invocation.requestBody();
        ChannelOutboundResponse response = invocation.descriptor().gatewayNative()
            ? sendGatewayNative(invocation, requestBody)
            : sendRemote(invocation, requestBody);
        if (response.status() == null) {
            throw new IllegalStateException("provider sendOutbound response status is missing");
        }
        return response;
    }

    private ChannelOutboundResponse sendGatewayNative(
        ChannelOutboundInvocation invocation,
        ChannelOutboundRequest requestBody
    ) {
        return gatewayNativeAdapters.find(invocation.descriptor().providerType())
            .orElseThrow(() -> new IllegalStateException("gateway-native channel provider adapter is not configured"))
            .sendOutbound(requestBody);
    }

    private ChannelOutboundResponse sendRemote(
        ChannelOutboundInvocation invocation,
        ChannelOutboundRequest requestBody
    ) throws Exception {
        if (invocation.descriptor().baseUrl() == null || invocation.descriptor().sendOutboundPath() == null) {
            throw new IllegalStateException("channel provider sendOutbound endpoint is not configured");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(HttpUrls.join(invocation.descriptor().baseUrl(), invocation.descriptor().sendOutboundPath()))
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
            invocation.idempotencyKey()
        ).forEach(request::header);

        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new IllegalStateException("provider sendOutbound failed with HTTP " + response.statusCode());
        }
        try {
            return objectMapper.readValue(response.body(), ChannelOutboundResponse.class);
        } catch (Exception error) {
            throw new IllegalStateException("provider sendOutbound response is invalid", error);
        }
    }

    private static Map<String, Object> requestBodyJson(ChannelOutboundRequest request) {
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

    private static Map<String, Object> traceContextJson(ChannelOutboundRequest request) {
        Map<String, Object> traceContext = new LinkedHashMap<>();
        traceContext.put("traceparent", request.traceContext().traceparent());
        if (request.traceContext().tracestate() != null && !request.traceContext().tracestate().isBlank()) {
            traceContext.put("tracestate", request.traceContext().tracestate());
        }
        return traceContext;
    }

    private static Map<String, Object> payloadJson(ChannelOutboundPayload payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("externalConversationId", payload.externalConversationId());
        result.put("messageBlock", payload.messageBlock());
        if (payload.resolvedTemplate() != null) {
            result.put("resolvedTemplate", resolvedTemplateJson(payload.resolvedTemplate()));
        }
        return result;
    }

    private static Map<String, Object> resolvedTemplateJson(ChannelOutboundResolvedTemplate template) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messageType", template.messageType());
        result.put("messageSubtype", template.messageSubtype());
        result.put("messageVersion", template.messageVersion());
        result.put("externalTemplateId", template.externalTemplateId());
        result.put("externalTemplateVersion", template.externalTemplateVersion());
        return result;
    }

}
