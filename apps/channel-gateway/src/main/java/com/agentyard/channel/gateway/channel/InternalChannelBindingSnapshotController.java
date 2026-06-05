package com.agentyard.channel.gateway.channel;

import com.agentyard.channel.gateway.shared.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/channel-admin/bindings")
public class InternalChannelBindingSnapshotController {
    private final ChannelAdminService channelAdminService;

    public InternalChannelBindingSnapshotController(ChannelAdminService channelAdminService) {
        this.channelAdminService = channelAdminService;
    }

    @GetMapping
    public ApiResponse<?> bindings(
        @RequestParam(required = false) Instant updatedAfter,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.ok(channelAdminService.listBindingSnapshots(updatedAfter, cursor, limit));
    }

    @GetMapping("/by-session/{sessionId}")
    public ApiResponse<?> bindingBySession(@PathVariable String sessionId) {
        return ApiResponse.ok(channelAdminService.getActiveBindingSnapshotBySession(sessionId));
    }
}
