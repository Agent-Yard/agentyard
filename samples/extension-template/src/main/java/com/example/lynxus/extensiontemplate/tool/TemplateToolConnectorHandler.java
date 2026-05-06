package com.example.lynxus.extensiontemplate.tool;

import com.example.lynxus.extensiontemplate.protocol.ExtensionApiException;
import com.example.lynxus.extensiontemplate.protocol.ExtensionRequestContext;
import com.lynxus.extension.sdk.generated.protocol.model.RemoteToolInvokeRequest;
import com.lynxus.extension.sdk.generated.protocol.model.ToolInvokeResponse;
import org.springframework.stereotype.Service;

@Service
public final class TemplateToolConnectorHandler implements ToolConnectorHandler {
    @Override
    public ToolInvokeResponse invoke(RemoteToolInvokeRequest request, ExtensionRequestContext context) {
        // Replace this with an idempotent call to your business system.
        // Return status=SUCCEEDED only when output satisfies the Tool operation output schema.
        throw ExtensionApiException.notImplemented("tool invoke");
    }
}
