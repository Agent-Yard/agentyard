package com.agentyard.platform.task;

import com.agentyard.contracts.task.TaskContracts.*;
import com.agentyard.platform.auth.RequireRuntimeAccess;
import com.agentyard.platform.shared.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/collaboration")
@RequireRuntimeAccess
public class CollaborationController {
    private final CollaborationService service; private final TaskCommandService commands;
    public CollaborationController(CollaborationService service,TaskCommandService commands) { this.service=service;this.commands=commands; }
    @GetMapping("/actors/me") public ApiResponse<?> me() { return ApiResponse.ok(service.currentActor()); }
    @GetMapping("/actors") public ApiResponse<?> actors(@RequestParam(defaultValue="") String query) { return ApiResponse.ok(service.actors(query)); }
    @GetMapping("/organizations") public ApiResponse<?> organizations() { return ApiResponse.ok(service.organizations()); }
    @PostMapping("/organizations") public ApiResponse<?> createOrganization(@RequestBody CreateOrganization request) { return ApiResponse.ok(service.createOrganization(request)); }
    @GetMapping("/organizations/{id}") public ApiResponse<?> organization(@PathVariable String id) { return ApiResponse.ok(service.organization(id)); }
    @PutMapping("/organizations/{id}") public ApiResponse<?> updateOrganization(@PathVariable String id,@RequestBody UpdateOrganization request) { return ApiResponse.ok(service.updateOrganization(id,request)); }
    @PutMapping("/organizations/{id}/members") public ApiResponse<?> member(@PathVariable String id,@RequestBody SetMember request) { return ApiResponse.ok(service.setMember(id,request)); }
    @GetMapping("/tasks") public ApiResponse<?> tasks(@RequestParam(required=false) String cursor,@RequestParam(required=false) TaskStatus status,@RequestParam(required=false) String organizationId,@RequestParam(defaultValue="50") int limit) { return ApiResponse.ok(service.tasks(cursor,status,organizationId,limit)); }
    @PostMapping("/tasks") public ApiResponse<?> createTask(@RequestBody CreateTask request) { return ApiResponse.ok(service.createTask(request)); }
    @GetMapping("/tasks/{id}") public ApiResponse<?> task(@PathVariable String id) { return ApiResponse.ok(service.detail(id)); }
    @PostMapping("/tasks/{id}/commands") public ApiResponse<?> command(@PathVariable String id,@RequestBody Command request) { return ApiResponse.ok(commands.command(id,request)); }
    @GetMapping("/tasks/{id}/context") public ApiResponse<?> context(@PathVariable String id,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="100") int limit) { return ApiResponse.ok(service.context(id,cursor,limit)); }
    @GetMapping("/tasks/{id}/context/{entryId}/versions") public ApiResponse<?> versions(@PathVariable String id,@PathVariable String entryId) { return ApiResponse.ok(service.versions(id,entryId)); }
    @GetMapping("/tasks/{id}/events") public ApiResponse<?> events(@PathVariable String id,@RequestParam(defaultValue="0") long afterRevision,@RequestParam(defaultValue="100") int limit) { return ApiResponse.ok(service.events(id,afterRevision,limit)); }
    @GetMapping("/tasks/{id}/decisions") public ApiResponse<?> decisions(@PathVariable String id,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="100") int limit) { return ApiResponse.ok(service.decisions(id,cursor,limit)); }
    @GetMapping("/sessions/{sessionId}/tasks") public ApiResponse<?> sessionTasks(@PathVariable String sessionId) { return ApiResponse.ok(service.sessionTasks(sessionId)); }
}
