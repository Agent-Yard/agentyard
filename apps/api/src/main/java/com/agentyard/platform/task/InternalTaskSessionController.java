package com.agentyard.platform.task;

import com.agentyard.platform.integration.InternalRuntimeAuth;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal/task-sessions")
public class InternalTaskSessionController {
    private final TaskSessionBridge bridge; private final InternalRuntimeAuth auth;
    public InternalTaskSessionController(TaskSessionBridge bridge,InternalRuntimeAuth auth) {this.bridge=bridge;this.auth=auth;}
    @GetMapping("/{sessionId}") public Object tasks(@PathVariable String sessionId,@RequestParam String agentId,@RequestHeader("Authorization") String token) {auth.requireBearer(token);return bridge.tasks(sessionId,agentId);}
    @GetMapping("/{sessionId}/tasks/{taskId}/context") public Object context(@PathVariable String sessionId,@PathVariable String taskId,@RequestParam String agentId,@RequestParam(required=false) String cursor,@RequestHeader("Authorization") String token) {auth.requireBearer(token);return bridge.context(sessionId,agentId,taskId,cursor);}
}
