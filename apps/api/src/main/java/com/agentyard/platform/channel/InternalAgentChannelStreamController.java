package com.agentyard.platform.channel;

import com.agentyard.platform.integration.InternalRuntimeAuth;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class InternalAgentChannelStreamController {
    private final InternalRuntimeAuth auth;
    private final AgentChannelActivityRelay relay;
    public InternalAgentChannelStreamController(InternalRuntimeAuth auth,AgentChannelActivityRelay relay) {
        this.auth=auth;this.relay=relay;
    }
    @PostMapping("/api/internal/activations/{activationId}/stream")
    public Map<String,Boolean> stream(@RequestHeader(value="Authorization",required=false) String authorization,
        @PathVariable String activationId,@RequestBody AgentChannelActivityRelay.RuntimeFrame frame) {
        auth.requireBearer(authorization);
        return Map.of("accepted",relay.accept(activationId,frame));
    }
}
