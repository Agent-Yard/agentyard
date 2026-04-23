package com.lynxus.platform.channel;

import com.lynxus.contracts.channel.ChannelContracts.CreateChannelAccountRequest;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelAccountRequest;
import com.lynxus.platform.auth.RequireGovernanceAccess;
import com.lynxus.platform.auth.RequireGovernanceWrite;
import com.lynxus.platform.shared.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/channel-admin/accounts")
@RequireGovernanceAccess
public class ChannelAdminController {
    private final ChannelAdminService channelAdminService;

    public ChannelAdminController(ChannelAdminService channelAdminService) {
        this.channelAdminService = channelAdminService;
    }

    @GetMapping
    public ApiResponse<?> accounts() {
        return ApiResponse.ok(channelAdminService.listAccounts());
    }

    @PostMapping
    @RequireGovernanceWrite
    public ApiResponse<?> createAccount(@RequestBody CreateChannelAccountRequest request) {
        return ApiResponse.ok(channelAdminService.createAccount(request));
    }

    @GetMapping("/{accountId}")
    public ApiResponse<?> account(@PathVariable String accountId) {
        return ApiResponse.ok(channelAdminService.getAccount(accountId));
    }

    @PutMapping("/{accountId}")
    @RequireGovernanceWrite
    public ApiResponse<?> updateAccount(@PathVariable String accountId, @RequestBody UpdateChannelAccountRequest request) {
        return ApiResponse.ok(channelAdminService.updateAccount(accountId, request));
    }

    @GetMapping("/{accountId}/bindings")
    public ApiResponse<?> bindings(@PathVariable String accountId) {
        return ApiResponse.ok(channelAdminService.listBindings(accountId));
    }

    @GetMapping("/{accountId}/inbound-events")
    public ApiResponse<?> inboundEvents(@PathVariable String accountId) {
        return ApiResponse.ok(channelAdminService.listInboundEvents(accountId));
    }

    @GetMapping("/{accountId}/outbound-deliveries")
    public ApiResponse<?> outboundDeliveries(@PathVariable String accountId) {
        return ApiResponse.ok(channelAdminService.listOutboundDeliveries(accountId));
    }
}
