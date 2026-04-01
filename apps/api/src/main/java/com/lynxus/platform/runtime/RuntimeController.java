package com.lynxus.platform.runtime;

import static com.lynxus.platform.runtime.RuntimeDtos.*;

import com.lynxus.platform.auth.RequireRuntimeAccess;
import com.lynxus.platform.shared.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequireRuntimeAccess
public class RuntimeController {
    private final RuntimeService runtimeService;

    public RuntimeController(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @GetMapping("/tasks")
    public ApiResponse<?> tasks() {
        return ApiResponse.ok(runtimeService.listTasks());
    }

    @GetMapping("/runtime/sessions")
    public ApiResponse<?> sessions() {
        return ApiResponse.ok(runtimeService.listSessions());
    }

    @PostMapping("/runtime/sessions")
    public ApiResponse<?> createSession(@RequestBody CreateConversationSessionRequest request) {
        return ApiResponse.ok(runtimeService.createSession(request));
    }

    @GetMapping("/runtime/sessions/{sessionId}")
    public ApiResponse<?> session(@PathVariable String sessionId) {
        return ApiResponse.ok(runtimeService.getSession(sessionId));
    }

    @GetMapping("/runtime/interactions/{interactionTaskId}")
    public ApiResponse<?> interaction(@PathVariable String interactionTaskId) {
        return ApiResponse.ok(runtimeService.getExternalInteractionTask(interactionTaskId));
    }

    @PostMapping("/runtime/interactions/{interactionTaskId}/return")
    public ApiResponse<?> acknowledgeInteractionReturn(
        @PathVariable String interactionTaskId,
        @RequestBody ExternalInteractionReturnRequest request
    ) {
        return ApiResponse.ok(runtimeService.acknowledgeExternalInteractionReturn(interactionTaskId, request));
    }

    @PostMapping("/runtime/interactions/callbacks/{provider}")
    public ApiResponse<?> interactionCallback(
        @PathVariable String provider,
        @RequestBody ExternalInteractionCallbackRequest request
    ) {
        return ApiResponse.ok(runtimeService.receiveExternalInteractionCallback(provider, request));
    }

    @PostMapping("/runtime/sessions/{sessionId}/messages")
    public ApiResponse<?> sendMessage(@PathVariable String sessionId, @RequestBody ConversationMessageRequest request) {
        return ApiResponse.ok(runtimeService.sendMessage(sessionId, request));
    }

    @PostMapping("/tasks")
    public ApiResponse<?> launchTask(@RequestBody TaskLaunchRequest request) {
        return ApiResponse.ok(runtimeService.launchTask(request));
    }

    @GetMapping("/workflows")
    public ApiResponse<?> workflows() {
        return ApiResponse.ok(runtimeService.listWorkflows());
    }

    @GetMapping("/workflows/{workflowId}")
    public ApiResponse<?> workflow(@PathVariable String workflowId) {
        return ApiResponse.ok(runtimeService.getWorkflow(workflowId));
    }

    @PatchMapping("/workflows/{workflowId}/resume")
    public ApiResponse<?> resume(@PathVariable String workflowId, @RequestBody ResumeActionRequest request) {
        return ApiResponse.ok(runtimeService.handleResumeAction(workflowId, request));
    }
}
