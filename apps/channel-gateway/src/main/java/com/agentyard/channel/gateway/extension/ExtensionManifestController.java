package com.agentyard.channel.gateway.extension;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ExtensionManifestController {
    private final ChannelGatewayDescriptorProvider descriptorProvider;

    public ExtensionManifestController(ChannelGatewayDescriptorProvider descriptorProvider) {
        this.descriptorProvider = descriptorProvider;
    }

    @GetMapping(value = "/extension/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> manifest() {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore())
            .body(descriptorProvider.canonicalManifestBytes());
    }
}
