package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.shared.ApiResponse;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDeliveryRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/internal/channel-outbound/deliveries")
public class InternalChannelOutboundController {
    private static final Set<String> ALLOWED_FIELDS = Set.of(
        "channelProfileId",
        "assistantId",
        "externalConversationId",
        "sessionId",
        "sessionMessageId",
        "messageBlock",
        "traceContext"
    );

    private final OutboundDeliveryExecutionService outboundDeliveryExecutionService;
    private final ObjectMapper objectMapper;

    public InternalChannelOutboundController(
        OutboundDeliveryExecutionService outboundDeliveryExecutionService,
        ObjectMapper objectMapper
    ) {
        this.outboundDeliveryExecutionService = outboundDeliveryExecutionService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ApiResponse<?> deliver(@RequestBody Map<String, Object> body) {
        rejectUnsupportedTopLevelFields(body);
        requireMessageBlockObject(body);
        return ApiResponse.ok(outboundDeliveryExecutionService.deliver(toRequest(body)));
    }

    private ChannelOutboundDeliveryRequest toRequest(Map<String, Object> body) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(body), ChannelOutboundDeliveryRequest.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("outbound delivery request is invalid", exception);
        }
    }

    private static void rejectUnsupportedTopLevelFields(Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("outbound delivery request is required");
        }
        for (String field : body.keySet()) {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("outbound delivery request must not contain " + field);
            }
        }
    }

    private static void requireMessageBlockObject(Map<String, Object> body) {
        if (!body.containsKey("messageBlock")) {
            throw new IllegalArgumentException("outbound delivery request.messageBlock is required");
        }
        if (!(body.get("messageBlock") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("outbound delivery request.messageBlock must be an object");
        }
    }
}
