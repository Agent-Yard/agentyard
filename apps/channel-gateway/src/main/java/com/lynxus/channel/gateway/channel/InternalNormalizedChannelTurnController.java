package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.shared.ApiResponse;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundTurn;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/internal/channel-turns")
public class InternalNormalizedChannelTurnController {
    private final NormalizedChannelTurnIngestService ingestService;
    private final ChannelInboundSessionDispatcher sessionDispatcher;
    private final ObjectMapper objectMapper;

    public InternalNormalizedChannelTurnController(
        NormalizedChannelTurnIngestService ingestService,
        ChannelInboundSessionDispatcher sessionDispatcher,
        ObjectMapper objectMapper
    ) {
        this.ingestService = ingestService;
        this.sessionDispatcher = sessionDispatcher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/normalized")
    public ApiResponse<?> ingestNormalizedTurn(
        @RequestBody Map<String, Object> body,
        HttpServletRequest request
    ) {
        rejectForbiddenTopLevelFields(body);
        requireNormalizedPayload(body);
        NormalizedChannelInboundTurn turn = toTurn(body);
        NormalizedChannelEventHeaders headers = new NormalizedChannelEventHeaders(
            request.getHeader(LynxusExtensionHeaders.REGISTRATION_ID),
            request.getHeader(LynxusExtensionHeaders.DESCRIPTOR_TYPE),
            request.getHeader(LynxusExtensionHeaders.DESCRIPTOR_ID),
            request.getHeader(LynxusExtensionHeaders.TRACE_ID),
            request.getHeader(LynxusExtensionHeaders.REQUEST_ID),
            request.getHeader(LynxusExtensionHeaders.IDEMPOTENCY_KEY)
        );
        ChannelInboundTurnIngestResult ingestResult = ingestService.ingest(turn, headers);
        return ApiResponse.ok(sessionDispatcher.dispatch(turn, ingestResult));
    }

    private NormalizedChannelInboundTurn toTurn(Map<String, Object> body) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(body), NormalizedChannelInboundTurn.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("normalized turn request is invalid", exception);
        }
    }

    private static void rejectForbiddenTopLevelFields(Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("normalized turn request is required");
        }
        if (body.containsKey("accountId")) {
            throw new IllegalArgumentException("normalized turn request must not contain accountId");
        }
        if (body.containsKey("externalSecretRef")) {
            throw new IllegalArgumentException("normalized turn request must not contain externalSecretRef");
        }
    }

    private static void requireNormalizedPayload(Map<String, Object> body) {
        if (!body.containsKey("normalizedPayload")) {
            throw new IllegalArgumentException("normalized turn request.normalizedPayload is required");
        }
        if (!(body.get("normalizedPayload") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("normalized turn request.normalizedPayload must be an object");
        }
    }
}
