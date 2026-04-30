package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.shared.ApiResponse;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobConfigWriteRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelTemplateBindingWriteRequest;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelProfileInternalRequest;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelProfileInternalRequest;
import org.springframework.beans.factory.annotation.Autowired;
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
@RequestMapping("/internal/channel-admin/profiles")
public class InternalChannelAdminController {
    private final ChannelAdminService channelAdminService;
    private final ProviderJobExecutionService providerJobExecutionService;

    @Autowired
    public InternalChannelAdminController(
        ChannelAdminService channelAdminService,
        ProviderJobExecutionService providerJobExecutionService
    ) {
        this.channelAdminService = channelAdminService;
        this.providerJobExecutionService = providerJobExecutionService;
    }

    public InternalChannelAdminController(ChannelAdminService channelAdminService) {
        this(channelAdminService, null);
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

    @DeleteMapping("/{channelProfileId}")
    public ApiResponse<?> deleteProfile(@PathVariable String channelProfileId, @RequestParam Long expectedRevision) {
        return ApiResponse.ok(channelAdminService.deleteProfile(channelProfileId, expectedRevision));
    }

    @GetMapping("/{channelProfileId}/bindings")
    public ApiResponse<?> bindings(@PathVariable String channelProfileId) {
        return ApiResponse.ok(channelAdminService.listBindings(channelProfileId));
    }

    @GetMapping("/bindings/by-session/{sessionId}")
    public ApiResponse<?> bindingBySession(@PathVariable String sessionId) {
        return ApiResponse.ok(channelAdminService.getBindingBySession(sessionId));
    }

    @GetMapping("/{channelProfileId}/inbound-events")
    public ApiResponse<?> inboundEvents(@PathVariable String channelProfileId) {
        return ApiResponse.ok(channelAdminService.listInboundEvents(channelProfileId));
    }

    @GetMapping("/{channelProfileId}/outbound-deliveries")
    public ApiResponse<?> outboundDeliveries(@PathVariable String channelProfileId) {
        return ApiResponse.ok(channelAdminService.listOutboundDeliveries(channelProfileId));
    }

    @GetMapping("/{channelProfileId}/template-bindings")
    public ApiResponse<?> templateBindings(@PathVariable String channelProfileId) {
        return ApiResponse.ok(channelAdminService.listTemplateBindings(channelProfileId));
    }

    @PutMapping("/{channelProfileId}/template-bindings/{assistantId}/{messageType}/{messageSubtype}/{messageVersion}")
    public ApiResponse<?> upsertTemplateBinding(
        @PathVariable String channelProfileId,
        @PathVariable String assistantId,
        @PathVariable String messageType,
        @PathVariable String messageSubtype,
        @PathVariable String messageVersion,
        @RequestBody ChannelTemplateBindingWriteRequest request
    ) {
        return ApiResponse.ok(channelAdminService.upsertTemplateBinding(
            channelProfileId,
            assistantId,
            messageType,
            messageSubtype,
            messageVersion,
            request
        ));
    }

    @DeleteMapping("/{channelProfileId}/template-bindings/{assistantId}/{messageType}/{messageSubtype}/{messageVersion}")
    public ApiResponse<?> deleteTemplateBinding(
        @PathVariable String channelProfileId,
        @PathVariable String assistantId,
        @PathVariable String messageType,
        @PathVariable String messageSubtype,
        @PathVariable String messageVersion,
        @RequestParam Long expectedRevision
    ) {
        return ApiResponse.ok(channelAdminService.deleteTemplateBinding(
            channelProfileId,
            assistantId,
            messageType,
            messageSubtype,
            messageVersion,
            expectedRevision
        ));
    }

    @GetMapping("/{channelProfileId}/jobs")
    public ApiResponse<?> jobs(@PathVariable String channelProfileId) {
        return ApiResponse.ok(channelAdminService.listJobs(channelProfileId));
    }

    @PutMapping("/{channelProfileId}/jobs/{jobType}")
    public ApiResponse<?> upsertJob(
        @PathVariable String channelProfileId,
        @PathVariable String jobType,
        @RequestBody ChannelProviderJobConfigWriteRequest request
    ) {
        return ApiResponse.ok(channelAdminService.upsertJob(channelProfileId, jobType, request));
    }

    @DeleteMapping("/{channelProfileId}/jobs/{jobType}")
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
    public ApiResponse<?> runJob(@PathVariable String channelProfileId, @PathVariable String jobType) {
        return ApiResponse.ok(providerJobExecutionService.runManual(channelProfileId, jobType));
    }
}
