package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.extension.ChannelProviderDescriptor;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistry;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityResponse;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityResponseStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityType;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class OutboundActivityExecutionService {
    private static final int MAX_TRANSIENT_IDEMPOTENCY_KEYS = 10_000;

    private final ChannelAdminRepository repository;
    private final ChannelProviderRegistry channelProviderRegistry;
    private final ChannelProviderActivitySender activitySender;
    private final Set<String> handledIdempotencyKeys = ConcurrentHashMap.newKeySet();

    public OutboundActivityExecutionService(
        ChannelAdminRepository repository,
        ChannelProviderRegistry channelProviderRegistry,
        ChannelProviderActivitySender activitySender
    ) {
        this.repository = repository;
        this.channelProviderRegistry = channelProviderRegistry;
        this.activitySender = activitySender;
    }

    public ChannelOutboundActivityResponse send(ChannelOutboundActivityRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("outbound activity request is required");
        }
        String channelProfileId = requireText(request.channelProfileId(), "outboundActivity.channelProfileId");
        ChannelOutboundActivityType activityType = requireActivityType(request.activityType());
        String idempotencyKey = requireText(request.idempotencyKey(), "outboundActivity.idempotencyKey");
        if (!recordTransientIdempotencyKey(idempotencyKey)) {
            return response(ChannelOutboundActivityResponseStatus.NO_OP, Map.of("duplicate", true));
        }

        ChannelOutboundProfileSnapshot profile = repository.findOutboundProfileSnapshot(channelProfileId)
            .orElseThrow(() -> new NoSuchElementException("channel profile not found: " + channelProfileId));
        if (profile.status() != ChannelProfileStatus.ACTIVE) {
            return response(ChannelOutboundActivityResponseStatus.NO_OP, Map.of("reason", "CHANNEL_PROFILE_INACTIVE"));
        }
        String assistantId = requireText(request.assistantId(), "outboundActivity.assistantId");
        if (profile.assistantBinding() != null
            && profile.assistantBinding().assistantId() != null
            && !assistantId.equals(profile.assistantBinding().assistantId())) {
            return response(ChannelOutboundActivityResponseStatus.NO_OP, Map.of("reason", "ASSISTANT_BINDING_MISMATCH"));
        }
        requireText(request.externalConversationId(), "outboundActivity.externalConversationId");
        requireText(request.frameId(), "outboundActivity.frameId");

        ChannelProviderDescriptor descriptor = channelProviderRegistry.requireProvider(profile.providerType());
        if (!supports(descriptor, activityType)) {
            return response(ChannelOutboundActivityResponseStatus.UNSUPPORTED, Map.of("activityType", activityType.name()));
        }
        if (!descriptor.gatewayNative() && descriptor.sendActivityPath() == null) {
            return response(ChannelOutboundActivityResponseStatus.UNSUPPORTED, Map.of("reason", "SEND_ACTIVITY_ENDPOINT_MISSING"));
        }

        try {
            return activitySender.send(new ChannelOutboundActivityInvocation(
                descriptor,
                profile,
                request,
                TraceIds.fromOrCreate(request.traceContext())
            ));
        } catch (Exception error) {
            throw new IllegalStateException(OutboundErrorSanitizer.sanitize(error), error);
        }
    }

    private boolean recordTransientIdempotencyKey(String idempotencyKey) {
        if (handledIdempotencyKeys.size() > MAX_TRANSIENT_IDEMPOTENCY_KEYS) {
            handledIdempotencyKeys.clear();
        }
        return handledIdempotencyKeys.add(idempotencyKey);
    }

    private static boolean supports(ChannelProviderDescriptor descriptor, ChannelOutboundActivityType activityType) {
        return switch (activityType) {
            case TYPING_START, TYPING_STOP -> descriptor.supportsTyping();
            case DRAFT_UPDATE, DRAFT_COMPLETE, DRAFT_DISCARD -> descriptor.supportsDraftUpdate();
        };
    }

    private static ChannelOutboundActivityType requireActivityType(ChannelOutboundActivityType activityType) {
        if (activityType == null) {
            throw new IllegalArgumentException("outboundActivity.activityType is required");
        }
        return activityType;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static ChannelOutboundActivityResponse response(
        ChannelOutboundActivityResponseStatus status,
        Map<String, Object> metadata
    ) {
        return new ChannelOutboundActivityResponse(status, false, metadata);
    }
}
