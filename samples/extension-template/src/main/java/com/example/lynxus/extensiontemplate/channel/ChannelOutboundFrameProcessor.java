package com.example.lynxus.extensiontemplate.channel;

import com.lynxus.extension.sdk.generated.protocol.model.ChannelOutboundFrame;

public interface ChannelOutboundFrameProcessor {
    void process(ChannelOutboundFrame frame);
}
