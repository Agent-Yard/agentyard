package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.extension.ChannelProviderDescriptor;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistry;
import com.lynxus.contracts.channel.ChannelContracts.ChannelRunJobPayload;
import com.lynxus.contracts.channel.ChannelContracts.ChannelRunJobRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelRunJobResponse;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelTraceContext;
import com.lynxus.extension.sdk.protocol.DescriptorType;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHttp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class RemoteProviderJobExecutor implements ProviderJobExecutor {
    private final ChannelProviderRegistry registry;
    private final NormalizedChannelEventIngestService ingestService;
    private final ObjectMapper objectMapper;
    private final String internalAuthToken;
    private final HttpClient httpClient;

    public RemoteProviderJobExecutor(
        ChannelProviderRegistry registry,
        NormalizedChannelEventIngestService ingestService,
        ObjectMapper objectMapper,
        @Qualifier("internalAuthToken") String internalAuthToken
    ) {
        this(registry, ingestService, objectMapper, internalAuthToken, HttpClient.newHttpClient());
    }

    RemoteProviderJobExecutor(
        ChannelProviderRegistry registry,
        NormalizedChannelEventIngestService ingestService,
        ObjectMapper objectMapper,
        String internalAuthToken,
        HttpClient httpClient
    ) {
        this.registry = registry;
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
        this.internalAuthToken = internalAuthToken;
        this.httpClient = httpClient;
    }

    @Override
    public ProviderJobExecutionResult run(ProviderJobClaim claim) throws Exception {
        ChannelProviderDescriptor descriptor = registry.requireProvider(claim.providerType());
        if (descriptor.baseUrl() == null || descriptor.runJobPath() == null) {
            throw new IllegalStateException("channel provider runJob endpoint is not configured");
        }

        TraceIds traceIds = TraceIds.create();
        ChannelRunJobRequest requestBody = new ChannelRunJobRequest(
            claim.providerType(),
            claim.channelProfileId(),
            claim.profileConfig(),
            blankToNull(claim.externalSecretRef()),
            claim.idempotencyKey(),
            new NormalizedChannelTraceContext(traceIds.traceparent(), null),
            new ChannelRunJobPayload(
                claim.jobType(),
                claim.scheduleConfig().jobConfig(),
                claim.scheduledAt()
            )
        );
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create(joinUrl(descriptor.baseUrl(), descriptor.runJobPath())))
            .timeout(Duration.ofSeconds(claim.jobTimeoutSeconds()))
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)));
        request.header("Content-Type", "application/json");
        LynxusExtensionHttp.descriptorLevelHeaders(
            "Bearer " + internalAuthToken,
            descriptor.registrationId(),
            DescriptorType.CHANNEL_PROVIDER,
            descriptor.providerType(),
            traceIds.traceId(),
            traceIds.requestId(),
            claim.idempotencyKey()
        ).forEach(request::header);

        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new IllegalStateException("provider runJob failed with HTTP " + response.statusCode());
        }
        ChannelRunJobResponse body = objectMapper.readValue(response.body(), ChannelRunJobResponse.class);
        int ingested = 0;
        for (NormalizedChannelInboundEvent event : body.events()) {
            var ingestResult = ingestService.ingest(event, new NormalizedChannelEventHeaders(
                descriptor.registrationId(),
                DescriptorType.CHANNEL_PROVIDER.wireValue(),
                descriptor.providerType(),
                traceIds.traceId(),
                TraceIds.nextRequestId(),
                event.dedupKey()
            ));
            if (!ingestResult.duplicate()) {
                ingested++;
            }
        }
        return new ProviderJobExecutionResult(ingested, body.nextCursor(), body.metadata());
    }

    private static String joinUrl(String baseUrl, String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String suffix = path.startsWith("/") ? path : "/" + path;
        return base + suffix;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record TraceIds(String traceId, String traceparent, String requestId) {
        static TraceIds create() {
            String traceId = java.util.UUID.randomUUID().toString().replace("-", "");
            String spanId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            return new TraceIds(traceId, "00-" + traceId + "-" + spanId + "-01", nextRequestId());
        }

        static String nextRequestId() {
            return "request-" + java.util.UUID.randomUUID();
        }
    }
}
