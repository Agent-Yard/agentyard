package com.lynxus.platform.session;

import com.lynxus.contracts.session.SessionContracts.StreamVisibility;
import com.lynxus.contracts.session.SessionContracts.SendSessionTurnRequest;
import com.lynxus.platform.auth.AuthModels.Role;
import com.lynxus.platform.auth.CurrentUserResolver;
import com.lynxus.platform.auth.RequireRuntimeAccess;
import com.lynxus.platform.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static com.lynxus.platform.session.SessionRuntimeDtos.*;

@RestController
@RequestMapping("/api/session-runtime")
@RequireRuntimeAccess
public class SessionRuntimeController {
    private final SessionRuntimeService sessionRuntimeService;
    private final SessionRuntimeStreamService sessionRuntimeStreamService;
    private final CurrentUserResolver currentUserResolver;

    public SessionRuntimeController(
        SessionRuntimeService sessionRuntimeService,
        SessionRuntimeStreamService sessionRuntimeStreamService,
        CurrentUserResolver currentUserResolver
    ) {
        this.sessionRuntimeService = sessionRuntimeService;
        this.sessionRuntimeStreamService = sessionRuntimeStreamService;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping("/sessions")
    public ApiResponse<?> sessions() {
        return ApiResponse.ok(sessionRuntimeService.listSessions());
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<?> session(@PathVariable String sessionId) {
        return ApiResponse.ok(sessionRuntimeService.getSessionDetail(sessionId));
    }

    @GetMapping("/sessions/{sessionId}/stream")
    public SseEmitter streamSession(
        @PathVariable String sessionId,
        @RequestParam(value = "lastEventId", required = false) String lastEventIdParam,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
        HttpServletRequest request
    ) {
        String lastEventId = lastEventIdHeader != null && !lastEventIdHeader.isBlank() ? lastEventIdHeader : lastEventIdParam;
        return sessionRuntimeStreamService.connect(
            sessionId,
            lastEventId,
            request.getUserPrincipal() == null ? "anonymous" : request.getUserPrincipal().getName(),
            streamVisibility()
        );
    }

    private Set<StreamVisibility> streamVisibility() {
        Set<Role> roles = Set.copyOf(currentUserResolver.resolveCurrentUser().roles());
        if (roles.contains(Role.PLATFORM_ADMIN) || roles.contains(Role.DOMAIN_ADMIN) || roles.contains(Role.DEVELOPER)) {
            return EnumSet.of(
                StreamVisibility.CUSTOMER,
                StreamVisibility.OPERATOR,
                StreamVisibility.DEVELOPER
            );
        }
        return EnumSet.of(StreamVisibility.CUSTOMER);
    }

    @GetMapping("/sessions/{sessionId}/privacy-mapping-summary")
    public ApiResponse<?> privacyMappingSummary(@PathVariable String sessionId) {
        return ApiResponse.ok(sessionRuntimeService.getPrivacyMappingSummary(sessionId));
    }

    @PostMapping("/turns")
    public ApiResponse<?> sendTurn(
        @RequestBody SendSessionTurnRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(sessionRuntimeService.sendTurn(request, idempotencyKey));
    }

    @PostMapping("/sessions/{sessionId}/human-resume")
    public ApiResponse<?> humanResume(@PathVariable String sessionId, @RequestBody HumanResumeRequest request) {
        return ApiResponse.ok(sessionRuntimeService.humanResume(sessionId, request));
    }

    @PostMapping("/sessions/{sessionId}/external-callback")
    public ApiResponse<?> externalCallback(
        @PathVariable String sessionId,
        @RequestBody ExternalCallbackRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(sessionRuntimeService.externalCallback(sessionId, request, idempotencyKey));
    }

    @PostMapping("/sessions/{sessionId}/handoff/end")
    public ApiResponse<?> endHumanHandoff(@PathVariable String sessionId) {
        return ApiResponse.ok(sessionRuntimeService.endHumanHandoff(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/human-reply")
    public ApiResponse<?> humanReply(@PathVariable String sessionId, @RequestBody HumanOperatorReplyRequest request) {
        return ApiResponse.ok(sessionRuntimeService.humanOperatorReply(sessionId, request));
    }
}
