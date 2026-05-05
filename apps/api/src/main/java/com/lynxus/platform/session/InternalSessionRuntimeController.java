package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionContracts.AgentTurnTransientFrame;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionMessageRequest;
import com.lynxus.platform.integration.InternalRuntimeAuth;
import com.lynxus.platform.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
public class InternalSessionRuntimeController {
    private final SessionRuntimeService sessionRuntimeService;
    private final SessionRuntimeStreamService streamService;
    private final InternalRuntimeAuth internalRuntimeAuth;
    private final ObjectMapper objectMapper;

    public InternalSessionRuntimeController(
        SessionRuntimeService sessionRuntimeService,
        SessionRuntimeStreamService streamService,
        InternalRuntimeAuth internalRuntimeAuth,
        ObjectMapper objectMapper
    ) {
        this.sessionRuntimeService = sessionRuntimeService;
        this.streamService = streamService;
        this.internalRuntimeAuth = internalRuntimeAuth;
        this.objectMapper = objectMapper;
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

    @PostMapping(
        value = "/api/internal/session-runtime/stream-frame-ingest",
        consumes = MediaType.APPLICATION_NDJSON_VALUE
    )
    public ApiResponse<?> acceptStreamFrameIngest(
        HttpServletRequest request,
        @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        internalRuntimeAuth.requireBearer(authorization);
        StreamFrameIngestResult result = acceptStreamFrameLines(request);
        return ApiResponse.ok(Map.of(
            "turnExecutionId",
            result.turnExecutionId() == null ? "" : result.turnExecutionId(),
            "frames",
            result.frames(),
            "accepted",
            result.accepted(),
            "duplicates",
            result.duplicates()
        ));
    }

    private StreamFrameIngestResult acceptStreamFrameLines(HttpServletRequest request) {
        StreamFrameIngestScope scope = null;
        int frames = 0;
        int accepted = 0;
        int duplicates = 0;
        long lineNumber = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber += 1;
                if (line.isBlank()) {
                    continue;
                }
                AgentTurnTransientFrame frame = readTransientFrame(line, lineNumber);
                scope = requireSingleTurnExecution(scope, frame, lineNumber);
                frames += 1;
                if (streamService.acceptStreamFrame(frame)) {
                    accepted += 1;
                } else {
                    duplicates += 1;
                }
            }
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "failed to read transient frame ingest stream", error);
        }
        return new StreamFrameIngestResult(scope == null ? null : scope.turnExecutionId(), frames, accepted, duplicates);
    }

    private AgentTurnTransientFrame readTransientFrame(String line, long lineNumber) {
        try {
            return objectMapper.readValue(line, AgentTurnTransientFrame.class);
        } catch (JacksonException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid transient frame NDJSON at line " + lineNumber, error);
        }
    }

    private static StreamFrameIngestScope requireSingleTurnExecution(
        StreamFrameIngestScope expected,
        AgentTurnTransientFrame frame,
        long lineNumber
    ) {
        StreamFrameIngestScope actual = new StreamFrameIngestScope(frame.sessionId(), frame.turnId(), frame.turnExecutionId());
        if (expected == null) {
            return actual;
        }
        if (!expected.equals(actual)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "transient frame NDJSON line " + lineNumber + " belongs to a different turn execution"
            );
        }
        return expected;
    }

    private record StreamFrameIngestResult(String turnExecutionId, int frames, int accepted, int duplicates) {
    }

    private record StreamFrameIngestScope(String sessionId, String turnId, String turnExecutionId) {
    }
}
