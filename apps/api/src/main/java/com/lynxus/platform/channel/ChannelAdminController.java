package com.lynxus.platform.channel;

import com.lynxus.contracts.channel.ChannelContracts.CreateChannelProfileRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobConfigWriteRequest;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelProfileRequest;
import com.lynxus.platform.auth.RequireGovernanceAccess;
import com.lynxus.platform.auth.RequireGovernanceWrite;
import com.lynxus.platform.shared.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/channel-admin/profiles")
@RequireGovernanceAccess
public class ChannelAdminController {
    private final ChannelAdminService channelAdminService;

    public ChannelAdminController(ChannelAdminService channelAdminService) {
        this.channelAdminService = channelAdminService;
    }

    @GetMapping
    public ApiResponse<?> profiles() {
        return ApiResponse.ok(channelAdminService.listProfiles());
    }

    @PostMapping
    @RequireGovernanceWrite
    public ApiResponse<?> createProfile(@RequestBody CreateChannelProfileRequest request) {
        return ApiResponse.ok(channelAdminService.createProfile(request));
    }

    @GetMapping("/{channelProfileId}")
    public ApiResponse<?> profile(@PathVariable String channelProfileId) {
        return ApiResponse.ok(channelAdminService.getProfile(channelProfileId));
    }

    @PutMapping("/{channelProfileId}")
    @RequireGovernanceWrite
    public ApiResponse<?> updateProfile(@PathVariable String channelProfileId, @RequestBody UpdateChannelProfileRequest request) {
        return ApiResponse.ok(channelAdminService.updateProfile(channelProfileId, request));
    }

    @DeleteMapping("/{channelProfileId}")
    @RequireGovernanceWrite
    public ApiResponse<?> deleteProfile(@PathVariable String channelProfileId, @RequestParam Long expectedRevision) {
        return ApiResponse.ok(channelAdminService.deleteProfile(channelProfileId, expectedRevision));
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

    @GetMapping("/{channelProfileId}/jobs")
    public ApiResponse<?> jobs(@PathVariable String channelProfileId) {
        return ApiResponse.ok(channelAdminService.listJobs(channelProfileId));
    }

    @PutMapping("/{channelProfileId}/jobs/{jobType}")
    @RequireGovernanceWrite
    public ApiResponse<?> upsertJob(
        @PathVariable String channelProfileId,
        @PathVariable String jobType,
        @RequestBody ChannelProviderJobConfigWriteRequest request
    ) {
        return ApiResponse.ok(channelAdminService.upsertJob(channelProfileId, jobType, request));
    }

    @DeleteMapping("/{channelProfileId}/jobs/{jobType}")
    @RequireGovernanceWrite
    public ApiResponse<?> deleteJob(
        @PathVariable String channelProfileId,
        @PathVariable String jobType,
        @RequestParam Long expectedRevision
    ) {
        return ApiResponse.ok(channelAdminService.deleteJob(channelProfileId, jobType, expectedRevision));
    }

    @GetMapping("/{channelProfileId}/jobs/{jobType}/runs")
    public ApiResponse<?> jobRuns(@PathVariable String channelProfileId, @PathVariable String jobType) {
        return ApiResponse.ok(channelAdminService.listJobRuns(channelProfileId, jobType));
    }

    @PostMapping("/{channelProfileId}/jobs/{jobType}/runs")
    @RequireGovernanceWrite
    public ApiResponse<?> runJob(@PathVariable String channelProfileId, @PathVariable String jobType) {
        return ApiResponse.ok(channelAdminService.runJob(channelProfileId, jobType));
    }
}
