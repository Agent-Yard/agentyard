package com.lynxus.platform.extension;

import com.lynxus.platform.shared.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/extensions")
public final class ExtensionDefinitionController {
    private final ExtensionDefinitionService service;

    public ExtensionDefinitionController(ExtensionDefinitionService service) {
        this.service = service;
    }

    @GetMapping("/channel-providers")
    public ApiResponse<?> channelProviders() {
        return ApiResponse.ok(service.channelProviders());
    }

    @GetMapping("/tool-connectors")
    public ApiResponse<?> toolConnectors() {
        return ApiResponse.ok(service.toolConnectors());
    }
}
