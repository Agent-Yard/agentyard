package com.lynxus.channel.gateway.extension;

import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface GatewayNativeChannelProviderAdapter {
    String providerType();

    Map<String, Object> descriptor();

    default List<OutboundFrameDispatch> prepareOutboundFrames(ChannelGatewayProfile profile, List<ChannelOutboundFrame> frames) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }
        return frames.stream()
            .map(frame -> new OutboundFrameDispatch(frame, List.of(frame)))
            .toList();
    }

    void consumeOutboundFrame(ChannelGatewayProfile profile, ChannelOutboundFrame frame);

    record OutboundFrameDispatch(ChannelOutboundFrame frame, List<ChannelOutboundFrame> drainedFrames) {
        public OutboundFrameDispatch {
            Objects.requireNonNull(frame, "frame");
            if (drainedFrames == null || drainedFrames.isEmpty()) {
                throw new IllegalArgumentException("drainedFrames is required");
            }
            drainedFrames = List.copyOf(drainedFrames);
        }
    }
}
