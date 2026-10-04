package com.agentyard.platform.task;

import com.agentyard.contracts.task.TaskContracts.*;
import com.agentyard.platform.integration.InternalRuntimeAuth;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal/task-executions")
public class InternalTaskExecutionController {
    private final TaskExecutionService service; private final InternalRuntimeAuth auth;
    public InternalTaskExecutionController(TaskExecutionService service,InternalRuntimeAuth auth) { this.service=service;this.auth=auth; }
    @GetMapping("/{id}") public ExecutionInput input(@PathVariable String id,@RequestHeader("Authorization") String authorization) { auth.requireBearer(authorization);return service.input(id); }
    @PostMapping("/{id}/progress") public Execution progress(@PathVariable String id,@RequestHeader("Authorization") String authorization,@RequestBody ExecutionProgress request) { auth.requireBearer(authorization);return service.progress(id,request); }
    @PostMapping("/{id}/commands") public CommandResult command(@PathVariable String id,@RequestHeader("Authorization") String authorization,@RequestBody Command request) { auth.requireBearer(authorization);return service.command(id,request); }
}
