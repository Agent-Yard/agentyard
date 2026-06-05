package com.agentyard.platform.extension;

import com.agentyard.platform.integration.InternalRuntimeAuth;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ExtensionAggregateRegistryValidationController {
    private final ExtensionAggregateRegistryValidationService validationService;
    private final InternalRuntimeAuth internalRuntimeAuth;

    public ExtensionAggregateRegistryValidationController(
        ExtensionAggregateRegistryValidationService validationService,
        InternalRuntimeAuth internalRuntimeAuth
    ) {
        this.validationService = validationService;
        this.internalRuntimeAuth = internalRuntimeAuth;
    }

    @GetMapping("/internal/extension-registry/validation")
    public ResponseEntity<ExtensionRegistryValidation> validation(
        @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        internalRuntimeAuth.requireBearer(authorization);
        ExtensionRegistryValidation validation = validationService.validate();
        HttpStatus status = "READY".equals(validation.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(validation);
    }
}
