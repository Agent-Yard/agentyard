package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionMessageRequest;
import com.lynxus.platform.integration.InternalRuntimeAuth;
import com.lynxus.platform.shared.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalSessionRuntimeController {
    private final SessionRuntimeService sessionRuntimeService;
    private final SessionChannelOutboundRelay outboundRelay;
    private final InternalRuntimeAuth internalRuntimeAuth;

    public InternalSessionRuntimeController(
        SessionRuntimeService sessionRuntimeService,
        SessionChannelOutboundRelay outboundRelay,
        InternalRuntimeAuth internalRuntimeAuth
    ) {
        this.sessionRuntimeService = sessionRuntimeService;
        this.outboundRelay = outboundRelay;
        this.internalRuntimeAuth = internalRuntimeAuth;
    }

    @PostMapping("/api/internal/session-runtime/channel-inbound")
    public ApiResponse<?> channelInboundMessage(
        @RequestBody ChannelInboundSessionMessageRequest request,
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        internalRuntimeAuth.requireBearer(authorization);
        return ApiResponse.ok(sessionRuntimeService.channelInboundMessage(request, idempotencyKey));
    }

    @PostMapping("/api/internal/session-runtime/sessions/{sessionId}/channel-outbound/replay")
    public ApiResponse<?> replayChannelOutbound(
        @PathVariable String sessionId,
        @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        internalRuntimeAuth.requireBearer(authorization);
        outboundRelay.relaySession(sessionId);
        return ApiResponse.ok(Map.of("sessionId", sessionId));
    }
}
