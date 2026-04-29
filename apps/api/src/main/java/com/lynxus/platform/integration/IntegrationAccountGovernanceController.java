package com.lynxus.platform.integration;

import com.lynxus.platform.auth.RequireGovernanceAccess;
import com.lynxus.platform.auth.RequireGovernanceWrite;
import com.lynxus.platform.integration.IntegrationDtos.CreateIntegrationAccountRequest;
import com.lynxus.platform.integration.IntegrationDtos.UpdateIntegrationAccountStatusRequest;
import com.lynxus.platform.integration.IntegrationDtos.UpdateIntegrationAccountRequest;
import com.lynxus.platform.shared.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integration/accounts")
@RequireGovernanceAccess
public class IntegrationAccountGovernanceController {
    private final IntegrationAccountService service;

    public IntegrationAccountGovernanceController(IntegrationAccountService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<?> accounts() {
        return ApiResponse.ok(service.listAccounts());
    }

    @GetMapping("/{accountId}")
    public ApiResponse<?> account(@PathVariable String accountId) {
        return ApiResponse.ok(service.getAccount(accountId));
    }

    @PostMapping
    @RequireGovernanceWrite
    public ApiResponse<?> createAccount(@RequestBody CreateIntegrationAccountRequest request) {
        return ApiResponse.ok(service.createAccount(request));
    }

    @PutMapping("/{accountId}")
    @RequireGovernanceWrite
    public ApiResponse<?> updateAccount(@PathVariable String accountId, @RequestBody UpdateIntegrationAccountRequest request) {
        return ApiResponse.ok(service.updateAccount(accountId, request));
    }

    @PutMapping("/{accountId}/status")
    @RequireGovernanceWrite
    public ApiResponse<?> updateAccountStatus(
        @PathVariable String accountId,
        @RequestBody UpdateIntegrationAccountStatusRequest request
    ) {
        return ApiResponse.ok(service.updateAccountStatus(accountId, request));
    }

    @PostMapping("/{accountId}/archive")
    @RequireGovernanceWrite
    public ApiResponse<?> archiveAccount(@PathVariable String accountId) {
        return ApiResponse.ok(service.archiveAccount(accountId));
    }
}
