package com.example.lynxus.extensiontemplate.channel;

import com.example.lynxus.extensiontemplate.protocol.ExtensionRequestContext;
import com.lynxus.extension.sdk.generated.protocol.model.ChannelRunJobRequest;
import com.lynxus.extension.sdk.generated.protocol.model.ChannelRunJobResponse;

public interface ChannelProviderHandler {
    ChannelRunJobResponse runJob(ChannelRunJobRequest request, ExtensionRequestContext context);
}
