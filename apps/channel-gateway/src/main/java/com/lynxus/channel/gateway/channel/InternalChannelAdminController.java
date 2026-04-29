package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.shared.ApiResponse;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelProfileInternalRequest;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelProfileInternalRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/channel-admin/profiles")
public class InternalChannelAdminController {
    private final ChannelAdminService channelAdminService;

    public InternalChannelAdminController(ChannelAdminService channelAdminService) {
        this.channelAdminService = channelAdminService;
    }

    @GetMapping
    public ApiResponse<?> profiles() {
        return ApiResponse.ok(channelAdminService.listProfiles());
    }

    @PostMapping
    public ApiResponse<?> createProfile(@RequestBody CreateChannelProfileInternalRequest request) {
        return ApiResponse.ok(channelAdminService.createProfile(request));
    }

    @GetMapping("/{channelProfileId}")
    public ApiResponse<?> profile(@PathVariable String channelProfileId) {
        return ApiResponse.ok(channelAdminService.getProfile(channelProfileId));
    }

    @PutMapping("/{channelProfileId}")
    public ApiResponse<?> updateProfile(@PathVariable String channelProfileId, @RequestBody UpdateChannelProfileInternalRequest request) {
        return ApiResponse.ok(channelAdminService.updateProfile(channelProfileId, request));
    }

    @GetMapping("/{channelProfileId}/bindings")
    public ApiResponse<?> bindings(@PathVariable String channelProfileId) {
        return ApiResponse.ok(channelAdminService.listBindings(channelProfileId));
    }

    @GetMapping("/{channelProfileId}/inbound-events")
    public ApiResponse<?> inboundEvents(@PathVariable String channelProfileId) {
        return ApiResponse.ok(channelAdminService.listInboundEvents(channelProfileId));
    }

    @GetMapping("/{channelProfileId}/outbound-deliveries")
    public ApiResponse<?> outboundDeliveries(@PathVariable String channelProfileId) {
        return ApiResponse.ok(channelAdminService.listOutboundDeliveries(channelProfileId));
    }
}
