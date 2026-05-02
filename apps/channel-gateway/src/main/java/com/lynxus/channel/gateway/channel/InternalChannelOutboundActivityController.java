package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.shared.ApiResponse;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundActivityRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/internal/channel-outbound/activities")
public class InternalChannelOutboundActivityController {
    private static final Set<String> ALLOWED_FIELDS = Set.of(
        "channelProfileId",
        "assistantId",
        "externalConversationId",
        "sessionId",
        "turnId",
        "frameId",
        "activityType",
        "idempotencyKey",
        "payload",
        "traceContext"
    );

    private final OutboundActivityExecutionService outboundActivityExecutionService;
    private final ObjectMapper objectMapper;

    public InternalChannelOutboundActivityController(
        OutboundActivityExecutionService outboundActivityExecutionService,
        ObjectMapper objectMapper
    ) {
        this.outboundActivityExecutionService = outboundActivityExecutionService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ApiResponse<?> send(@RequestBody Map<String, Object> body) {
        rejectUnsupportedTopLevelFields(body);
        return ApiResponse.ok(outboundActivityExecutionService.send(toRequest(body)));
    }

    private ChannelOutboundActivityRequest toRequest(Map<String, Object> body) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(body), ChannelOutboundActivityRequest.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("outbound activity request is invalid", exception);
        }
    }

    private static void rejectUnsupportedTopLevelFields(Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("outbound activity request is required");
        }
        for (String field : body.keySet()) {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("outbound activity request must not contain " + field);
            }
        }
    }
}
