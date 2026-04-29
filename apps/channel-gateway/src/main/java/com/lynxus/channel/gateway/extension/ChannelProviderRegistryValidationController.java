package com.lynxus.channel.gateway.extension;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/extension-registry/channel-providers")
public final class ChannelProviderRegistryValidationController {
    private final ChannelProviderRegistryValidationService validationService;

    public ChannelProviderRegistryValidationController(ChannelProviderRegistryValidationService validationService) {
        this.validationService = validationService;
    }

    @GetMapping("/validation")
    public ResponseEntity<ChannelProviderRegistryValidation> validation() {
        ChannelProviderRegistryValidation validation = validationService.validate();
        HttpStatus status = "READY".equals(validation.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(validation);
    }
}
