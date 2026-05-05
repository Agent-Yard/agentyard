package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import java.util.List;

public interface ChannelOutboundFrameHandoff {
    boolean offer(ChannelOutboundProfileConsumer consumer, ChannelOutboundFrame frame, int maxPendingFinals, int transientCapacity);

    int pendingFinals(ChannelOutboundProfileConsumer consumer);

    List<ChannelOutboundFrame> drain(ChannelOutboundProfileConsumer consumer, int limit);
}
