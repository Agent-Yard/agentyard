package com.example.lynxus.extensiontemplate.channel;

import com.example.lynxus.extensiontemplate.protocol.ExtensionRequestContext;
import com.lynxus.extension.sdk.generated.protocol.model.ChannelRunJobRequest;
import com.lynxus.extension.sdk.generated.protocol.model.ChannelRunJobResponse;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public final class TemplateChannelProviderHandler implements ChannelProviderHandler {
    @Override
    public ChannelRunJobResponse runJob(ChannelRunJobRequest request, ExtensionRequestContext context) {
        // Replace this no-op with provider pull/sync logic when jobDefinitions are kept in the manifest.
        // Pull-style providers may return normalized inbound events in events; webhook-only providers
        // should remove jobDefinitions and endpoints.runJob from extension-manifest.json.
        return new ChannelRunJobResponse()
            .status(ChannelRunJobResponse.StatusEnum.NOOP)
            .nextCursor(null)
            .events(List.of())
            .metadata(Map.of());
    }
}
