package com.example.lynxus.extensiontemplate.extension;

import com.lynxus.extension.sdk.generated.protocol.model.ExtensionHealth;
import com.lynxus.extension.sdk.generated.protocol.model.HealthProbe;
import com.lynxus.extension.sdk.protocol.LynxusExtensionProtocol;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ExtensionController {
    private final ExtensionManifestFactory manifestFactory;

    public ExtensionController(ExtensionManifestFactory manifestFactory) {
        this.manifestFactory = manifestFactory;
    }

    @GetMapping(value = LynxusExtensionProtocol.EXTENSION_MANIFEST_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> manifest() {
        return manifestFactory.manifest();
    }

    @GetMapping(value = LynxusExtensionProtocol.EXTENSION_HEALTH_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ExtensionHealth extensionHealth() {
        return new ExtensionHealth()
            .status(ExtensionHealth.StatusEnum.UP)
            .details(Map.of("checkedAt", OffsetDateTime.now().toString()));
    }

    @GetMapping(value = LynxusExtensionProtocol.HEALTH_LIVE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public HealthProbe live() {
        return new HealthProbe().status(HealthProbe.StatusEnum.UP);
    }

    @GetMapping(value = LynxusExtensionProtocol.HEALTH_READY_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public HealthProbe ready() {
        return new HealthProbe().status(HealthProbe.StatusEnum.UP);
    }
}
