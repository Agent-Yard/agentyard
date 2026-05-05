package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import java.time.Duration;
import java.util.List;

public interface ChannelOutboundFrameHandoff {
    boolean offer(ChannelOutboundProfileConsumer consumer, ChannelOutboundFrame frame, int maxPendingFinals, int transientCapacity);

    int pendingFinals(ChannelOutboundProfileConsumer consumer);

    List<ChannelOutboundFrame> drain(ChannelOutboundProfileConsumer consumer, int limit);

    List<ChannelOutboundFrame> drainOrWait(ChannelOutboundProfileConsumer consumer, int limit, Duration timeout) throws InterruptedException;

    boolean hasFrames(ChannelOutboundProfileConsumer consumer);

    void requeueFirst(ChannelOutboundProfileConsumer consumer, ChannelOutboundFrame frame);

    void registerListener(FrameAvailableListener listener);

    void unregisterListener(FrameAvailableListener listener);

    @FunctionalInterface
    interface FrameAvailableListener {
        void framesAvailable(ChannelOutboundProfileConsumer consumer);
    }
}
