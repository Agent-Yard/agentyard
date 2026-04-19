package com.lynxus.platform.session;

import com.lynxus.platform.auth.RequireRuntimeAccess;
import com.lynxus.platform.shared.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.lynxus.platform.session.SessionRuntimeDtos.*;

@RestController
@RequestMapping("/api/session-runtime")
@RequireRuntimeAccess
public class SessionRuntimeController {
    private final SessionRuntimeService sessionRuntimeService;

    public SessionRuntimeController(SessionRuntimeService sessionRuntimeService) {
        this.sessionRuntimeService = sessionRuntimeService;
    }

    @GetMapping("/sessions")
    public ApiResponse<?> sessions() {
        return ApiResponse.ok(sessionRuntimeService.listSessions());
    }

    @PostMapping("/sessions")
    public ApiResponse<?> createSession(@RequestBody CreateSessionRequest request) {
        return ApiResponse.ok(sessionRuntimeService.createSession(request));
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<?> session(@PathVariable String sessionId) {
        return ApiResponse.ok(sessionRuntimeService.getSessionDetail(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ApiResponse<?> sendMessage(@PathVariable String sessionId, @RequestBody SendSessionMessageRequest request) {
        return ApiResponse.ok(sessionRuntimeService.sendMessage(sessionId, request));
    }

    @PostMapping("/sessions/{sessionId}/human-resume")
    public ApiResponse<?> humanResume(@PathVariable String sessionId, @RequestBody HumanResumeRequest request) {
        return ApiResponse.ok(sessionRuntimeService.humanResume(sessionId, request));
    }

    @PostMapping("/sessions/{sessionId}/external-callback")
    public ApiResponse<?> externalCallback(@PathVariable String sessionId, @RequestBody ExternalCallbackRequest request) {
        return ApiResponse.ok(sessionRuntimeService.externalCallback(sessionId, request));
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
