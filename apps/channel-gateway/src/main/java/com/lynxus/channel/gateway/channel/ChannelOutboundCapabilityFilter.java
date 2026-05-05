package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.extension.ChannelProviderDescriptor;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import org.springframework.stereotype.Component;

@Component
public class ChannelOutboundCapabilityFilter {
    public boolean allows(ChannelProviderDescriptor descriptor, ChannelOutboundFrame frame) {
        if (descriptor == null || frame == null) {
            return false;
        }
        return switch (frame.kind()) {
            case TYPING_START, TYPING_STOP -> descriptor.supportsTyping();
            case DRAFT_UPDATE, DRAFT_COMPLETE, DRAFT_DISCARD -> descriptor.supportsDraftUpdate();
            case FINAL_DELIVERY -> descriptor.supportsFinalDelivery();
        };
    }

    public boolean isFinal(ChannelOutboundFrame frame) {
        return frame != null && frame.kind() == ChannelOutboundFrameKind.FINAL_DELIVERY;
    }
}
