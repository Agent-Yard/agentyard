package com.agentyard.platform.agent;

import com.agentyard.contracts.agent.AgentContracts.ToolRunResume;
import com.agentyard.contracts.agent.ToolRunCallbacks.Callback;
import com.agentyard.platform.integration.InternalRuntimeAuth;
import org.springframework.web.bind.annotation.*;

@RestController
public class InternalToolRunCallbackController {
    private final InternalRuntimeAuth auth;
    private final ExternalToolRunCallbackService callbacks;
    public InternalToolRunCallbackController(InternalRuntimeAuth auth,ExternalToolRunCallbackService callbacks) {
        this.auth=auth;this.callbacks=callbacks;
    }
    @PostMapping("/api/internal/tool-runs/{runId}/external-callback")
    public ToolRunResume callback(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@RequestBody Callback callback) {
        auth.requireBearer(token);return callbacks.receive(runId,callback);
    }
}
