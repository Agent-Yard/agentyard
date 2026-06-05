package com.agentyard.channel.gateway.channel;

import com.agentyard.channel.gateway.shared.ApiResponse;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelInboundEvent;
import com.agentyard.extension.sdk.protocol.AgentYardExtensionHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/internal/channel-events")
public class InternalNormalizedChannelEventController {
    private final NormalizedChannelEventIngestService ingestService;
    private final ObjectMapper objectMapper;

    public InternalNormalizedChannelEventController(
        NormalizedChannelEventIngestService ingestService,
        ObjectMapper objectMapper
    ) {
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/normalized")
    public ApiResponse<?> ingestNormalizedEvent(
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        rejectForbiddenTopLevelFields(body);
        requireNormalizedPayload(body);
        NormalizedChannelInboundEvent event = toEvent(body);
        NormalizedChannelEventHeaders headers = new NormalizedChannelEventHeaders(
            request.getHeader(AgentYardExtensionHeaders.REGISTRATION_ID),
            request.getHeader(AgentYardExtensionHeaders.DESCRIPTOR_TYPE),
            request.getHeader(AgentYardExtensionHeaders.DESCRIPTOR_ID),
            request.getHeader(AgentYardExtensionHeaders.TRACE_ID),
            request.getHeader(AgentYardExtensionHeaders.REQUEST_ID),
            request.getHeader(AgentYardExtensionHeaders.IDEMPOTENCY_KEY)
        );
        var result = ingestService.ingest(event, headers);
        return ApiResponse.ok(result);
    }

    private NormalizedChannelInboundEvent toEvent(Map<String, Object> body) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(body), NormalizedChannelInboundEvent.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("normalized event request is invalid", exception);
        }
    }

    private static void rejectForbiddenTopLevelFields(Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("normalized event request is required");
        }
        if (body.containsKey("accountId")) {
            throw new IllegalArgumentException("normalized event request must not contain accountId");
        }
        if (body.containsKey("externalSecretRef")) {
            throw new IllegalArgumentException("normalized event request must not contain externalSecretRef");
        }
    }

    private static void requireNormalizedPayload(Map<String, Object> body) {
        if (!body.containsKey("normalizedPayload")) {
            throw new IllegalArgumentException("normalized event request.normalizedPayload is required");
        }
        if (!(body.get("normalizedPayload") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("normalized event request.normalizedPayload must be an object");
        }
    }
}
