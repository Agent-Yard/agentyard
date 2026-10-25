package com.agentyard.platform.agent;

import com.agentyard.contracts.agent.AgentContracts.*;
import com.agentyard.platform.integration.InternalRuntimeAuth;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal")
public class InternalAgentController {
    private final AgentService agents;
    private final AgentToolService tools;
    private final ToolRunService toolRuns;
    private final InternalRuntimeAuth auth;
    public InternalAgentController(AgentService agents,AgentToolService tools,ToolRunService toolRuns,InternalRuntimeAuth auth) {
        this.agents=agents;this.tools=tools;this.toolRuns=toolRuns;this.auth=auth;
    }
    @GetMapping("/activations/{id}/runtime") public ActivationRuntime runtime(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id) {
        auth.requireBearer(token);return agents.runtime(id);
    }
    @PostMapping("/activations/{id}/tools") public ToolResult tool(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id,@RequestBody ToolInvocation call) {
        auth.requireBearer(token);return tools.invoke(id,call);
    }
    @PostMapping("/activations/{id}/complete") public void complete(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id,@RequestBody ActivationOutcome outcome) {
        auth.requireBearer(token);agents.complete(id,outcome);
    }
    @GetMapping("/tool-runs/{id}/runtime") public ToolRunRuntime toolRuntime(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id) {
        auth.requireBearer(token);return toolRuns.runtime(id);
    }
    @PostMapping("/tool-runs/{id}/authorize-node") public ToolRunRuntime authorizeNode(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id,@RequestBody ToolRunNodeAuthorization node) {
        auth.requireBearer(token);return toolRuns.authorizeNode(id,node);
    }
    @PostMapping("/tool-runs/{id}/progress") public void progress(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id,@RequestBody ToolRunProgress progress) {
        auth.requireBearer(token);toolRuns.progress(id,progress);
    }
    @PostMapping("/tool-runs/{id}/signals/{signalId}/ack") public void acknowledge(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id,@PathVariable String signalId) {
        auth.requireBearer(token);toolRuns.acknowledge(id,signalId);
    }
}
