package com.lynxus.platform.runtime;

import static com.lynxus.platform.runtime.RuntimeDtos.*;

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
public class RuntimeController {
    private final RuntimeService runtimeService;

    public RuntimeController(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @GetMapping("/tasks")
    public ApiResponse<?> tasks() {
        return ApiResponse.ok(runtimeService.listTasks());
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

    @PatchMapping("/workflows/{workflowId}/human-action")
    public ApiResponse<?> humanAction(@PathVariable String workflowId, @RequestBody HumanActionRequest request) {
        return ApiResponse.ok(runtimeService.handleHumanAction(workflowId, request));
    }
}
