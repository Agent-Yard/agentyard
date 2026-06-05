package com.agentyard.platform.session;

import com.agentyard.contracts.session.SessionContracts.AgentTurnTransientFrame;
import com.agentyard.contracts.session.SessionContracts.ChannelIdentityImportTarget;
import com.agentyard.contracts.session.SessionContracts.ChannelInboundSessionTurnRequest;
import com.agentyard.contracts.session.SessionContracts.ExistingSessionImportTarget;
import com.agentyard.contracts.session.SessionContracts.ImportSessionTarget;
import com.agentyard.contracts.session.SessionContracts.SessionMessageInput;
import com.agentyard.contracts.session.SessionContracts.SessionMessageRole;
import com.agentyard.contracts.session.SessionContracts.SessionMessageSender;
import com.agentyard.contracts.session.SessionContracts.SessionMessageSenderType;
import com.agentyard.contracts.session.SessionContracts.TrustedImportSessionTurnMessage;
import com.agentyard.contracts.session.SessionContracts.TrustedImportSessionTurnRequest;
import com.agentyard.contracts.session.SessionContracts.WebIdentityImportTarget;
import com.agentyard.platform.integration.InternalRuntimeAuth;
import com.agentyard.platform.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    @PostMapping("/api/internal/session-runtime/channel-inbound-turns")
    public ApiResponse<?> channelInboundTurn(
        @RequestBody ChannelInboundSessionTurnRequest request,
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        internalRuntimeAuth.requireBearer(authorization);
        return ApiResponse.ok(sessionRuntimeService.channelInboundTurn(request, idempotencyKey));
    }

    @PostMapping("/api/internal/session-runtime/import-turns")
    public ApiResponse<?> importTurn(
        @RequestBody Map<String, Object> payload,
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        internalRuntimeAuth.requireBearer(authorization);
        return ApiResponse.ok(sessionRuntimeService.importTurn(parseTrustedImportTurn(payload), idempotencyKey));
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

    @SuppressWarnings("unchecked")
    private static TrustedImportSessionTurnRequest parseTrustedImportTurn(Map<String, Object> payload) {
        if (payload == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "import turn request is required");
        }
        ImportSessionTarget target = parseImportTarget(requiredMap(payload.get("target"), "target"));
        List<TrustedImportSessionTurnMessage> messages = new ArrayList<>();
        Object rawMessages = payload.get("messages");
        if (rawMessages instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> message = requiredMap(item, "messages[]");
                messages.add(new TrustedImportSessionTurnMessage(
                    optionalText(message.get("importMessageId")),
                    optionalText(message.get("externalMessageId")),
                    parseInstant(message.get("occurredAt")),
                    SessionMessageRole.valueOf(requiredText(message.get("role"), "message.role")),
                    parseSender(requiredMap(message.get("sender"), "message.sender")),
                    parseMessageInput(requiredMap(message.get("message"), "message.message")),
                    objectMap(message.get("metadata"))
                ));
            }
        }
        return new TrustedImportSessionTurnRequest(
            target,
            requiredText(payload.get("turnDedupKey"), "turnDedupKey"),
            requiredText(payload.get("importBatchId"), "importBatchId"),
            requiredText(payload.get("sourceSystem"), "sourceSystem"),
            messages,
            objectMap(payload.get("metadata"))
        );
    }

    private static ImportSessionTarget parseImportTarget(Map<String, Object> target) {
        if (hasText(target.get("sessionId"))) {
            return new ExistingSessionImportTarget(
                requiredText(target.get("sessionId"), "target.sessionId"),
                requiredText(target.get("customerId"), "target.customerId"),
                requiredText(target.get("assistantId"), "target.assistantId")
            );
        }
        if (hasText(target.get("channelProfileId"))) {
            return new ChannelIdentityImportTarget(
                requiredText(target.get("channelProfileId"), "target.channelProfileId"),
                requiredText(target.get("externalConversationId"), "target.externalConversationId"),
                requiredText(target.get("customerId"), "target.customerId"),
                requiredText(target.get("assistantId"), "target.assistantId")
            );
        }
        return new WebIdentityImportTarget(
            requiredText(target.get("customerId"), "target.customerId"),
            requiredText(target.get("assistantId"), "target.assistantId")
        );
    }

    @SuppressWarnings("unchecked")
    private static SessionMessageInput parseMessageInput(Map<String, Object> message) {
        Object blocks = message.get("blocks");
        return new SessionMessageInput(
            blocks instanceof List<?> list ? (List<Object>) list : List.of(),
            objectMap(message.get("metadata"))
        );
    }

    private static SessionMessageSender parseSender(Map<String, Object> sender) {
        return new SessionMessageSender(
            SessionMessageSenderType.valueOf(requiredText(sender.get("senderType"), "sender.senderType")),
            optionalText(sender.get("senderId")),
            requiredText(sender.get("senderName"), "sender.senderName")
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return (Map<String, Object>) map;
    }

    private static Instant parseInstant(Object value) {
        String text = optionalText(value);
        return text == null ? null : Instant.parse(text);
    }

    private static String requiredText(Object value, String field) {
        String text = optionalText(value);
        if (text == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return text;
    }

    private static String optionalText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static boolean hasText(Object value) {
        return optionalText(value) != null;
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
