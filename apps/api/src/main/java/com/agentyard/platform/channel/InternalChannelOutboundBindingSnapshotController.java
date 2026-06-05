package com.agentyard.platform.channel;

import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshotRefreshRequest;
import com.agentyard.platform.integration.InternalRuntimeAuth;
import com.agentyard.platform.shared.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalChannelOutboundBindingSnapshotController {
    private final ChannelBindingSnapshotRefreshCoordinator refreshCoordinator;
    private final InternalRuntimeAuth internalRuntimeAuth;

    public InternalChannelOutboundBindingSnapshotController(
        ChannelBindingSnapshotRefreshCoordinator refreshCoordinator,
        InternalRuntimeAuth internalRuntimeAuth
    ) {
        this.refreshCoordinator = refreshCoordinator;
        this.internalRuntimeAuth = internalRuntimeAuth;
    }

    @PostMapping("/api/internal/channel-outbound/binding-snapshot/refresh")
    public ApiResponse<?> refresh(
        @RequestBody ChannelOutboundBindingSnapshotRefreshRequest request,
        @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        internalRuntimeAuth.requireBearer(authorization);
        refreshCoordinator.requestRefresh(request);
        return ApiResponse.ok(Map.of("accepted", true));
    }
}
