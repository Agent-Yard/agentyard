package com.lynxus.platform.channel;

import com.lynxus.platform.integration.InternalRuntimeAuth;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class InternalChannelOutboundFrameStreamController {
    private final ChannelOutboundFramePublisher framePublisher;
    private final InternalRuntimeAuth internalRuntimeAuth;

    public InternalChannelOutboundFrameStreamController(
        ChannelOutboundFramePublisher framePublisher,
        InternalRuntimeAuth internalRuntimeAuth
    ) {
        this.framePublisher = framePublisher;
        this.internalRuntimeAuth = internalRuntimeAuth;
    }

    @GetMapping(value = "/api/internal/channel-outbound/frames/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
        @RequestParam String channelProfileId,
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
        @RequestHeader(name = "X-Lynxus-Last-Acked-Final-Sequence", required = false) Long lastAckedFinalSequence,
        @RequestHeader(name = "X-Lynxus-Last-Acked-Session-Id", required = false) String lastAckedSessionId,
        @RequestHeader(name = "X-Lynxus-Last-Acked-Session-Message-Id", required = false) String lastAckedSessionMessageId,
        @RequestHeader(name = "X-Lynxus-Max-Final-Replay-Frames", required = false) Integer maxFinalReplayFrames
    ) {
        internalRuntimeAuth.requireBearer(authorization);
        validateCheckpointHeaders(lastAckedFinalSequence, lastAckedSessionId, lastAckedSessionMessageId);
        try {
            return framePublisher.connect(channelProfileId, lastEventId, lastAckedFinalSequence, maxFinalReplayFrames);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage(), error);
        }
    }

    private static void validateCheckpointHeaders(
        Long lastAckedFinalSequence,
        String lastAckedSessionId,
        String lastAckedSessionMessageId
    ) {
        boolean hasDiagnosticSession = hasText(lastAckedSessionId) || hasText(lastAckedSessionMessageId);
        if (hasDiagnosticSession && lastAckedFinalSequence == null) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "lastAckedFinalSequence is required when diagnostic final checkpoint headers are present"
            );
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
