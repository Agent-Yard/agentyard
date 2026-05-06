package com.example.lynxus.extensiontemplate.channel;

import com.example.lynxus.extensiontemplate.protocol.ExtensionRequestContext;
import com.example.lynxus.extensiontemplate.protocol.ProtocolHeaderExtractor;
import com.lynxus.extension.sdk.generated.protocol.model.ChannelRunJobRequest;
import com.lynxus.extension.sdk.generated.protocol.model.ChannelRunJobResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ChannelProviderController {
    public static final String RUN_JOB_PATH = "/channel/run-job";

    private final ProtocolHeaderExtractor headerExtractor;
    private final ChannelProviderHandler handler;

    public ChannelProviderController(ProtocolHeaderExtractor headerExtractor, ChannelProviderHandler handler) {
        this.headerExtractor = headerExtractor;
        this.handler = handler;
    }

    @PostMapping(
        value = RUN_JOB_PATH,
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ChannelRunJobResponse runJob(
        @RequestBody ChannelRunJobRequest requestBody,
        HttpServletRequest request
    ) {
        ExtensionRequestContext context = headerExtractor.descriptorContext(request);
        return handler.runJob(requestBody, context);
    }
}
