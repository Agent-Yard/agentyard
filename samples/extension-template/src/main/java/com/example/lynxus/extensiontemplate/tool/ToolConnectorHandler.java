package com.example.lynxus.extensiontemplate.tool;

import com.example.lynxus.extensiontemplate.protocol.ExtensionRequestContext;
import com.lynxus.extension.sdk.generated.protocol.model.RemoteToolInvokeRequest;
import com.lynxus.extension.sdk.generated.protocol.model.ToolInvokeResponse;

public interface ToolConnectorHandler {
    ToolInvokeResponse invoke(RemoteToolInvokeRequest request, ExtensionRequestContext context);
}
