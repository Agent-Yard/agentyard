package com.example.lynxus.extensiontemplate.channel;

import com.lynxus.extension.sdk.generated.protocol.model.ChannelOutboundFrame;
import org.springframework.stereotype.Service;

@Service
public final class TemplateChannelOutboundFrameProcessor implements ChannelOutboundFrameProcessor {
    @Override
    public void process(ChannelOutboundFrame frame) {
        // Connect this to your provider delivery API. FINAL_DELIVERY frames must be idempotent
        // and ACKed through LynxusChannelGatewayClient only after provider delivery succeeds.
    }
}
