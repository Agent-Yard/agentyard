package com.example.lynxus.extensiontemplate.tool;

import com.example.lynxus.extensiontemplate.protocol.ExtensionRequestContext;
import com.example.lynxus.extensiontemplate.protocol.ProtocolHeaderExtractor;
import com.lynxus.extension.sdk.generated.protocol.model.RemoteToolInvokeRequest;
import com.lynxus.extension.sdk.generated.protocol.model.ToolInvokeResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ToolConnectorController {
    public static final String INVOKE_PATH = "/tools/invoke";

    private final ProtocolHeaderExtractor headerExtractor;
    private final ToolConnectorHandler handler;

    public ToolConnectorController(ProtocolHeaderExtractor headerExtractor, ToolConnectorHandler handler) {
        this.headerExtractor = headerExtractor;
        this.handler = handler;
    }

    @PostMapping(
        value = INVOKE_PATH,
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ToolInvokeResponse invoke(
        @RequestBody RemoteToolInvokeRequest requestBody,
        HttpServletRequest request
    ) {
        ExtensionRequestContext context = headerExtractor.descriptorContext(request);
        return handler.invoke(requestBody, context);
    }
}
