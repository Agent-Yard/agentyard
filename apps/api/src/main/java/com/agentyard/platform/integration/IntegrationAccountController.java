package com.agentyard.platform.integration;

import com.agentyard.platform.shared.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IntegrationAccountController {
    private final IntegrationAccountService service;
    private final InternalRuntimeAuth internalRuntimeAuth;

    public IntegrationAccountController(IntegrationAccountService service, InternalRuntimeAuth internalRuntimeAuth) {
        this.service = service;
        this.internalRuntimeAuth = internalRuntimeAuth;
    }

    @GetMapping("/api/internal/integration/accounts/{accountId}/credential")
    public ApiResponse<?> runtimeCredential(
        @PathVariable String accountId,
        @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        internalRuntimeAuth.requireBearer(authorization);
        return ApiResponse.ok(service.runtimeCredential(accountId));
    }
}
